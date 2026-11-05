package me.cityechoes.ui

import me.cityechoes.mods.CityEchoesModule
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.TextValue

import java.lang.reflect.Constructor
import java.lang.reflect.Proxy

import org.slf4j.LoggerFactory

/**
 * 把 [SettingsModel] 的分组模型翻译成 dangomusic 设置页自己的 `Group` / `Row`，
 * 让"都市回响"作为原生第三个 tab 出现在它的设置页里。
 *
 * 为什么用反射：`SettingsPage$Group` / `$Row` / `$Ctl` / `$FontChoice` 在 dangomusic 里是
 * `internal`，字节码层面是 package-private 的 final class，从 addon 的包直接 new 不了。
 * 这样不需要 Access Transformer，也不用把 addon 的包名改成 dangomusic 的。
 *
 * ⚠️ `getConstructor` 找得到 public 构造器，但**构造器所在的类本身是 package-private**，
 * 跨包 `newInstance` 依然会抛 `IllegalAccessException`（访问检查看的是「成员 + 声明类」两者）。
 * 所以每个构造器都必须 `setAccessible(true)`，否则整条注入链会静默失败
 * —— 之前就是漏了这一步，页面里一直不出第三个 tab。
 *
 * 任何一步失败都返回 null，调用方直接放弃注入 —— 独立的 K 页面还在，游戏不会崩。
 */
internal object DangoSettingsBridge {

    private const val P = "me.sorihiko.dango.musicui.SettingsPage"

    private val LOG = LoggerFactory.getLogger("CityEchoes")

    private var resolved = false
    private var broken = false

    private var groupCtor: Constructor<*>? = null
    private var rowCtor: Constructor<*>? = null
    private var fontCtor: Constructor<*>? = null
    private var ctlClass: Class<*>? = null
    private var function1: Class<*>? = null

    /**
     * `buildSettings` 是否已经成功挂上第三个 tab。
     *
     * `drawTabs` 的画标签时机在 tab 数据求值之前（首帧），所以这里用这个标记做协调：
     * 只有 tab 真的加进去了，才多画一个标签 —— 否则会出现"3 个标签 2 个 tab"，
     * 虽然 dangomusic 自己会把索引 `coerceIn` 掉不会崩，但第三个标签点了没反应会很怪。
     */
    @Volatile
    var tabApplied = false
        private set

    fun onApplied() {
        if (!tabApplied) LOG.info("[CityEchoes] City Echoes tab attached to the native settings page")
        tabApplied = true
    }

    /** `drawTabs` 的 Redirect 第一次真的被调用时记一笔，用来区分"tab 挂上了但标签没多" */
    private var labelHooked = false

    fun onLabelRedirect(argCount: Int) {
        if (labelHooked) return
        labelHooked = true
        LOG.info("[CityEchoes] drawTabs label redirect hit ({} labels)", argCount)
    }

    /**
     * `<init>` 的注入每次 new SettingsPage 都会跑（每次打开设置页一次），
     * 但这条信息只需要确认一次，否则日志会被刷屏。
     */
    private var choiceLogged = false

    fun onChoiceLabelsInjected(count: Int) {
        if (choiceLogged) return
        choiceLogged = true
        LOG.info("[CityEchoes] choice labels injected into SettingsPage ({} entries)", count)
    }

    /** 第三个 tab 的标题，走 dangomusic 自己的 I18n（I18nMixin 会补上中文） */
    fun tabLabel(): String = translate("City Echoes")

    /** 构造第三个 tab 的分组列表；返回 null 表示这次放弃注入 */
    fun buildTab(module: CityEchoesModule): List<Any>? {
        if (broken || !resolve()) return null
        return try {
            val built = SettingsModel.build(module)
            val out = ArrayList<Any>(built.size)
            // 只有第一组带模块总开关，否则同一个模块会画出 7 个开关
            built.forEachIndexed { i, g -> out += group(module, g, i == 0) }
            out
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    // ---------------------------------------------------------------- 构造

    private fun group(module: CityEchoesModule, g: Group, toggle: Boolean): Any {
        val rows = ArrayList<Any>(g.rows.size)
        for (r in g.rows) {
            // 平滑字体已经并进 dangomusic 的字体选择器（FontChoice 里带 smooth 开关）
            if (r.key == "smoothFont") continue
            rows += row(module, r)
        }
        return groupCtor!!.newInstance(g.title, g.sub, module as Module, toggle, rows)
    }

    private fun row(module: CityEchoesModule, r: Row): Any {
        var value: Any = r.value
        if (r.ctl == Ctl.FONT && r.value is TextValue) {
            val smooth = field(module, "smoothFont") as? BooleanValue
            val fc = fontCtor
            if (smooth != null && fc != null) value = fc.newInstance(r.value, smooth)
        }
        return rowCtor!!.newInstance(
            r.label,
            r.desc,
            value,
            ctl(r.ctl.name),
            r.dep,
            formatter(r.fmt),
            1f,
            r.key,
        )
    }

    private fun ctl(name: String): Any =
        ctlClass!!.enumConstants.first { (it as Enum<*>).name == name }

    /**
     * `Row.fmt` 的类型是 dangomusic shade 过的 `Function1`，我们编译期拿不到它的 Kotlin 身份，
     * 所以用动态代理实现：只要 `invoke` 能调通，dangomusic 那边看不出区别。
     */
    private fun formatter(fn: (Any) -> String): Any {
        val iface = function1!!
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            when (method.name) {
                "invoke" -> try {
                    fn(args?.getOrNull(0) ?: "")
                } catch (_: Throwable) {
                    ""
                }

                "getArity" -> 1
                "toString" -> "CityEchoesFormatter"
                "hashCode" -> System.identityHashCode(fn)
                "equals" -> false
                else -> null
            }
        }
    }

    // ---------------------------------------------------------------- 反射解析

    private fun resolve(): Boolean {
        if (resolved) return groupCtor != null
        resolved = true
        return try {
            val groupCls = Class.forName("$P\$Group")
            val rowCls = Class.forName("$P\$Row")
            val fontCls = Class.forName("$P\$FontChoice")
            ctlClass = Class.forName("$P\$Ctl")
            function1 = Class.forName("me.sorihiko.dango.shadow.kotlin.jvm.functions.Function1")

            groupCtor = groupCls.getConstructor(
                String::class.java, String::class.java, Module::class.java,
                java.lang.Boolean.TYPE, java.util.List::class.java,
            )
            rowCtor = rowCls.getConstructor(
                String::class.java, String::class.java, Any::class.java, ctlClass,
                BooleanValue::class.java, function1, java.lang.Float.TYPE, String::class.java,
            )
            fontCtor = fontCls.getConstructor(TextValue::class.java, BooleanValue::class.java)

            // 声明类是 package-private，光有 public 构造器不够，跨包 newInstance 会被拒。
            for (c in listOfNotNull(groupCtor, rowCtor, fontCtor)) c.isAccessible = true

            LOG.info("[CityEchoes] native settings bridge resolved (Group/Row/FontChoice opened)")
            true
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    private fun field(module: CityEchoesModule, name: String): Any? = try {
        val f = module.javaClass.getDeclaredField(name)
        f.isAccessible = true
        f.get(module)
    } catch (_: Throwable) {
        null
    }

    private fun translate(s: String): String = try {
        I18n.INSTANCE.text(s)
    } catch (_: Throwable) {
        s
    }

    private fun fail(t: Throwable) {
        broken = true
        // 必须走 slf4j：Forge 不会把 System.err 收进 latest.log，之前的报错全丢了
        LOG.error("[CityEchoes] native settings tab unavailable, the K page still works", t)
    }
}
