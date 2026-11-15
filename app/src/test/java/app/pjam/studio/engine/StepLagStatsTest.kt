package app.pjam.studio.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** T2-9 "누락 스텝 0" 지표가 실제로 센다는 것을 확인한다(전에는 noteMissed를 아무도 부르지 않아 항상 0이었다). */
class StepLagStatsTest {
    @Test fun consecutiveStepsAreNotMissed() {
        val s = StepLagStats()
        for (n in 0L..99L) s.onStep(n, 100_000)
        assertEquals(0L, s.summary().missedSteps)
        assertEquals(100L, s.summary().n)
    }

    @Test fun skippedStepNumbersAreCounted() {
        val s = StepLagStats()
        for (n in listOf(0L, 1L, 2L, 5L, 6L, 10L)) s.onStep(n, 100_000) // 3,4 와 7,8,9 가 빠짐
        assertEquals(5L, s.summary().missedSteps)
    }

    @Test fun restartFromZeroStartsANewRunWithoutCountingMissed() {
        val s = StepLagStats()
        for (n in 0L..20L) s.onStep(n, 1)
        for (n in 0L..5L) s.onStep(n, 1) // 정지 후 다시 재생
        assertEquals(0L, s.summary().missedSteps)
    }

    @Test fun engineReportsEveryStepNumberInOrderEvenWhenTheClockJumps() {
        val steps = ArrayList<Long>()
        val engine = LoopEngine(FakeSampler(), onStepFired = { n, _ -> steps += n })
        val t0 = 10_000_000_000L
        engine.send(EngineCommand.TogglePlay)
        engine.tick(t0)
        engine.tick(t0 + 50 * stepNanosForBpm(100)) // 시계가 50스텝 뛰어도 밀린 스텝을 모두 순서대로 부른다
        assertEquals((0L..50L).toList(), steps)
        val s = StepLagStats(); steps.forEach { s.onStep(it, 0) }
        assertEquals(0L, s.summary().missedSteps)
    }
}
