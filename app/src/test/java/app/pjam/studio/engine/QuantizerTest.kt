package app.pjam.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantizerTest {
    private val step = stepNanosForBpm(100) // 150ms
    private val start = 1_000_000_000L

    private fun q(offsetSteps: Double, bpmStep: Long = step, latency: Long = 0L) =
        Quantizer.quantize(start + (offsetSteps * bpmStep).toLong(), start, bpmStep, latency)

    @Test fun stepLengthAt100And140Bpm() {
        assertEquals(150_000_000L, stepNanosForBpm(100))
        assertEquals(107_142_857L, stepNanosForBpm(140))
        assertEquals(214_285_714L, stepNanosForBpm(70))
    }

    @Test fun exactBeats() {
        for (s in 0 until 32) assertEquals(s, q(s.toDouble()))
    }

    @Test fun roundsToNearest() {
        assertEquals(5, q(5.4))
        assertEquals(6, q(5.6))
        assertEquals(6, q(5.5)) // .5는 올림
    }

    @Test fun lateInLoopWrapsToZero() {
        assertEquals(0, q(31.6))
        assertEquals(31, q(31.4))
        assertEquals(0, q(32.0))
    }

    @Test fun halfStepBeforeStartIsZero() {
        assertEquals(0, q(-0.4))
        assertEquals(0, q(-0.5)) // -0.5 → 반올림 올림 → 0
        assertEquals(31, q(-0.6)) // 이보다 이르면 앞 루프의 끝
    }

    @Test fun negativeTimesWrapCorrectly() {
        assertEquals(30, q(-2.0))
        assertEquals(16, q(-16.0))
    }

    @Test fun latencyCompensationShiftsEarlier() {
        // 입력이 2스텝 지연되어 도착했다고 보정
        assertEquals(3, q(5.0, latency = 2 * step))
    }

    @Test fun otherTempos() {
        for (bpm in intArrayOf(70, 100, 140)) {
            val s = stepNanosForBpm(bpm)
            for (n in intArrayOf(0, 1, 15, 16, 31)) {
                assertEquals(n, Quantizer.quantize(start + n * s + s / 10, start, s))
            }
            assertEquals(0, Quantizer.quantize(start + (31.6 * s).toLong(), start, s))
        }
    }
}
