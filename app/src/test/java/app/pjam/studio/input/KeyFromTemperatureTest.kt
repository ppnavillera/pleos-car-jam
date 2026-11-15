package app.pjam.studio.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFromTemperatureTest {
    @Test fun firstValueIsTheBaselineThenHalfDegreeIsOneSemitone() {
        val k = KeyFromTemperature()
        assertEquals(0, k.semitones(22.0f))
        assertEquals(1, k.semitones(22.5f))
        assertEquals(2, k.semitones(23.0f))
        assertEquals(-1, k.semitones(21.5f))
        assertEquals(0, k.semitones(22.0f))
    }

    @Test fun clampedToPlusMinusThree() {
        val k = KeyFromTemperature(); k.semitones(22.0f)
        assertEquals(3, k.semitones(30.0f))
        assertEquals(-3, k.semitones(15.0f))
        assertEquals(3, k.semitones(25.0f)) // 정확히 +6단계도 3으로
    }

    @Test fun inBetweenValuesRoundToNearestStep() {
        val k = KeyFromTemperature(); k.semitones(20.0f)
        assertEquals(1, k.semitones(20.4f))
        assertEquals(0, k.semitones(20.2f))
    }

    @Test fun mapperCarriesRawAndSemitonesAndUsesFirstValueAsBaseline() {
        val m = InputMapper()
        assertNull(m.level(LevelSource.TEMPERATURE, 22.0f, 15f, 30f, 0)) // 기준값, 이벤트 아님
        val up = m.level(LevelSource.TEMPERATURE, 23.0f, 15f, 30f, 1) as InputEvent.Level
        assertEquals(2, up.semitones); assertEquals(23.0f, up.raw)
        val down = m.level(LevelSource.TEMPERATURE, 20.5f, 15f, 30f, 2) as InputEvent.Level
        assertEquals(-3, down.semitones)
        val back = m.level(LevelSource.TEMPERATURE, 22.0f, 15f, 30f, 3) as InputEvent.Level
        assertEquals(0, back.semitones)
    }

    @Test fun otherLevelsHaveNoSemitones() {
        val m = InputMapper(); m.level(LevelSource.FAN_SPEED, 3f, 1f, 8f, 0)
        assertEquals(0, (m.level(LevelSource.FAN_SPEED, 6f, 1f, 8f, 1) as InputEvent.Level).semitones)
    }
}
