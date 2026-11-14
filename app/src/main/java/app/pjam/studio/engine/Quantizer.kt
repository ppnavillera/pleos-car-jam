package app.pjam.studio.engine

object Quantizer {
    /**
     * 입력 시각을 가장 가까운 스텝(0 until steps)으로 맞춘다.
     * step = round((입력시각 − 지연보정 − 루프시작시각) / 스텝길이) mod steps
     * - 반올림은 .5에서 올림. 루프 끝 직전 입력(31.6 → 32)은 0으로 감긴다.
     * - 루프 시작 직전 입력(음수 시각, 반 스텝 이내)은 0이 된다.
     */
    fun quantize(
        inputNanos: Long,
        loopStartNanos: Long,
        stepNanos: Long,
        latencyNanos: Long = 0L,
        steps: Int = STEPS_PER_LOOP,
    ): Int = Math.floorMod(roundedSteps(inputNanos, loopStartNanos, stepNanos, latencyNanos), steps.toLong()).toInt()

    /** 기준 시각으로부터 몇 스텝째인지(감기지 않은 정수, 음수 가능). 녹음이 여러 바퀴 이어질 때 쓴다. */
    fun roundedSteps(inputNanos: Long, referenceNanos: Long, stepNanos: Long, latencyNanos: Long = 0L): Long {
        require(stepNanos > 0) { "stepNanos must be > 0" }
        val delta = inputNanos - latencyNanos - referenceNanos
        return Math.floorDiv(2 * delta + stepNanos, 2 * stepNanos)
    }
}
