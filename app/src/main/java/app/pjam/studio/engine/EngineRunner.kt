package app.pjam.studio.engine

import java.util.concurrent.locks.LockSupport

/** LoopEngine을 도는 전용 스레드. 스텝 시각 직전까지 park하고 마지막 ~0.3ms는 yield로 맞춘다. */
class EngineRunner(
    private val engine: LoopEngine,
    private val clock: Clock,
    private val onThreadStart: () -> Unit = {},
) {
    @Volatile private var alive = true
    private val thread = Thread({ onThreadStart(); loop() }, "pjam-engine")

    fun start() {
        engine.onWake = { LockSupport.unpark(thread) }
        thread.start()
    }

    fun shutdown() {
        alive = false
        LockSupport.unpark(thread)
    }

    private fun loop() {
        while (alive) {
            engine.tick(clock.nanos())
            val next = engine.nextWakeNanos()
            if (next == Long.MAX_VALUE) {
                LockSupport.parkNanos(MAX_IDLE_NANOS)
            } else {
                val wait = next - clock.nanos()
                if (wait > SPIN_NANOS) LockSupport.parkNanos(wait - SPIN_NANOS) else if (wait > 0) Thread.yield()
            }
        }
    }

    private companion object {
        const val SPIN_NANOS = 300_000L
        const val MAX_IDLE_NANOS = 100_000_000L
    }
}
