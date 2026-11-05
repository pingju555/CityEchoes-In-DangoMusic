package me.cityechoes.mixin

import me.cityechoes.mods.CityEchoesModule
import me.cityechoes.ui.CityLog
import me.cityechoes.ui.DangoSettingsBridge
import me.cityechoes.ui.SettingsModel

import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * 把"都市回响"并入 dangomusic 自己的设置页，作为第三个 tab。
 *
 * 三个注入点，全部 `require = 0`：dangomusic 哪天重构了这里，注入失败也只是退回 addon
 * 自带的 K 页面，不会因为 Mixin apply 失败把游戏崩掉。
 *
 * 1. `buildSettings` 的唯一 `areturn` —— 在 2 个 tab 后面追加第 3 个。
 * 2. `drawTabs` 里唯一一次 `listOf(...)` —— 把 2 个标签换成 3 个（多出来的就是新 tab）。
 * 3. `<init>` 末尾 —— 往 `choiceLabels` 里塞我们的下拉文案。
 *    这一步不能省：drawRow 的 CHOICE 分支用的是 `MapsKt.getValue`（缺键直接抛异常），
 *    不像别处用 `get`。
 */
@Mixin(targets = ["me.sorihiko.dango.musicui.SettingsPage"])
abstract class SettingsPageMixin {

    @Inject(method = ["buildSettings"], at = [At("RETURN")], cancellable = true, require = 0)
    private fun cityechoesAppendTab(cir: CallbackInfoReturnable<List<Any?>>) {
        val module = CityEchoesModule.INSTANCE
        if (module == null) {
            CityLog.log.warn("[CityEchoes] buildSettings hooked, but CityEchoesModule.INSTANCE is null")
            return
        }
        val groups = DangoSettingsBridge.buildTab(module)
        if (groups == null) {
            CityLog.log.warn("[CityEchoes] buildTab returned null, no tab will be added")
            return
        }
        val base = cir.returnValue
        if (base == null) {
            CityLog.log.warn("[CityEchoes] buildSettings returned null, no tab will be added")
            return
        }
        val out = ArrayList<Any?>(base.size + 1)
        for (g in base) out.add(g)
        out.add(groups)
        cir.returnValue = out
        DangoSettingsBridge.onApplied()
    }

    @Redirect(
        method = ["drawTabs"],
        at = At(
            value = "INVOKE",
            target = "Lme/sorihiko/dango/shadow/kotlin/collections/CollectionsKt;listOf([Ljava/lang/Object;)Ljava/util/List;",
        ),
        require = 0,
    )
    private fun cityechoesTabLabels(args: Array<Any?>): List<Any?> {
        DangoSettingsBridge.onLabelRedirect(args.size)
        val out = ArrayList<Any?>(args.size + 1)
        for (a in args) out.add(a)
        // tab 数据求值在画标签之后（首帧），所以只认 tab 真的挂上了才多画一个标签
        if (DangoSettingsBridge.tabApplied) out.add(DangoSettingsBridge.tabLabel())
        return out
    }

    @Inject(method = ["<init>"], at = [At("RETURN")], require = 0)
    private fun cityechoesChoiceLabels(ci: CallbackInfo) {
        try {
            val f = javaClass.getDeclaredField("choiceLabels")
            f.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = f.get(this) as? MutableMap<String, List<String>> ?: return
            map.putAll(SettingsModel.CHOICES)
            DangoSettingsBridge.onChoiceLabelsInjected(SettingsModel.CHOICES.size)
        } catch (t: Throwable) {
            // 拿不到就只是下拉项显示英文，不影响其它行
            CityLog.log.warn("[CityEchoes] could not inject choice labels", t)
        }
    }
}
