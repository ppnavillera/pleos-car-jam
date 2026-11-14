package app.pjam.studio.engine

/**
 * 박자 시계 코어. 스레드·sleep 없이 `advanceTo(now)`만 가진 순수 로직이다.
 * 스텝 시각은 누적 덧셈이 아니라 `anchorTime + (n − anchorStep) × stepNanos`로 계산하므로 드리프트가 없다.
 * 스텝 번호는 시작 후 계속 증가하는 절대 번호(Long)이고, 루프 위치(0..31)는 LoopEngine이 계산한다.
 * 한 스레드(엔진 스레드)에서만 호출한다.
 */
class Transport {
    var running: Boolean = false
        private set
    var stepNanos: Long = stepNanosForBpm(DEFAULT_BPM)
        private set

    /** 아직 발사하지 않은 다음 스텝의 절대 번호. */
    var nextStep: Long = 0
        private set

    private var anchorTime = 0L
    private var anchorStep = 0L

    fun start(now: Long, stepNanos: Long, firstStep: Long = 0L) {
        this.stepNanos = stepNanos
        anchorTime = now
        anchorStep = firstStep
        nextStep = firstStep
        running = true
    }

    fun stop() {
        running = false
    }

    fun timeOfStep(n: Long): Long = anchorTime + (n - anchorStep) * stepNanos

    /** 다음 스텝이 울릴 시각. 멈춰 있으면 Long.MAX_VALUE. */
    fun nextStepTime(): Long = if (running) timeOfStep(nextStep) else Long.MAX_VALUE

    /**
     * 템포 변경. 이미 예약된 다음 스텝의 시각은 그대로 두고, 그 이후 스텝부터 새 길이를 쓴다.
     * 스텝 번호는 이어지고 소리가 한꺼번에 몰리지 않는다.
     */
    fun setStepNanos(newStepNanos: Long) {
        if (running) {
            anchorTime = timeOfStep(nextStep)
            anchorStep = nextStep
        }
        stepNanos = newStepNanos
    }

    /** now까지 도래한 모든 스텝을 순서대로 onStep(절대 스텝 번호, 예정 시각)으로 보낸다. */
    fun advanceTo(now: Long, onStep: (step: Long, scheduledNanos: Long) -> Unit) {
        while (running && timeOfStep(nextStep) <= now) {
            val n = nextStep
            val t = timeOfStep(n)
            nextStep = n + 1
            onStep(n, t)
        }
    }
}
