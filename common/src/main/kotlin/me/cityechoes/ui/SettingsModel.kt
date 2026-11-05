package me.cityechoes.ui

import me.cityechoes.mods.CityEchoesModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.ColorValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.TextValue

import java.util.Locale
import kotlin.math.roundToInt

/** 一个设置行用哪种控件 */
internal enum class Ctl {
    /** 开关 */
    TOGGLE,

    /** 滑条（点数值可以手动输入） */
    SLIDER,

    /** ◀ 选项 ▶ */
    CHOICE,

    /** 颜色块 + 取色器 */
    COLOR,

    /** ◀ 字体 ▶ */
    FONT,
}

internal class Row(
    val key: String,
    val label: String,
    val desc: String,
    val ctl: Ctl,
    /** BooleanValue / IntValue / FloatValue / ColorValue / TextValue，按 [ctl] 强转 */
    val value: Any,
    /** 依赖的开关：它关掉时这一行变暗 */
    val dep: BooleanValue?,
    val fmt: (Any) -> String,
)

internal class Group(val title: String, val sub: String, val rows: List<Row>)

/**
 * 把 [CityEchoesModule] 里带 `@ModuleValue` 的字段读出来，编成"分组 → 行"的展示模型。
 *
 * 值的读写完全走 reflection（和主 mod 自己的设置页做法一样），所以：
 *  - 新增一个 `@ModuleValue` 字段，这里不改也能显示（会落进 Other 组）；
 *  - 标签和说明直接复用注解里的 name / desc，不用维护第二份文案。
 */
internal object SettingsModel {

    /** 分组：标题、副标题、成员字段名（顺序即显示顺序） */
    private val LAYOUT: List<Triple<String, String, List<String>>> = listOf(
        Triple(
            "Lyrics", "What the lyrics look like",
            listOf(
                "worldMode", "scale", "lyricColors", "textColor", "rainbowSpeed",
                "translation", "translationColor", "translationAnimation", "translationWiggle",
            ),
        ),
        Triple(
            "Type", "Font and hand-drawn jitter",
            listOf("lyricFont", "smoothFont", "wiggle", "wiggleFps", "wiggleAmount"),
        ),
        Triple(
            "Position", "Where the lyrics float",
            listOf("distance", "facePlayer", "keepAway", "aboveGround", "spread"),
        ),
        Triple(
            "City motion", "Per-letter performance",
            listOf(
                "cityAnim", "cityJump", "cityMotionStrength", "cityShakeFreq",
                "cityFallDistance", "cityTilt", "cityScrambleExit", "citySingingEmphasis",
            ),
        ),
        Triple("Timing", "How long lines stay", listOf("cityExtended", "cityExtendedMs")),
        Triple("Glow", "Deep Glow", listOf("glow", "glowNightOnly", "glowStrength", "glowRadius")),
        Triple("Song info", "Top left corner", listOf("nowPlaying", "showCover")),
    )

    /** 行 ← 依赖的开关（关掉时变暗） */
    private val DEPS: Map<String, String> = mapOf(
        "glowNightOnly" to "glow", "glowStrength" to "glow", "glowRadius" to "glow",
        "wiggleFps" to "wiggle", "wiggleAmount" to "wiggle",
        "translationColor" to "translation", "translationAnimation" to "translation",
        "translationWiggle" to "translation",
        "cityExtendedMs" to "cityExtended",
        "distance" to "worldMode", "facePlayer" to "worldMode", "keepAway" to "worldMode",
        "aboveGround" to "worldMode", "spread" to "worldMode",
        "showCover" to "nowPlaying",
    )

    /** 用 ◀ ▶ 切换的选项：字段名 → 每个值的显示名（从 int 最小值开始） */
    val CHOICES: Map<String, List<String>> = mapOf(
        "lyricColors" to listOf("Custom color", "Cover color", "Cover gradient", "Rainbow"),
        "cityAnim" to listOf("Ripple wave", "Ripple shake", "Fall wave", "Fall shake"),
    )

    fun build(module: CityEchoesModule): List<Group> {
        val fields = fieldsOf(module)
        val out = ArrayList<Group>()
        val used = HashSet<String>()

        for ((title, sub, keys) in LAYOUT) {
            val rows = ArrayList<Row>()
            for (k in keys) {
                val (ann, v) = fields[k] ?: continue
                used += k
                rows += row(k, ann, v, fields)
            }
            if (rows.isNotEmpty()) out += Group(title, sub, rows)
        }

        // 布局表没写到的字段：兜底放最后，新增设置项不会"消失"
        val rest = fields.filterKeys { it !in used }
        if (rest.isNotEmpty()) {
            out += Group("Other", "More options", rest.map { (k, e) -> row(k, e.first, e.second, fields) })
        }
        return out
    }

    // ---------------------------------------------------------------- 内部

    private fun fieldsOf(module: CityEchoesModule): LinkedHashMap<String, Pair<ModuleValue, Any>> {
        val out = LinkedHashMap<String, Pair<ModuleValue, Any>>()
        for (f in module.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            val v = try {
                f.isAccessible = true
                f.get(module)
            } catch (_: Throwable) {
                null
            } ?: continue
            out[f.name] = ann to v
        }
        return out
    }

    private fun row(
        key: String,
        ann: ModuleValue,
        value: Any,
        all: Map<String, Pair<ModuleValue, Any>>,
    ): Row {
        val ctl = when {
            value is BooleanValue -> Ctl.TOGGLE
            key in CHOICES -> Ctl.CHOICE
            value is ColorValue -> Ctl.COLOR
            value is TextValue -> Ctl.FONT
            else -> Ctl.SLIDER           // IntValue / FloatValue
        }
        val dep = DEPS[key]?.let { all[it]?.second as? BooleanValue }
        return Row(key, ann.name.ifEmpty { key }, ann.desc, ctl, value, dep, formatter(key))
    }

    private fun fmt1(x: Float): String = String.format(Locale.ROOT, "%.1f", x)

    private fun fmtN(x: Float, decimals: Int): String =
        if (decimals <= 0) x.roundToInt().toString() else String.format(Locale.ROOT, "%.${decimals}f", x)

    private fun formatter(key: String): (Any) -> String = when (key) {
        "scale" -> { v -> fmt1((v as FloatValue).value) + "x" }
        "distance" -> { v -> "${(v as FloatValue).value.roundToInt()} blocks" }
        "spread" -> { v -> "${(v as FloatValue).value.roundToInt()}\u00B0" }
        "glowRadius", "cityMotionStrength" -> { v -> "${(v as FloatValue).value.roundToInt()}%" }
        "cityExtendedMs" -> { v -> "${(v as FloatValue).value.roundToInt()} ms" }
        "wiggleFps" -> { v -> "${(v as IntValue).value} fps" }
        "lyricColors", "cityAnim" -> { v ->
            val labels = CHOICES.getValue(key)
            labels[(v as IntValue).value.coerceIn(0, labels.size - 1)]
        }
        "lyricFont" -> { v ->
            val name = (v as TextValue).value
            if (name.isEmpty()) "Default" else name
        }
        else -> { v ->
            when (v) {
                is IntValue -> v.value.toString()
                is FloatValue -> fmtN(v.value, v.decimals)
                else -> ""
            }
        }
    }
}
