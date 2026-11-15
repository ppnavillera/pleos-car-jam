package app.pjam.studio.engine

/**
 * 스텝 지연 통계 (T0-13, T2-9). 스텝 예정 시각과 엔진이 그 스텝의 play()를 부르기 직전 시각의 차(lag, ns)를 모은다.
 * 스텝마다 로그를 찍으면 그 로그가 오차를 만들므로 숫자만 링 버퍼에 넣고 요약은 따로 부른다. 엔진 스레드만 add 한다.
 * 이 값은 "엔진이 늦게 깨어난 정도"까지만 잰다. SoundPool 내부 버퍼와 출력 지연은 포함하지 않는다.
 */
class StepLagStats(private val capacity: Int = 8192) {
    private val buf = LongArray(capacity)
    @Volatile private var count = 0L
    @Volatile private var maxSeen = 0L
    @Volatile private var missed = 0L

    fun add(lagNanos: Long) {
        buf[(count % capacity).toInt()] = lagNanos
        count++
        if (lagNanos > maxSeen) maxSeen = lagNanos
    }

    /** 스텝이 통째로 건너뛰어진 횟수 (연속 스텝 번호가 끊김). */
    fun noteMissed(n: Long) { missed += n }

    private var lastStep = -1L

    /**
     * 엔진이 스텝 n을 울리기 직전에 부른다. 번호가 직전보다 1 커야 정상이다. 2 이상 커지면 그 사이 스텝이 울리지 않은 것이라 누락으로 센다.
     * 번호가 같거나 작아지면 재생이 다시 시작된 것(0부터)이라 새 구간으로 본다.
     */
    fun onStep(n: Long, lagNanos: Long) {
        if (lastStep >= 0 && n > lastStep + 1) noteMissed(n - lastStep - 1)
        lastStep = n
        add(lagNanos)
    }

    fun reset() { count = 0; maxSeen = 0; missed = 0; lastStep = -1 }

    data class Summary(val n: Long, val p50Ms: Double, val p95Ms: Double, val p99Ms: Double, val maxMs: Double, val missedSteps: Long) {
        override fun toString() = "n=$n p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms missed=$missedSteps".format(p50Ms, p95Ms, p99Ms, maxMs)
    }

    fun summary(): Summary {
        val total = count
        val n = minOf(total, capacity.toLong()).toInt()
        if (n == 0) return Summary(0, 0.0, 0.0, 0.0, 0.0, missed)
        val a = buf.copyOf(n).also { it.sort() }
        fun pct(p: Double) = a[minOf(n - 1, (p * n).toInt())] / 1e6
        return Summary(total, pct(0.50), pct(0.95), pct(0.99), maxSeen / 1e6, missed)
    }
}
