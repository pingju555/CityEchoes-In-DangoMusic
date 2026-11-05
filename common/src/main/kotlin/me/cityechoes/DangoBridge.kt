package me.cityechoes

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.gfx.Gfx
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.platform.Platform
import java.lang.reflect.Proxy

/**
 * 对 dangomusic 公开 API 的极薄门面。
 *
 * 关键事实：dangomusic 打包时把 kotlin 标准库整体 shade 成
 * `me.sorihiko.dango.shadow.kotlin`（连 `kotlin.Metadata` 也一起搬走了），
 * 于是它所有 Kotlin 类从 addon 的视角看都变成"普通 Java 类"：
 * 单例要走 `INSTANCE` 静态字段，属性要走 `getXxx()`。
 *
 * 这个对象把上述 Java 风格访问统一收口，调用方代码保持干净，也方便主 mod 改名时只改一处。
 *
 * 唯一需要运行时技巧的是 [Gfx.guarded]：它的参数类型是 shaded 的
 * `Function0<shaded Unit>`，普通 Kotlin lambda 类型对不上，所以这里用动态代理
 * 造一个干净的 Function0 传进去（这就是 addon 里唯一一处"绕一下"的地方）。
 */
object DangoBridge {

    val gfx: Gfx
        get() = DangoClient.INSTANCE.getGfx()

    val platform: Platform
        get() = DangoClient.INSTANCE.getPlatform()

    val positionMs: Long
        get() = MusicPlayer.INSTANCE.getPositionMs()

    fun beatPulse(pos: Long): Float = MusicPlayer.INSTANCE.beatPulse(pos)

    val active: Boolean
        get() = MusicPlayer.INSTANCE.getActive()

    val current: NeteaseTrack?
        get() = MusicPlayer.INSTANCE.getCurrent()

    val isPlaying: Boolean
        get() = MusicPlayer.INSTANCE.getState() == MusicPlayer.State.PLAYING

    /** 按网易云 id 取逐字歌词（公开 API）。 */
    fun lyricsById(id: Long): Lyrics? = LyricsProvider.INSTANCE.neteaseLyricById(id)

    /**
     * 等价于 dangomusic 的 `gfx.guarded { block() }`：保存 GL 状态 → 执行 → 还原。
     * 通过动态代理满足 shaded 的 `Function0<Unit>`，避免 Kotlin lambda 类型不匹配。
     *
     * 代理建不出来时（例如主 mod 换了 shade 前缀）退化为直接执行 block：
     * 最坏情况是渲染状态少一次 save/restore，但绝不让游戏崩。
     */
    fun guarded(g: Gfx, block: () -> Unit) {
        val lambda = try {
            newShadedFunction0(block)
        } catch (t: Throwable) {
            block()
            return
        }
        guardedMethod.invoke(g, lambda)
    }

    private fun newShadedFunction0(block: () -> Unit): Any = Proxy.newProxyInstance(
        function0Type.classLoader ?: DangoBridge::class.java.classLoader,
        arrayOf(function0Type),
    ) { _, method, _ ->
        when (method.name) {
            "invoke" -> {
                block()
                unitInstance
            }
            "toString" -> "CityEchoes.guarded"
            "hashCode" -> System.identityHashCode(block)
            "equals" -> false
            else -> null
        }
    }

    /** Gfx.guarded 的运行时签名（参数是被 shade 过的 Function0）。 */
    private val guardedMethod by lazy {
        Gfx::class.java.methods.first { it.name == "guarded" && it.parameterCount == 1 }
    }

    private val function0Type by lazy { guardedMethod.parameterTypes[0] }

    /** shaded 版 kotlin.Unit 的单例；取不到就用 null（guarded 不关心返回值）。 */
    private val unitInstance: Any? by lazy {
        try {
            Class.forName(
                "me.sorihiko.dango.shadow.kotlin.Unit",
                true,
                Gfx::class.java.classLoader,
            ).getField("INSTANCE").get(null)
        } catch (_: Throwable) {
            null
        }
    }
}
