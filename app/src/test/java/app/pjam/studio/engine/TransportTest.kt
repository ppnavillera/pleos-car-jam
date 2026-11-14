package app.pjam.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {
    @Test fun thousandStepsHaveZeroDrift() {
        val step = stepNanosForBpm(137) // 나누어떨어지지 않는 값
        val t = Transport()
        val start = 5_000_000_000L
        t.start(start, step)
        var now = start
        var expected = 0L
        // 폴링을 불규칙하게(0~13ms 늦게) 해도 예정 시각은 start + n*step 이어야 한다
        var jitter = 3_000_000L
        while (expected < 1000) {
            now += step / 3
            jitter = (jitter * 7 + 1_234_567) % 13_000_000
            t.advanceTo(now + jitter) { n, scheduled ->
                assertEquals(expected, n)
                assertEquals(start + n * step, scheduled)
                expected++
            }
        }
        assertEquals(1000L, expected)
    }

    @Test fun tickFiresAllDueStepsInOrder() {
        val t = Transport()
        t.start(0, 100)
        val got = ArrayList<Long>()
        t.advanceTo(450) { n, _ -> got += n }
        assertEquals(listOf(0L, 1, 2, 3, 4), got)
        assertEquals(500L, t.nextStepTime())
    }

    @Test fun tempoChangeKeepsStepNumbersContinuous() {
        val t = Transport()
        t.start(0, 100)
        val got = ArrayList<Pair<Long, Long>>()
        t.advanceTo(250) { n, s -> got += n to s } // 0,1,2 발사 (0,100,200). 다음 = 3 @300
        t.setStepNanos(50)
        t.advanceTo(1000) { n, s -> got += n to s }
        assertEquals((0L until got.size.toLong()).toList(), got.map { it.first })
        assertEquals(300L, got[3].second) // 예약된 다음 스텝 시각은 유지
        assertEquals(350L, got[4].second) // 그 뒤부터 새 길이
        assertEquals(300L + 5 * 50, got[8].second)
        assertTrue(got.last().second <= 1000)
    }

    @Test fun stopHaltsAndNextStepTimeIsMax() {
        val t = Transport()
        t.start(0, 100)
        t.advanceTo(100) { n, _ -> if (n == 1L) t.stop() }
        assertEquals(Long.MAX_VALUE, t.nextStepTime())
        var fired = 0
        t.advanceTo(10_000) { _, _ -> fired++ }
        assertEquals(0, fired)
    }
}
