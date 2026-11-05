package me.cityechoes

import kotlin.math.*

/**
 * 都市回响（City Echoes）逐字演出算法的纯逻辑移植——和渲染后端完全无关。
 * 原 Python 实现见 glyph_motion.py / audio_energy.py / scramble.py。
 *
 * 这里只负责一件事：给定每个字的排版位置、当前歌曲时间、实时响度，
 * 算出每个字的 (位移 / 透明度 / 缩放 / 旋转 / 替换符)，真正的"画字"交给 CityEchoesModule。
 * 这样渲染层（逐字 billboard）可以原样复用，只有动画数学被搬过来。
 */
object CityEchoes {
    /** 乱码退场时从这组符号里挑（对应原版的 SYMBOLS = "#%?+*=<>") */
    const val SYMBOLS = "#%?+*=<>"

    fun smoothstep(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    /**
     * 确定性整数哈希噪声 → -1..1，对应 noise_value()：同一组参数永远得到同一个数，
     * 这样发光层画一遍、本体画一遍，抖动/乱码结果完全一致。
     */
    fun hashNoise(seed: Int, rank: Int, axis: Int, sample: Int): Float {
        var value = (seed.toLong() and 0xFFFFFFFFL) * 0x9E3779B1L +
                (rank.toLong() and 0xFFFFFFFFL) * 0x85EBCA77L +
                (axis.toLong() and 0xFFFFFFFFL) * 0xC2B2AE3DL +
                (sample.toLong() and 0xFFFFFFFFL)
        value = value and 0xFFFFFFFFL
        value = ((value xor (value ushr 16)) * 0x7FEB352DL) and 0xFFFFFFFFL
        value = ((value xor (value ushr 15)) * 0x846CA68BL) and 0xFFFFFFFFL
        value = value xor (value ushr 16)
        return (value.toDouble() / 0xFFFFFFFFL).toFloat() * 2f - 1f
    }

    /** 随时间插值的连续噪声 → -1..1，对应 noise()：值噪声（posterizeTime 后再线性插值） */
    fun valueNoise(seed: Int, rank: Int, axis: Int, seconds: Float, frequency: Float): Float {
        val clock = seconds * frequency
        val sample = floor(clock)
        val weight = smoothstep((clock - sample))
        val first = hashNoise(seed, rank, axis, sample.toInt())
        val next = hashNoise(seed, rank, axis, sample.toInt() + 1)
        return first + (next - first) * weight
    }

    /** 错相位的分段进度 0..1（进场 / 退场），对应 stagger() */
    fun stagger(positionMs: Long, startMs: Long, endMs: Long, rank: Int, count: Int, durationMs: Long): Float {
        val total = max(0.0, (endMs - startMs).toDouble())
        if (total == 0.0) return if (positionMs >= endMs) 1f else 0f
        val duration = min(durationMs.toDouble(), total)
        val delay = if (count > 1) (total - duration) * rank / (count - 1) else 0.0
        return ((positionMs - startMs - delay) / duration).coerceIn(0.0, 1.0).toFloat()
    }

    /** 单个字"正在被唱到"的强调权重 0..1：在 [start,end) 内、靠近两端时淡入淡出，对应 word_emphasis() */
    fun charEmphasis(charIndex: Int, starts: LongArray, ends: LongArray, elapsedMs: Long): Float {
        if (charIndex < 0 || charIndex >= starts.size) return 0f
        val s = starts[charIndex]
        val e = ends[charIndex]
        if (elapsedMs < s || elapsedMs >= e) return 0f
        val ramp = min(80.0, ((e - s) / 4.0))
        if (ramp <= 0.0) return 1f
        val a = min((elapsedMs - s).toDouble() / ramp, (e - elapsedMs).toDouble() / ramp)
        return smoothstep(a.toFloat())
    }

    /** 乱码退场：到退场窗口后，每个字按确定性噪声换成符号之一并淡出，对应 scramble_state() */
    fun scramble(
        state: CityGlyph, rank: Int, count: Int,
        exitStartMs: Long, endMs: Long, elapsedMs: Long,
        prefs: CityPrefs, seed: Int, pixels: Float,
    ): CityGlyph {
        if (!prefs.scrambleExit || elapsedMs < exitStartMs) return state
        val total = max(0.0, (endMs - exitStartMs).toDouble())
        val duration = if (count == 1) total else min(160.0 * 100.0 / prefs.exitSpeed, 2.0 * total / max(1, count))
        val phase = stagger(elapsedMs, exitStartMs, endMs, rank, count, duration.toLong())
        if (phase <= 0) return state
        if (phase >= 1) return CityGlyph(state.x, state.y, 0f, state.scale, state.rotation, -1, 0f)
        val sample = floor((elapsedMs - exitStartMs) * 12.0 / 1000.0).toInt()
        val symbol = min(7, floor(((hashNoise(seed, rank, 7, sample) + 1) * 4)).toInt())
        val opacity = state.opacity * (1 - smoothstep(((phase - 0.4) / 0.6).toFloat()))
        return CityGlyph(
            state.x + hashNoise(seed, rank, 8, sample) * pixels / 32f,
            state.y, opacity, state.scale, state.rotation, symbol, 0f,
        )
    }

    /** 主函数：算出这一帧每个字的状态，对应 glyph_states() */
    fun glyphStates(
        inputs: List<CityInput>,
        prefs: CityPrefs,
        item: CityItem,
        elapsedMs: Long,
        energy: Float,
        pixels: Float,
        seed: Int,
        charStart: LongArray,
        charEnd: LongArray,
        angle: Float = 0f,
        @Suppress("UNUSED_PARAMETER") moving: Boolean = true,
    ): List<CityGlyph> {
        val visible = inputs.filter { !it.isSpace }
        val count = visible.size
        var rank = 0
        val seconds = elapsedMs / 1000f
        val amp = prefs.jump * (0.35f + 0.65f * energy.coerceIn(0f, 1f))
        val falling = prefs.animStyle.startsWith("fall") && !prefs.scrambleExit
        val shaking = prefs.animStyle.endsWith("shake")
        val radians = Math.toRadians(angle.toDouble())
        val sinR = sin(radians).toFloat()
        val cosR = cos(radians).toFloat()
        val out = ArrayList<CityGlyph>(inputs.size)
        for (g in inputs) {
            if (g.isSpace) {
                out.add(CityGlyph(g.x, g.baseline, 0f, 1f, 0f, -1, 0f))
                continue
            }
            val r = rank
            rank++
            val emphasis = if (prefs.singingSync) charEmphasis(g.index, charStart, charEnd, elapsedMs) else 0f
            val entering = stagger(elapsedMs, item.startMs, item.entryEndMs, r, count, (140f * 100f / prefs.entrySpeed).toLong())
            val leaving = stagger(elapsedMs, item.exitStartMs, item.endMs, r, count, ((if (falling) 350 else 200) * 100f / prefs.exitSpeed).toLong())
            val opacity = smoothstep(entering) * (if (prefs.scrambleExit) 1f else 1 - smoothstep(leaving))
            val (dx, dy) = if (shaking) {
                val ax = amp * 0.3f * valueNoise(seed, r, 0, seconds, prefs.shakeFrequency)
                val ay = amp * 0.5f * valueNoise(seed, r, 1, seconds, prefs.shakeFrequency)
                ax to ay
            } else {
                0f to (amp * 0.4f * Math.sin(seconds * 2.0 * Math.PI * 1.5 - r * 0.6)).toFloat()
            }
            val distance = if (falling) prefs.fallDistance * pixels / 32f * leaving * leaving else 0f
            val rotation = if (falling) ((if (hashNoise(seed, r, 2, 0) >= 0) 8f else -8f) * leaving * leaving) else 0f
            val x = g.x + dx + sinR * distance
            val y = g.baseline + dy + cosR * distance
            var state = CityGlyph(x, y, opacity, if (opacity > 0f) 1 + 0.08f * emphasis else 1f, rotation, -1, if (opacity > 0f) emphasis else 0f)
            state = scramble(state, r, count, item.exitStartMs, item.endMs, elapsedMs, prefs, seed, pixels)
            out.add(state)
        }
        val strength = prefs.motionStrength / 100f
        if (strength == 1f) return out
        return out.mapIndexed { i, st ->
            val g = inputs[i]
            CityGlyph(
                g.x + (st.x - g.x) * strength,
                g.baseline + (st.y - g.baseline) * strength,
                st.opacity,
                1 + (st.scale - 1) * strength,
                st.rotation * strength,
                st.replacement,
                st.emphasis,
            )
        }
    }

    /** 一个要排版的字的输入：字符、基准左上角 (x,y)、是否空白、非空白序号 */
    data class CityInput(val char: Char, val x: Float, val baseline: Float, val isSpace: Boolean, val index: Int)

    /** 一个字这一帧算出来的状态 */
    data class CityGlyph(
        val x: Float, val y: Float, val opacity: Float,
        val scale: Float, val rotation: Float, val replacement: Int, val emphasis: Float,
    )

    /** 一句歌词的时间窗（都相对这一句的开始，毫秒）：进场结束 / 退场开始 / 整句结束 */
    data class CityItem(val startMs: Long, val entryEndMs: Long, val exitStartMs: Long, val endMs: Long)

    /** 都市回响的可调参数（对应原版 prefs） */
    data class CityPrefs(
        val jump: Float,
        val animStyle: String,
        val scrambleExit: Boolean,
        val singingSync: Boolean,
        val fallDistance: Float,
        val shakeFrequency: Float,
        val entrySpeed: Float,
        val exitSpeed: Float,
        val motionStrength: Float,
    )
}
