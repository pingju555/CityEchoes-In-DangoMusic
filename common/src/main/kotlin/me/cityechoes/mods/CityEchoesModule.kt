package me.cityechoes.mods

import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.gfx.CjkStyle
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.ColorValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.module.values.TextValue
import me.sorihiko.dango.music.CoverArt
import me.sorihiko.dango.music.LyricFonts
import me.sorihiko.dango.music.NeteaseTrack

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.asin

import me.cityechoes.CityEchoes
import me.cityechoes.DangoBridge

// ---- 歌词配色（放在顶层 const，属性初始化时即可用，避免前向引用） ----
private const val COLORS_CUSTOM = 0
private const val COLORS_COVER = 1
private const val COLORS_GRADIENT = 2
private const val COLORS_RAINBOW = 3

/** 一句歌词从头到尾跨过多少色相（1 = 整个色环） */
private const val RAINBOW_SPAN = 0.6f

// ---- 都市回响（City Echoes）动画样式 ----
private const val CITY_ANIM_RIPPLE_WAVE = 0
private const val CITY_ANIM_RIPPLE_SHAKE = 1
private const val CITY_ANIM_FALL_WAVE = 2
private const val CITY_ANIM_FALL_SHAKE = 3

/** 把都市回响的像素空间换算到 DangoMusic 的本地单位（一行高约 11 单位） */
private const val CITY_PIXELS = 11f

/** 都市回响招牌：彩色描边相对白芯的放大倍数（绕字心，形成彩色轮廓） */
private const val CITY_CORE_SCALE = 1.2f

/**
 * 都市回响（City Echoes）独立 addon 模块。
 *
 * 只通过 dangomusic 的公开 API 自绘 3D 歌词：抓取歌词、跟随播放进度、用公开 Gfx 绘制、
 * 监听同样的公开事件——不依赖 dangomusic 内部实现，主 mod 更新也不会破。
 *
 * 与合并版 LyricsModule 的区别：
 *  - 永远走都市回响路径，没有"现代 / 都市回响"切换；
 *  - 实时响度用公开 [MusicPlayer.beatPulse] 代替合并版私有字段 rmsEnergy（不注入 MusicPlayer 字节码）；
 *  - 歌词抓取用公开 [LyricsProvider.neteaseLyricById]（合并版用内部的 MusicSources）；
 *  - 启用时由 Mixin 接管原版 LyricsModule 的 WorldRender / HudRender，避免双份歌词。
 */
@ModuleMeta(category = Category.MUSIC, name = "City Echoes")
class CityEchoesModule : Module() {

    companion object {
        /** 由 DangoMusicMixin 在 dangomusic 初始化时填入，供 LyricsModuleMixin 判断要不要接管原版渲染。 */
        var INSTANCE: CityEchoesModule? = null

        /** 本模块是否启用（启用则取消原版 3D 歌词与 Now playing HUD，改由本模块画）。 */
        fun isActive(): Boolean = INSTANCE?.enabled ?: false
    }

    // ---------------------------------------------------------------- 设置（都市回响 + 共享渲染项）

    @ModuleValue(name = "3D lyrics", desc = "Show City Echoes lyrics floating in the world")
    private val worldMode = BooleanValue(true)

    @ModuleValue(name = "Top-left song info", desc = "Show the current song in the top left")
    private val nowPlaying = BooleanValue(false)

    @ModuleValue(name = "Show cover", desc = "Show the album cover in the top left")
    private val showCover = BooleanValue(true)

    @ModuleValue(name = "Scale", desc = "Lyrics size")
    private val scale = FloatValue(0.5f, 3f, 1f, 1)

    @ModuleValue(name = "Distance", desc = "How far away 3D lyrics appear (blocks)")
    private val distance = FloatValue(3f, 40f, 10f, 1)

    @ModuleValue(name = "Glow", desc = "Deep Glow style: white-hot core, wide soft halo")
    private val glow = BooleanValue(true)

    @ModuleValue(name = "Night glow only", desc = "Turn glow off in daylight and back on when it gets dark")
    private val glowNightOnly = BooleanValue(false)

    @ModuleValue(name = "Glow strength", desc = "Glow brightness")
    private val glowStrength = FloatValue(0f, 3f, 0.3f, 2)

    @ModuleValue(name = "Glow radius", desc = "Halo spread: higher is wider and softer")
    private val glowRadius = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue(name = "Smooth font", desc = "Smooth font instead of the Minecraft pixel font")
    private val smoothFont = BooleanValue(false)

    @ModuleValue(name = "Lyric font", desc = "Pixel font, smooth font, or a .ttf / .otf font from config/dangomusic/fonts")
    private val lyricFont = TextValue("")

    @ModuleValue(name = "Wiggle", desc = "Low-fps jitter on every letter, like hand-drawn animation")
    private val wiggle = BooleanValue(true)

    @ModuleValue(name = "Wiggle fps", desc = "Jitter frame rate: lower feels more hand-drawn")
    private val wiggleFps = IntValue(4, 24, 8)

    @ModuleValue(name = "Wiggle amount", desc = "Jitter strength")
    private val wiggleAmount = FloatValue(0f, 2f, 0.8f, 2)

    @ModuleValue(name = "Translation", desc = "Show the translation under each lyric line when the song has one")
    private val translation = BooleanValue(false)

    @ModuleValue(name = "Translation animation", desc = "The translation appears character by character like the lyrics")
    private val translationAnimation = BooleanValue(true)

    @ModuleValue(name = "Translation wiggle", desc = "The translation wiggles like the lyrics (uses the Wiggle speed and amount)")
    private val translationWiggle = BooleanValue(true)

    // 都市回响参数
    @ModuleValue(name = "City jump", desc = "How far letters bounce with the music (City Echoes)")
    private val cityJump = FloatValue(0f, 3f, 1.5f, 2)

    @ModuleValue(name = "City anim", desc = "City Echoes animation: 0 ripple wave / 1 ripple shake / 2 fall wave / 3 fall shake")
    private val cityAnim = IntValue(CITY_ANIM_RIPPLE_WAVE, CITY_ANIM_FALL_SHAKE, CITY_ANIM_RIPPLE_WAVE)

    @ModuleValue(name = "City scramble exit", desc = "Letters turn into symbols and scatter on exit (City Echoes)")
    private val cityScrambleExit = BooleanValue(true)

    @ModuleValue(name = "City singing emphasis", desc = "Letters grow as they are sung (City Echoes)")
    private val citySingingEmphasis = BooleanValue(true)

    @ModuleValue(name = "City tilt", desc = "Whole line tilts a little (City Echoes)")
    private val cityTilt = BooleanValue(true)

    @ModuleValue(name = "City extended display", desc = "Keep previous lines on screen a bit after they end (City Echoes)")
    private val cityExtended = BooleanValue(true)

    @ModuleValue(name = "City extended ms", desc = "How long previous lines linger (City Echoes)")
    private val cityExtendedMs = FloatValue(0f, 4000f, 1200f, 0)

    @ModuleValue(name = "City motion", desc = "Overall motion amount 0-100% (City Echoes)")
    private val cityMotionStrength = FloatValue(0f, 100f, 100f, 0)

    @ModuleValue(name = "City fall distance", desc = "How far letters drop when using a fall style (City Echoes)")
    private val cityFallDistance = FloatValue(0f, 80f, 30f, 0)

    @ModuleValue(name = "City shake freq", desc = "Shake speed for shake styles (City Echoes)")
    private val cityShakeFreq = FloatValue(1f, 20f, 6f, 1)

    // 歌词版式 / 颜色
    @ModuleValue(name = "Spread", desc = "How far left or right each line can appear (degrees)")
    private val spread = FloatValue(0f, 60f, 30f, 0)

    @ModuleValue(name = "Above ground", desc = "Lyrics never appear below your eyes, so they don't sink into the ground")
    private val aboveGround = BooleanValue(false)

    @ModuleValue(name = "Keep away", desc = "Lyrics back off when you walk or sprint into them")
    private val keepAway = BooleanValue(true)

    @ModuleValue(name = "Face player", desc = "Lyrics keep turning to face you")
    private val facePlayer = BooleanValue(true)

    @ModuleValue(name = "Lyric colors", desc = "Where the lyric and translation colors come from")
    private val lyricColors = IntValue(COLORS_CUSTOM, COLORS_RAINBOW, COLORS_CUSTOM)

    @ModuleValue(name = "Rainbow speed", desc = "How fast the rainbow flows across the lyrics")
    private val rainbowSpeed = FloatValue(0.05f, 1f, 0.25f, 2)

    @ModuleValue(name = "Custom lyric color", desc = "Used when Lyric colors is set to Custom color")
    private val textColor = ColorValue(0xF7E1D7)

    @ModuleValue(name = "Custom translation color", desc = "Used when Lyric colors is set to Custom color")
    private val translationColor = ColorValue(0x803D66)

    // ---------------------------------------------------------------- 状态

    /** 正在播放的歌（来自内置播放器） */
    private class NowPlaying(val track: NeteaseTrack, val playing: Boolean) {
        val key: String get() = track.key
        val title: String get() = track.name
        val artist: String get() = track.artists.joinToString(" / ")
    }

    private val cover = CoverArt()
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "CityEchoes-Lyrics").apply { isDaemon = true } }

    private object NotFound
    private val cache = ConcurrentHashMap<String, Any>()     // key -> Lyrics 或 NotFound
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val notFoundAt = ConcurrentHashMap<String, Long>()

    private var current: NowPlaying? = null
    private var currentKey: String? = null
    private var songPos = 0.0                 // 毫秒，本地平滑时钟
    private var lastSeenNanos = 0L

    private val random = Random()

    /** 一句漂浮的歌词（都市回响里同时可多句并存） */
    private class Floating(
        val lines: List<String>,
        val startMs: Long,
        val revealMs: Long,
        var x: Double, var y: Double, var z: Double,
        var yaw: Float, var pitch: Float,
        /** 每个非空白字符出现的时间（相对这句开始，毫秒） */
        val charTimes: LongArray = LongArray(0),
        /** 这一句结束时间（相对歌曲开始，毫秒）；延长显示时比下一句开始更晚 */
        var endMs: Long = 0L,
        /** 原始 LyricLine，取逐字时间用 */
        val lineRef: LyricLine? = null,
        /** 每个非空白字符的结束时间（相对这句开始，毫秒），演唱强调用 */
        var charEnd: LongArray = LongArray(0),
        /** 在歌词行数组里的下标，调度去重用 */
        var cityIndex: Int = -1,
    ) {
        /** 这一句的翻译（已经按宽度换好行；没有就是空的），画在原文下面 */
        var translation: List<String> = emptyList()
    }

    /** 正在往发光画布上画（只画字本身，不画阴影） */
    private var glowPass = false

    // 都市回响模式：同时可在屏幕上多句并存（延长显示）
    private val cityStaged = mutableListOf<Floating>()
    /** 都市回响模式：平滑后的实时响度（0..1） */
    private var cityEnergy = 0f
    private var cityEnergyNanos = 0L
    private var cityLastIdx = -1
    private var cityPosMark = 0L

    // dangomusic 对 addon 来说是 Java 视角：属性 getter 要按 getEnabledByDefault() 覆写
    override fun getEnabledByDefault(): Boolean = true

    /**
     * dangomusic 的设置页把四个自家模块写死了，没有"遍历 moduleManager.modules"的通用列表，
     * 所以 addon 注册的模块在 UI 上没有任何入口，只能靠 `config/dangomusic/config.json`
     * 里以模块名（"City Echoes"）为键的那一段来配置。
     *
     * 因此这里补一个首次运行逻辑：配置文件里还没有本模块时默认打开；
     * 一旦写过一次，就完全交给 dangomusic 的 ConfigManager 读回用户的选择（改成 false 就能关掉）。
     */
    override fun init() {
        if (!configHasThisModule()) enabled = true
    }

    /** 配置文件里是否已经有本模块的段落（有 = 用户已经保存过状态，别再强制启用）。 */
    private fun configHasThisModule(): Boolean = try {
        // dangomusic 的配置目录由 Platform 提供（config/dangomusic），配置文件固定叫 config.json
        val file: Path = DangoBridge.platform.getConfigDir().resolve("config.json")
        if (!Files.exists(file)) {
            false
        } else {
            String(Files.readAllBytes(file), Charsets.UTF_8).contains("\"${getName()}\"")
        }
    } catch (_: Throwable) {
        false
    }

    override fun onDisable() {
        cover.clear()
        current = null
        currentKey = null
        cityStaged.clear()
    }

    // ---------------------------------------------------------------- 当前歌曲 / 时钟

    private fun nowPlaying(): NowPlaying? {
        if (!DangoBridge.active) return null
        val t = DangoBridge.current ?: return null
        return NowPlaying(t, DangoBridge.isPlaying)
    }

    private fun lyricsFor(key: String): Lyrics? = cache[key] as? Lyrics

    private fun requestLyrics(s: NowPlaying) {
        val key = s.key
        if (cache[key] === NotFound) {
            val failedAt = notFoundAt[key] ?: 0L
            if (System.nanoTime() - failedAt < 30_000_000_000L) return
            cache.remove(key, NotFound)
        }
        if (cache.containsKey(key) || !pending.add(key)) return
        val track = s.track
        fetcher.execute {
            try {
                // 只用公开 API：按网易云 id 取逐字歌词（合并版用内部的 MusicSources）
                val result = DangoBridge.lyricsById(track.id)
                if (result == null) notFoundAt[key] = System.nanoTime() else notFoundAt.remove(key)
                cache[key] = result ?: NotFound
            } catch (_: Exception) {
                notFoundAt[key] = System.nanoTime()
                cache[key] = NotFound
            } finally {
                pending.remove(key)
            }
        }
    }

    /** 跟着内置播放器的进度走（它的进度是精确的） */
    private fun updateClock() {
        val now = System.nanoTime()
        val s = nowPlaying()
        if (s == null) {
            if (current != null && (now - lastSeenNanos) / 1e9 > 3.0) {
                current = null
                currentKey = null
                cityStaged.clear()
            }
            return
        }
        lastSeenNanos = now
        requestLyrics(s)
        val p = DangoBridge.positionMs.toDouble()
        if (s.key != currentKey) {
            currentKey = s.key
            songPos = p
            cityStaged.clear()
        } else {
            if (abs(p - songPos) > 300) cityStaged.clear()   // 拖了进度条
            songPos = p
        }
        current = s
    }

    // ---------------------------------------------------------------- 渲染入口

    @EventHandler
    fun render(e: WorldRenderEndEvent) {
        if (!enabled) return
        // addon 出任何意外都不该把游戏搞崩：吞掉异常，只打一次日志
        try {
            renderFrame(e)
        } catch (t: Throwable) {
            reportOnce(t)
        }
    }

    private fun renderFrame(e: WorldRenderEndEvent) {
        rainbowPhase = ((System.nanoTime() / 1_000_000_000.0 * rainbowSpeed.value) % 1.0).toFloat()
        // 歌词字体：选了就换（同一个文件不会重复加载）
        DangoBridge.gfx.text.lyricFont(if (smoothFont.get()) LyricFonts.INSTANCE.file(lyricFont.value) else null)
        updateClock()
        val s = current
        if (s != null && (showCover.get() || nowPlaying.get() || lyricColors.value == COLORS_COVER || lyricColors.value == COLORS_GRADIENT)) {
            s.track.coverUrl.takeIf { it.isNotEmpty() }?.let { cover.requestUrl(s.key, it) }
        }
        cover.upload()
        val lyrics = currentKey?.let { lyricsFor(it) }
        val paused = s != null && !s.playing
        if (s == null) cityStaged.clear() else if (lyrics != null) updateCity(s, lyrics, paused)

        val gfx = DangoBridge.gfx
        DangoBridge.guarded(gfx) {
            if (worldMode.get()) drawCityWorld(e, lyrics, paused)
        }
    }

    /** 渲染异常只报第一次，避免每帧刷屏把日志写爆 */
    private var renderErrorLogged = false

    private fun reportOnce(t: Throwable) {
        if (renderErrorLogged) return
        renderErrorLogged = true
        me.cityechoes.ui.CityLog.log.error("[CityEchoes] render failed (further errors suppressed)", t)
    }

    /** 屏幕上的东西（暗角之后画，不会被压暗）：左上角 Now playing、对拍提示 */
    @EventHandler
    fun renderHud(e: HudRenderEvent) {
        if (!enabled) return
        try {
            val s = current
            val gfx = DangoBridge.gfx
            DangoBridge.guarded(gfx) {
                if (nowPlaying.get() && s != null) drawNowPlaying(s)
            }
        } catch (t: Throwable) {
            reportOnce(t)
        }
    }

    // ---------------------------------------------------------------- 排版辅助

    /** 按像素宽度换行：英文在空格处断，中日韩可以在任意字之间断 */
    private fun wrap(text: String, maxWidth: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var lastSpace = -1
        for (c in text) {
            sb.append(c)
            if (c == ' ') lastSpace = sb.length - 1
            if (strW(sb.toString()) > maxWidth && sb.length > 1) {
                if (lastSpace > 0) {
                    out += sb.substring(0, lastSpace).trim()
                    val rest = sb.substring(lastSpace + 1)
                    sb.setLength(0)
                    sb.append(rest)
                } else {
                    out += sb.substring(0, sb.length - 1).trim()
                    sb.setLength(0)
                    sb.append(c)
                }
                lastSpace = -1
            }
        }
        if (sb.isNotBlank()) out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    /** 拆成"词"：英文按空格；中日韩每两个字一组。返回每个词（去掉空白） */
    private fun splitWords(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var cjk = 0
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
            cjk = 0
        }
        for (c in text) {
            when {
                c.isWhitespace() -> flush()
                c.code >= 0x2E80 -> {
                    if (sb.isNotEmpty() && cjk == 0) flush()
                    sb.append(c)
                    cjk++
                    if (cjk >= 2) flush()
                }
                else -> {
                    if (cjk > 0) flush()
                    sb.append(c)
                }
            }
        }
        flush()
        return out
    }

    /** 每个非空白字符的出现时间：有逐字歌词用真实时间，没有就把一句平均分给每个词 */
    private fun charTimes(line: LyricLine, nextMs: Long): LongArray {
        val times = ArrayList<Long>()
        val words = line.words
        if (words != null) {
            for (w in words) {
                val at = (w.startMs - line.timeMs).coerceAtLeast(0L)
                for (c in w.text) if (!c.isWhitespace()) times += at
            }
        } else {
            val toks = splitWords(line.text)
            val dur = (nextMs - line.timeMs).coerceAtLeast(300L)
            val span = min(dur * 0.75, toks.size * 330.0)
            toks.forEachIndexed { i, t ->
                val at = (span * i / max(1, toks.size)).toLong()
                repeat(t.length) { times += at }
            }
        }
        return times.toLongArray()
    }

    /** 每个非空白字符的出现（start）和结束（end），供演唱强调对齐 */
    private fun charTiming(line: LyricLine, nextMs: Long): Pair<LongArray, LongArray> {
        val starts = charTimes(line, nextMs)
        val ends = LongArray(starts.size)
        for (i in starts.indices) ends[i] = if (i + 1 < starts.size) starts[i + 1] else starts[i] + 220L
        return starts to ends
    }

    /** "Above ground"：歌词中心最低只到眼睛下面 [GROUND_MARGIN] 格 */
    private fun maxPitch(d: Double): Float =
        Math.toDegrees(asin((GROUND_MARGIN / d).coerceIn(0.0, 1.0))).toFloat()

    private fun spawn(line: LyricLine, nextMs: Long): Floating {
        val text = line.text
        val startMs = line.timeMs
        val (ct, ce) = charTiming(line, nextMs)
        val lines = wrap(text, 200)
        val chars = lines.sumOf { it.length }
        val lineDur = (nextMs - startMs).coerceAtLeast(400L)
        val reveal = min((chars * 55L).coerceAtLeast(250L), (lineDur * 0.6).toLong())

        val tr = if (translation.get()) line.translation?.let { wrap(it, TRANSLATION_WRAP) } ?: emptyList() else emptyList()
        val player = DangoBridge.platform.player
        if (player == null) return Floating(lines, startMs, reveal, 0.0, 0.0, 0.0, 0f, 0f, ct, endMs = nextMs).also { it.translation = tr }
        val yaw = player.yaw + (random.nextFloat() * 2f - 1f) * spread.value
        val d = distance.value.toDouble()
        var pitch = (player.pitch - 3f - random.nextFloat() * 10f).coerceIn(-60f, 30f)
        if (aboveGround.get()) pitch = min(pitch, maxPitch(d))
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val dx = -sin(yr) * cos(pr)
        val dy = -sin(pr)
        val dz = cos(yr) * cos(pr)
        val ex = player.eyeX
        val ey = player.eyeY
        val ez = player.eyeZ
        return Floating(lines, startMs, reveal, ex + dx * d, ey + dy * d, ez + dz * d, yaw, pitch, ct, endMs = nextMs, lineRef = line, charEnd = ce).also { it.translation = tr }
    }

    // ---------------------------------------------------------------- 颜色

    /** 翻译的字号是原文的多少 */
    private val TRANSLATION_SCALE = 0.72f
    /** 翻译按多宽换行（原文是 200；字小了，按画出来差不多宽算） */
    private val TRANSLATION_WRAP = 270
    /** "Above ground"：歌词中心最低在眼睛下面多少格 */
    private val GROUND_MARGIN = 0.5
    /** 天黑到这个程度（原版 sky darken，0 = 白天，11 = 半夜）就算晚上 */
    private val NIGHT_SKY_DARKEN = 4

    /** 封面的代表色（选了封面主色 / 封面渐变、并且这首歌的封面已经出来时才有） */
    private fun coverColors(): List<Int>? {
        if (lyricColors.value != COLORS_COVER && lyricColors.value != COLORS_GRADIENT) return null
        if (currentKey == null || cover.shownKey != currentKey) return null
        return cover.palette.takeIf { it.isNotEmpty() }
    }

    /** 彩虹的相位：每帧算一次，发光层和本体用同一个值，颜色才对得上 */
    private var rainbowPhase = 0f

    private val textRgb: Int get() = coverColors()?.let { it[0] and 0xFFFFFF } ?: textColor.value
    private val translationRgb: Int get() = coverColors()?.let { it[0] and 0xFFFFFF } ?: translationColor.value

    /** 一个字的颜色：[center] 是这个字的中心离行首多远，[width] 是整行宽。 */
    private fun charRgb(center: Float, width: Float, base: Int): Int {
        val u = if (width > 0f) (center / width).coerceIn(0f, 1f) else 0.5f
        if (lyricColors.value == COLORS_RAINBOW) {
            val hue = ((u * RAINBOW_SPAN + rainbowPhase) % 1f + 1f) % 1f
            return java.awt.Color.HSBtoRGB(hue, 0.55f, 1f) and 0xFFFFFF
        }
        val colors = if (lyricColors.value == COLORS_GRADIENT) coverColors() else null
        if (colors == null || colors.size < 2) return base
        val at = u * (colors.size - 1)
        val i = at.toInt().coerceAtMost(colors.size - 2)
        return mixRgb(colors[i], colors[i + 1], at - i)
    }

    private fun mixRgb(from: Int, to: Int, t: Float): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            out = out or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    // ---------------------------------------------------------------- 文字（平滑字体 / MC 字体 + 发光）

    /** 平滑字体的字号：和 MC 字体的大写字高（7 像素）差不多 */
    private val lyricSize = 10f

    private fun strW(s: String): Float =
        if (smoothFont.get()) DangoBridge.gfx.text.smoothWidth(s, lyricSize, CjkStyle.LYRIC) else DangoBridge.gfx.text.pixelWidth(s)

    private fun charW(c: Char): Float =
        if (smoothFont.get()) DangoBridge.gfx.text.smoothWidth(c.toString(), lyricSize, CjkStyle.LYRIC) else DangoBridge.gfx.text.pixelCharWidth(c)

    /** 只在晚上发光时：天黑慢慢亮起来，天亮慢慢灭掉。返回 0..1 */
    private var nightLevel = -1f
    private var nightNanos = 0L

    private fun nightGlow(): Float {
        val target = if (!glowNightOnly.get()) 1f else {
            val dark = DangoBridge.platform.skyDarken?.toInt()
            if (dark == null || dark >= NIGHT_SKY_DARKEN) 1f else 0f
        }
        val now = System.nanoTime()
        if (nightLevel < 0f) nightLevel = target
        val dt = if (nightNanos == 0L) 0f else ((now - nightNanos) / 1e9f).coerceIn(0f, 0.1f)
        nightNanos = now
        nightLevel += (target - nightLevel) * (1f - kotlin.math.exp(-dt / 0.8f))
        return nightLevel
    }

    /** 一个要画的字 */
    private class Glyph(val c: Char, val x: Float, val y: Float, val a: Float, val k: Int, val rgb: Int, val pop: Float = 1f)

    /** 由 (字序号, 第几帧, 种类) 得到一个固定的 -1..1 随机数 */
    private fun hashRand(k: Int, frame: Long, salt: Int, seed: Int): Float {
        var h = k * 73856093 xor (frame * 19349663L).toInt() xor (salt * 83492791) xor seed
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, draw: () -> Unit) = withWiggle(g, seed, frame, wiggle.get(), draw)

    private inline fun withWiggle(g: Glyph, seed: Int, frame: Long, on: Boolean, draw: () -> Unit) {
        val amt = wiggleAmount.value
        val wig = on && amt > 0.001f
        if (!wig && g.pop == 1f) {
            draw()
            return
        }
        val rot = if (wig) hashRand(g.k, frame, 1, seed) * 9f * amt else 0f
        val dx = if (wig) hashRand(g.k, frame, 2, seed) * 0.8f * amt else 0f
        val dy = if (wig) hashRand(g.k, frame, 3, seed) * 1.6f * amt else 0f
        val sc = (if (wig) 1f + hashRand(g.k, frame, 4, seed) * 0.12f * amt else 1f) * g.pop
        val cx = g.x + charW(g.c) / 2f
        val cy = g.y + 3.5f
        val pen = DangoBridge.gfx.text
        pen.push()
        pen.translate(cx + dx, cy + dy)
        pen.rotate(rot)
        pen.scale(sc, sc)
        pen.translate(-cx, -cy)
        draw()
        pen.pop()
    }

    /** 画一个字：y 是字的顶部（和 MC 字体一样） */
    private fun drawGlyph(c: Char, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoBridge.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(c.toString(), x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.LYRIC)
            }
            pen.color(r, g, b, a)
            pen.smoothText(c.toString(), x, baseline, lyricSize, CjkStyle.LYRIC)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return                          // FontRenderer 会把 alpha<4 当成不透明
            pen.pixelText(c.toString(), x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    /** 翻译：画在原文下面，字小一点、淡一点 */
    private fun drawTranslation(lines: List<String>, elapsedMs: Long, revealMs: Long, alphaMul: Float, top: Float, scale: Float) {
        if (lines.isEmpty() || !translation.get()) return
        val smooth = smoothFont.get()
        val pen = DangoBridge.gfx.text
        val lineH = 11f
        val animate = translationAnimation.get()
        val wig = translationWiggle.get()
        pen.push()
        pen.translate(0f, top)
        pen.scale(scale, scale)
        if (smooth) pen.beginSmooth()
        if (!animate && !wig) {
            var a = ((elapsedMs - 120L) / 260f).coerceIn(0f, 1f)
            a = a * a * (3f - 2f * a)
            val alpha = a * alphaMul * 0.85f
            if (alpha > 0.02f) {
                var y = 0f
                for (line in lines) {
                    if (lyricColors.value == COLORS_RAINBOW || (lyricColors.value == COLORS_GRADIENT && coverColors() != null)) {
                        val lineW = strW(line)
                        var x = -lineW / 2f
                        for (c in line) {
                            val w = charW(c)
                            if (!c.isWhitespace()) drawGlyph(c, x, y, charRgb(x + lineW / 2f + w / 2f, lineW, translationRgb), alpha, smooth, !glowPass)
                            x += w
                        }
                    } else {
                        drawLine(line, -strW(line) / 2f, y, translationRgb, alpha, smooth, !glowPass)
                    }
                    y += lineH
                }
            }
        } else {
            val total = lines.sumOf { it.length }.coerceAtLeast(1)
            val glyphs = ArrayList<Glyph>()
            var k = 0
            var y = 0f
            for (line in lines) {
                val lineW = strW(line)
                var x = -lineW / 2f
                for (c in line) {
                    val rgb = charRgb(x + lineW / 2f + charW(c) / 2f, lineW, translationRgb)
                    val a: Float
                    val lift: Float
                    if (animate) {
                        val appearAt = 120f + k.toFloat() / total * revealMs
                        var t = ((elapsedMs - appearAt) / 180f).coerceIn(0f, 1f)
                        t = t * t * (3f - 2f * t)
                        a = t
                        lift = (1f - t) * 5f
                    } else {
                        var t = ((elapsedMs - 120L) / 260f).coerceIn(0f, 1f)
                        t = t * t * (3f - 2f * t)
                        a = t
                        lift = 0f
                    }
                    val alpha = a * alphaMul * 0.85f
                    if (alpha > 0.02f && !c.isWhitespace()) glyphs += Glyph(c, x, y + lift, alpha, 1000 + k, rgb)
                    x += charW(c)
                    k++
                }
                y += lineH
            }
            val seed = lines.hashCode()
            val frame = System.currentTimeMillis() * wiggleFps.value.coerceAtLeast(1) / 1000L
            for (g in glyphs) withWiggle(g, seed, frame, wig) { drawGlyph(g.c, g.x, g.y, g.rgb, g.a, smooth, !glowPass) }
        }
        if (smooth) pen.endSmooth()
        pen.pop()
    }

    /** 整行画（翻译用，不需要逐字动画） */
    private fun drawLine(text: String, x: Float, y: Float, rgb: Int, a: Float, smooth: Boolean, shadow: Boolean) {
        val pen = DangoBridge.gfx.text
        if (smooth) {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val baseline = y + 7.6f
            if (shadow) {
                pen.color(0f, 0f, 0f, 0.45f * a)
                pen.smoothText(text, x + 0.6f, baseline + 0.6f, lyricSize, CjkStyle.LYRIC)
            }
            pen.color(r, g, b, a)
            pen.smoothText(text, x, baseline, lyricSize, CjkStyle.LYRIC)
        } else {
            val alpha = (a * 255f).toInt()
            if (alpha < 5) return
            pen.pixelText(text, x, y, (alpha shl 24) or rgb, shadow)
        }
    }

    // ===== 都市回响 (City Echoes) 模式 =====

    private fun cityAnimStyleName(): String = when (cityAnim.value) {
        CITY_ANIM_RIPPLE_SHAKE -> "ripple_shake"
        CITY_ANIM_FALL_WAVE -> "fall_wave"
        CITY_ANIM_FALL_SHAKE -> "fall_shake"
        else -> "ripple_wave"
    }

    /** 都市回响调度：维护同时存在的多句歌词（延长显示），按歌曲时间增删 */
    private fun updateCity(s: NowPlaying, lyrics: Lyrics, paused: Boolean) {
        val pos = songPos
        val idx = lyrics.indexAt(pos.toLong())
        if (idx < 0) { cityStaged.clear(); cityLastIdx = -1; cityPosMark = pos.toLong(); return }
        if (cityLastIdx >= 0 && (abs(idx - cityLastIdx) > 3 || abs(pos - cityPosMark) > 2000)) cityStaged.clear()
        if (cityStaged.none { it.cityIndex == idx }) {
            val line = lyrics.lines[idx]
            if (line.text.isNotBlank()) {
                val next = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 5000)
                val f = spawn(line, next)
                f.cityIndex = idx
                if (cityExtended.get()) f.endMs = next + cityExtendedMs.value.toLong()
                cityStaged.add(f)
            }
        }
        cityStaged.removeIf { pos > it.endMs }
        while (cityStaged.size > 4) cityStaged.removeAt(0)
        cityLastIdx = idx
        cityPosMark = pos.toLong()
    }

    /** 都市回响模式的总入口：发光层 + 本体各画一遍 */
    private fun drawCityWorld(e: WorldRenderEndEvent, lyrics: Lyrics?, paused: Boolean) {
        val gfx = DangoBridge.gfx
        val deepGlow = gfx.glow
        val glowAmount = glowStrength.value * nightGlow()
        if (glow.get() && glowAmount > 0.01f && deepGlow.available && cityStaged.isNotEmpty()) {
            try {
                if (deepGlow.begin()) {
                    glowPass = true
                    try { drawCityScene(e, lyrics, paused) } finally { glowPass = false }
                    deepGlow.end(1f, 1f, 1f, glowAmount * 4f, glowRadius.value)
                }
            } catch (t: Throwable) {
                glowPass = false
                deepGlow.disable(t)
            }
            gfx.world.resync()
        }
        drawCityScene(e, lyrics, paused)
    }

    /** 都市回响：把多句歌词摆到世界里，并平滑实时响度 */
    private fun drawCityScene(e: WorldRenderEndEvent, lyrics: Lyrics?, paused: Boolean) {
        if (cityStaged.isEmpty()) return
        val world = DangoBridge.gfx.world
        world.begin()
        val now = System.nanoTime()
        val dt = if (cityEnergyNanos == 0L) 0.016 else ((now - cityEnergyNanos) / 1e9).coerceIn(0.0, 0.1)
        cityEnergyNanos = now
        // 公开 API 拿到"节拍脉冲"当实时响度（合并版用的是私有 rmsEnergy 字段，这里不依赖内部实现）
        val target = if (paused) 0f else DangoBridge.beatPulse(DangoBridge.positionMs)
        cityEnergy = (cityEnergy + (target - cityEnergy) * (1 - kotlin.math.exp(-dt / 0.12))).toFloat()
        val pos = songPos
        val energy = cityEnergy
        for (f in cityStaged) {
        var rx = f.x - world.cameraX
        var ry = f.y - world.cameraY
        var rz = f.z - world.cameraZ
        var len = sqrt((rx * rx + ry * ry + rz * rz).toDouble()).toFloat()
        val minD = distance.value * 0.6f
        if (keepAway.get() && len in 1e-4f..minD) {
            val k = minD / len
            rx *= k; ry *= k; rz *= k
            f.x = world.cameraX + rx; f.y = world.cameraY + ry; f.z = world.cameraZ + rz
            len = minD
        }
            val yaw: Float
            val pitch: Float
            if (facePlayer.get() && len > 1e-4) {
                yaw = Math.toDegrees(atan2(-rx, rz)).toFloat()
                pitch = Math.toDegrees(atan2(-ry, sqrt(rx * rx + rz * rz))).toFloat()
            } else { yaw = f.yaw; pitch = f.pitch }
            val sc = 0.03f * scale.value * (distance.value / 6.5f)
            world.push(rx, ry, rz, yaw, pitch, sc)
            val roll = if (cityTilt.get()) CityEchoes.hashNoise(f.lines.hashCode(), 0, 9, 0) * 6f else 0f
            val pen = DangoBridge.gfx.text
            pen.push()
            pen.rotate(roll)
            drawCityBody(f, energy, (pos - f.startMs).toLong(), 1f)
            drawTranslation(f.translation, (pos - f.startMs).toLong(), f.revealMs, 1f, f.lines.size * 11f / 2f + 2.5f, TRANSLATION_SCALE)
            pen.pop()
            world.pop()
        }
    }

    /** 都市回响：逐字算位移 / 透明度 / 缩放 / 旋转 / 替换符，然后画出来 */
    private fun drawCityBody(f: Floating, energy: Float, elapsedMs: Long, alphaMul: Float) {
        val smooth = smoothFont.get()
        val animStyle = cityAnimStyleName()
        val prefs = CityEchoes.CityPrefs(
            cityJump.value, animStyle, cityScrambleExit.get(), citySingingEmphasis.get(),
            cityFallDistance.value, cityShakeFreq.value, 100f, 100f, cityMotionStrength.value,
        )
        val seed = f.lines.hashCode()
        val pixels = CITY_PIXELS
        val endRel = (f.endMs - f.startMs).coerceAtLeast(1L)
        val exitStartRel = if (prefs.scrambleExit) max(0L, endRel - 500L) else max(0L, endRel - 200L)
        val item = CityEchoes.CityItem(0L, 140L, exitStartRel, endRel)
        val inputs = ArrayList<CityEchoes.CityInput>()
        val rgbs = ArrayList<Int>()
        val lineH = 11f
        var y = -(f.lines.size * lineH) / 2f
        var n = 0
        for (line in f.lines) {
            val lineW = strW(line)
            var x = -lineW / 2f
            for (c in line) {
                val isSpace = c.isWhitespace()
                val rgb = charRgb(x + lineW / 2f + charW(c) / 2f, lineW, textRgb)
                inputs.add(CityEchoes.CityInput(c, x, y, isSpace, if (isSpace) -1 else n))
                rgbs.add(rgb)
                if (!isSpace) n++
                x += charW(c)
            }
            y += lineH
        }
        val states = CityEchoes.glyphStates(inputs, prefs, item, elapsedMs, energy, pixels, seed, f.charTimes, f.charEnd, angle = 0f, moving = true)
        val pen = DangoBridge.gfx.text
        if (smooth) pen.beginSmooth()
        for (i in inputs.indices) {
            val cg = states[i]
            if (cg.opacity <= 0.02f) continue
            val input = inputs[i]
            val ch = if (cg.replacement >= 0) CityEchoes.SYMBOLS[cg.replacement] else input.char
            val a = cg.opacity.coerceIn(0f, 1f) * alphaMul
            if (a <= 0.02f) continue
            drawCityGlyph(ch, cg.x, cg.y, rgbs[i], a, cg.scale, cg.rotation, smooth)
        }
        if (smooth) pen.endSmooth()
    }

    /**
     * 都市回响招牌：白芯 + 彩色描边。
     *  - 发光层（glowPass）用彩色画，模糊后形成彩色光晕（亮处经色调映射趋白、边缘保留颜色）。
     *  - 本体先画一圈"放大 CITY_CORE_SCALE 倍的彩色副本"当描边，再叠纯白核心覆盖中心。
     */
    private fun drawCityGlyph(c: Char, x: Float, y: Float, rgb: Int, a: Float, scale: Float, rot: Float, smooth: Boolean) {
        if (glowPass) {
            drawCityCore(c, x, y, rgb, a, scale, rot, smooth)
        } else {
            drawCityCore(c, x, y, rgb, a, scale * CITY_CORE_SCALE, rot, smooth)
            drawCityCore(c, x, y, 0xFFFFFF, a, scale, rot, smooth)
        }
    }

    /** 绕字心 (cx,cy) 做缩放 / 旋转后画一个字；y 是字顶部 */
    private fun drawCityCore(c: Char, x: Float, y: Float, rgb: Int, a: Float, scale: Float, rot: Float, smooth: Boolean) {
        val cw = charW(c)
        val cx = x + cw / 2f
        val cy = y + 3.5f
        val pen = DangoBridge.gfx.text
        pen.push()
        pen.translate(cx, cy)
        pen.rotate(rot)
        pen.scale(scale, scale)
        pen.translate(-cx, -cy)
        drawGlyph(c, x, y, rgb, a, smooth, false)
        pen.pop()
    }

    // ===== 左上角 Now playing =====

    /** 左上角小柱子当前颜色（向封面主色渐变） */
    private var barR = 0xF2 / 255f
    private var barG = 0xA7 / 255f
    private var barB = 0xC3 / 255f
    private var barColorNanos = 0L

    private fun drawNowPlaying(s: NowPlaying) {
        val hud = DangoBridge.gfx.hud
        hud.begin()
        val x = 6
        val y = 6
        val coverSize = 32

        var textX = x
        if (showCover.get()) {
            val loc = cover.location
            val ready = loc != null && cover.shownKey == s.key
            hud.rect(x - 1, y - 1, x + coverSize + 1, y + coverSize + 1, 0x66000000)
            if (ready) {
                val fade = ((System.nanoTime() - cover.shownAtNanos) / 1e9f / 0.35f).coerceIn(0f, 1f)
                hud.texture(loc!!, x, y, coverSize, coverSize, fade)
            } else {
                hud.rect(x, y, x + coverSize, y + coverSize, 0x55F2A7C3)
            }
            textX = x + coverSize + 6
        }

        // 三根跳动的小柱子：颜色跟随封面主色，换歌时慢慢过渡
        val target = if (cover.shownKey == s.key) cover.accent else CoverArt.DEFAULT_ACCENT
        val nowNs = System.nanoTime()
        val dt = if (barColorNanos == 0L) 1f else ((nowNs - barColorNanos) / 1e9f).coerceIn(0f, 0.1f)
        barColorNanos = nowNs
        val k = 1f - kotlin.math.exp(-dt / 0.25f)
        barR += (((target shr 16) and 0xFF) / 255f - barR) * k
        barG += (((target shr 8) and 0xFF) / 255f - barG) * k
        barB += ((target and 0xFF) / 255f - barB) * k
        val barColor = (0xFF shl 24) or ((barR * 255).toInt().coerceIn(0, 255) shl 16) or
            ((barG * 255).toInt().coerceIn(0, 255) shl 8) or (barB * 255).toInt().coerceIn(0, 255)
        val t = nowNs / 1e9
        for (i in 0 until 3) {
            val h = if (s.playing) (3 + 4 * (0.5 + 0.5 * sin(t * (6.0 + i * 1.7) + i))).toInt() else 2
            val bx = textX + i * 3
            hud.rect(bx, y + 8 - h, bx + 2, y + 8, barColor)
        }

        val title = s.title.let { if (it.length > 40) it.take(39) + "…" else it }
        hud.text(title, (textX + 12).toFloat(), y.toFloat(), 0xFFFFFFFF.toInt(), true)
        if (s.artist.isNotEmpty()) {
            hud.text(s.artist, textX.toFloat(), (y + 11).toFloat(), 0xFFB8B8C8.toInt(), true)
        }

        hud.end()
    }

}
