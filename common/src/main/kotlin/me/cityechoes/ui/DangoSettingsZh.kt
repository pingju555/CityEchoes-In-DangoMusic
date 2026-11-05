package me.cityechoes.ui

import me.cityechoes.mods.CityEchoesModule
import me.sorihiko.dango.module.values.ModuleValue

/**
 * 原生设置页的中文词条。
 *
 * dangomusic 的 `drawGroup` / `drawRow` 会把标题、说明、下拉项都过一遍 `I18n.text()`，
 * 查不到就原样返回。所以中文环境下要补一张表，否则都市回响那一屏会是英文。
 *
 * 键刻意按**字段名**写而不是按英文文案：英文名来自 `@ModuleValue(name = ...)`，
 * 运行时读注解现取，写错字段名只会少一条翻译，不会把文案对错行。
 */
internal object DangoSettingsZh {

    private const val TAB = "City Echoes"

    /** (英文标题, 英文副标题, 中文标题 to 中文副标题) */
    private val GROUPS: List<Triple<String, String, Pair<String, String>>> = listOf(
        Triple("Lyrics", "What the lyrics look like", "歌词" to "歌词长什么样"),
        Triple("Type", "Font and hand-drawn jitter", "字体" to "字体与手绘抖动"),
        Triple("Position", "Where the lyrics float", "位置" to "歌词漂浮在哪里"),
        Triple("City motion", "Per-letter performance", "都市动态" to "逐字演出"),
        Triple("Timing", "How long lines stay", "时间" to "每句停留多久"),
        Triple("Glow", "Deep Glow", "辉光" to "深辉光"),
        Triple("Song info", "Top left corner", "曲目信息" to "左上角"),
        Triple("Other", "More options", "其他" to "更多选项"),
    )

    /** 字段名 → 名称 / 说明 */
    private val FIELDS: Map<String, Pair<String, String>> = mapOf(
        "worldMode" to ("3D 歌词" to "在世界里显示都市回响歌词"),
        "nowPlaying" to ("左上角曲目" to "在左上角显示当前播放的歌"),
        "showCover" to ("显示封面" to "在左上角显示专辑封面"),
        "scale" to ("缩放" to "歌词大小"),
        "distance" to ("距离" to "3D 歌词出现的位置有多远（格）"),
        "glow" to ("深辉光" to "白热核心 + 大范围柔光晕"),
        "glowNightOnly" to ("仅夜间发光" to "白天关闭发光，入夜自动亮起"),
        "glowStrength" to ("辉光强度" to "发光亮度"),
        "glowRadius" to ("辉光半径" to "光晕扩散范围，越大越宽越柔"),
        "smoothFont" to ("平滑字体" to "使用平滑字体代替像素字体"),
        "lyricFont" to ("歌词字体" to "像素字体、平滑字体，或 config/dangomusic/fonts 里的 .ttf / .otf"),
        "wiggle" to ("抖动" to "每个字低帧率抖动，像手绘动画"),
        "wiggleFps" to ("抖动帧率" to "越低越像手绘"),
        "wiggleAmount" to ("抖动幅度" to "抖动强度"),
        "translation" to ("翻译" to "有翻译时在每句歌词下方显示"),
        "translationAnimation" to ("翻译动画" to "翻译逐字出现，和歌词一样"),
        "translationWiggle" to ("翻译抖动" to "翻译跟着歌词一起抖动（用抖动的帧率与幅度）"),
        "cityJump" to ("跳动幅度" to "字母随音乐弹起的高度"),
        "cityAnim" to ("演出模式" to "涟漪波动 / 涟漪抖动 / 坠落波动 / 坠落抖动"),
        "cityScrambleExit" to ("乱序退场" to "退场时字母变成符号散开"),
        "citySingingEmphasis" to ("演唱强调" to "唱到的字母会放大"),
        "cityTilt" to ("整行倾斜" to "整行微微倾斜"),
        "cityExtended" to ("延长停留" to "一句结束后保留一小段时间"),
        "cityExtendedMs" to ("延长时间" to "上一句停留多久"),
        "cityMotionStrength" to ("运动强度" to "整体运动幅度 0-100%"),
        "cityFallDistance" to ("坠落距离" to "坠落类样式里字母下落的距离"),
        "cityShakeFreq" to ("抖动频率" to "抖动类样式的抖动速度"),
        "spread" to ("散布角度" to "每行可以偏离多少度"),
        "aboveGround" to ("不沉地面" to "歌词不低于视线，所以不会陷进地里"),
        "keepAway" to ("自动避让" to "走或跑向歌词时它会后退"),
        "facePlayer" to ("始终朝向" to "歌词一直转向你"),
        "lyricColors" to ("歌词配色" to "歌词和翻译的颜色来源"),
        "rainbowSpeed" to ("彩虹速度" to "彩虹流过歌词的快慢"),
        "textColor" to ("自定义歌词色" to "歌词配色选为自定义颜色时使用"),
        "translationColor" to ("自定义翻译色" to "歌词配色选为自定义颜色时使用"),
    )

    /** 字段名 → 每个选项的中文（顺序与 [SettingsModel.CHOICES] 一致） */
    private val CHOICES: Map<String, List<String>> = mapOf(
        "lyricColors" to listOf("自定义颜色", "封面主色", "封面渐变", "彩虹"),
        "cityAnim" to listOf("涟漪波动", "涟漪抖动", "坠落波动", "坠落抖动"),
    )

    private var cached: Map<String, String>? = null

    private var mergedBase: Map<String, String>? = null
    private var mergedOut: Map<String, String>? = null

    /**
     * 把中文词条合并进 dangomusic 自己的中文字典。
     *
     * `I18n.text()` 每画一段文字都会调一次 `getChinese()`，所以这里按 base 的**实例身份**
     * 缓存合并结果，避免每帧重复拷贝整张表。模块还没注册时（dictionary 为空）直接返回原表，
     * 也不写缓存 —— 否则会把"还没词条"的空结果永久固化下来。
     */
    fun merge(base: Map<String, String>): Map<String, String> {
        if (mergedBase === base) mergedOut?.let { return it }
        val zh = dictionary()
        if (zh.isEmpty()) return base
        val out = LinkedHashMap<String, String>(base.size + zh.size)
        out.putAll(base)
        for ((k, v) in zh) out.putIfAbsent(k, v)   // 本体自己有的优先
        mergedBase = base
        mergedOut = out
        return out
    }

    /** 英文词条 → 中文词条；模块还没注册时返回空表 */
    fun dictionary(): Map<String, String> {
        cached?.let { return it }
        val module = CityEchoesModule.INSTANCE ?: return emptyMap()
        val out = LinkedHashMap<String, String>(192)
        out[TAB] = "都市回响"

        for ((title, sub, zh) in GROUPS) {
            out[title] = zh.first
            out[sub] = zh.second
        }

        for (f in module.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            val zh = FIELDS[f.name] ?: continue
            if (ann.name.isNotEmpty()) out[ann.name] = zh.first
            if (ann.desc.isNotEmpty()) out[ann.desc] = zh.second
        }

        for ((key, labels) in SettingsModel.CHOICES) {
            val zh = CHOICES[key] ?: continue
            for (i in labels.indices) {
                val translated = zh.getOrNull(i) ?: continue
                out[labels[i]] = translated
            }
        }

        cached = out
        return out
    }
}
