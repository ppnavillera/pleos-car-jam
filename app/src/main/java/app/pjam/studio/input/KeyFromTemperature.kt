package app.pjam.studio.input

import kotlin.math.roundToInt

/**
 * 설정 온도 → 조(키) (기획서 4.5, T3-4). 앱이 처음 읽은 온도를 기준 0으로 두고, 증분 단위(0.5°C)마다 반음 ±1, 범위 −3~+3.
 * 증분 단위는 capability가 알려 주는 값이다(실측 15.0..30.0, 0.5 단위). 순수 Kotlin.
 */
class KeyFromTemperature(private val stepCelsius: Float = 0.5f, private val maxSemitones: Int = 3) {
    private var baseline: Float? = null

    /** 온도 값을 넣으면 기준 대비 반음 수(−max..+max)를 돌려준다. 첫 값은 기준이 되어 0. */
    fun semitones(celsius: Float): Int {
        val base = baseline ?: celsius.also { baseline = it }
        return ((celsius - base) / stepCelsius).roundToInt().coerceIn(-maxSemitones, maxSemitones)
    }
}
