package app.pjam.studio.engine

import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.InputMapper
import app.pjam.studio.input.LevelSource
import org.junit.Assert.assertEquals
import org.junit.Test

class TempoTest {
    @Test fun endpointsAndMiddle() {
        assertEquals(70, Tempo.bpmFor(0f))
        assertEquals(140, Tempo.bpmFor(1f))
        assertEquals(105, Tempo.bpmFor(0.5f))
    }

    @Test fun outOfRangeClampsToEnds() {
        assertEquals(70, Tempo.bpmFor(-3f))
        assertEquals(140, Tempo.bpmFor(9f))
    }

    @Test fun monotonicOverEightFanSteps() {
        val bpms = (1..8).map { Tempo.bpmFor((it - 1) / 7f) }
        assertEquals(70, bpms.first()); assertEquals(140, bpms.last())
        assertEquals(bpms.sorted(), bpms)
        assertEquals(8, bpms.toSet().size)
    }

    /** capability 1..8 에서 SDK가 0(범위 밖)을 주면 70 BPM, 9 이상이면 140 BPM. */
    @Test fun fanValueOutsideCapabilityRangeMapsToEndTempo() {
        val m = InputMapper()
        assertEquals(null, m.level(LevelSource.FAN_SPEED, 4f, 1f, 8f, 0)) // 기준값
        val low = m.level(LevelSource.FAN_SPEED, 0f, 1f, 8f, 1) as InputEvent.Level
        assertEquals(70, Tempo.bpmFor(low.normalized))
        val high = m.level(LevelSource.FAN_SPEED, 11f, 1f, 8f, 2) as InputEvent.Level
        assertEquals(140, Tempo.bpmFor(high.normalized))
    }

    /** 템포가 바뀌어도 스텝 번호는 이어지고 소리가 몰리지 않는다 (엔진 SetBpm: Transport.setStepNanos). */
    @Test fun tempoChangeKeepsStepPosition() {
        val sampler = FakeSampler()
        val engine = LoopEngine(sampler)
        var now = 1_000_000_000L
        engine.send(EngineCommand.Start); engine.tick(now)
        val unit = 1_000_000L
        repeat(900) { now += unit; engine.tick(now) } // 약 6스텝
        val before = engine.state.value.step
        engine.send(EngineCommand.SetBpm(Tempo.bpmFor(1f)))
        repeat(2) { now += unit; engine.tick(now) }
        assertEquals(140, engine.state.value.bpm)
        assertEquals(before, engine.state.value.step)
        repeat(400) { now += unit; engine.tick(now) }
        assertEquals(true, engine.state.value.step > before) // 앞으로 계속 진행(되감기지 않음)
    }
}
