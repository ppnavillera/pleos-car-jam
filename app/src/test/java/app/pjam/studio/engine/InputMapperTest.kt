package app.pjam.studio.engine

import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.InputMapper
import app.pjam.studio.input.LevelSource
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource
import app.pjam.studio.input.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputMapperTest {
    private val m = InputMapper()

    @Test fun brakeFiresOnFalseToTrueOnly() {
        assertNull(m.brake(false, 1)) // 기준값
        assertEquals(InputEvent.Trigger(TriggerSource.BRAKE, 2), m.brake(true, 2))
        assertNull(m.brake(true, 3)) // 계속 밟고 있음 / 같은 값 반복
        assertNull(m.brake(false, 4)) // 뗌은 Trigger 아님
        assertEquals(InputEvent.Trigger(TriggerSource.BRAKE, 5), m.brake(true, 5))
    }

    @Test fun brakeInitiallyPressedDoesNotFire() {
        assertNull(m.brake(true, 0))
        assertNull(m.brake(true, 1))
        assertNull(m.brake(false, 2))
        assertEquals(InputEvent.Trigger(TriggerSource.BRAKE, 3), m.brake(true, 3))
    }

    @Test fun brakeNullIsIgnoredAndKeepsState() {
        m.brake(false, 0)
        assertNull(m.brake(null, 1))
        assertEquals(InputEvent.Trigger(TriggerSource.BRAKE, 2), m.brake(true, 2))
    }

    @Test fun turnSignalTriggersOnOffToDirection() {
        assertNull(m.turnSignal(TurnDirection.OFF, 0)) // 기준값
        assertEquals(InputEvent.Trigger(TriggerSource.TURN_LEFT, 1), m.turnSignal(TurnDirection.LEFT, 1))
        assertNull(m.turnSignal(TurnDirection.LEFT, 2)) // 점멸 콜백 반복
        assertNull(m.turnSignal(TurnDirection.LEFT, 3)) // Blink↔Flash는 SDK에서 같은 LEFT로 온다
        assertNull(m.turnSignal(TurnDirection.OFF, 4))
        assertEquals(InputEvent.Trigger(TriggerSource.TURN_RIGHT, 5), m.turnSignal(TurnDirection.RIGHT, 5))
    }

    @Test fun turnSignalDirectLeftToRightIsTrigger() {
        m.turnSignal(TurnDirection.OFF, 0); m.turnSignal(TurnDirection.LEFT, 1)
        assertEquals(InputEvent.Trigger(TriggerSource.TURN_RIGHT, 2), m.turnSignal(TurnDirection.RIGHT, 2))
    }

    @Test fun turnSignalInitiallyOnDoesNotFire() {
        assertNull(m.turnSignal(TurnDirection.LEFT, 0))
        assertNull(m.turnSignal(TurnDirection.LEFT, 1))
    }

    @Test fun steeringZonesWithHysteresis() {
        assertNull(m.steering(0f, 0)) // 기준 = 중앙
        assertNull(m.steering(9f, 1)) // 경계 안
        assertEquals(InputEvent.Zone(SteeringZone.RIGHT, 2), m.steering(12f, 2))
        assertNull(m.steering(9f, 3)) // 히스테리시스: 7~10 사이는 유지
        assertNull(m.steering(8f, 4))
        assertEquals(InputEvent.Zone(SteeringZone.CENTER, 5), m.steering(6f, 5))
        assertNull(m.steering(-9f, 6))
        assertEquals(InputEvent.Zone(SteeringZone.LEFT, 7), m.steering(-11f, 7))
        assertNull(m.steering(-30f, 8)) // 가장자리 구간 유지
        assertNull(m.steering(-470f, 9))
    }

    @Test fun steeringJitterAroundBoundaryDoesNotFlap() {
        m.steering(0f, 0)
        var events = 0
        for ((i, d) in listOf(9.5f, 10.2f, 9.8f, 10.1f, 9.9f, 10.3f).withIndex()) if (m.steering(d, i.toLong()) != null) events++
        assertEquals(1, events)
    }

    @Test fun steeringSdkNegativeIsLeft() {
        // V4: SDK 값은 음수 = 왼쪽, 양수 = 오른쪽 (Virtual Console과 반대 부호)
        m.steering(0f, 0)
        assertEquals(InputEvent.Zone(SteeringZone.LEFT, 1), m.steering(-15f, 1))
        assertEquals(InputEvent.Zone(SteeringZone.CENTER, 2), m.steering(-2f, 2))
        assertEquals(InputEvent.Zone(SteeringZone.RIGHT, 3), m.steering(15f, 3))
    }

    @Test fun steeringFirstValueIsBaselineEvenFarFromCenter() {
        assertNull(m.steering(-100f, 0)) // 구독 직후 값은 기준
        assertNull(m.steering(-120f, 1))
        assertEquals(InputEvent.Zone(SteeringZone.CENTER, 2), m.steering(0f, 2))
    }

    @Test fun steeringSignCanBeFlipped() {
        val flipped = InputMapper(rightIsPositive = false)
        flipped.steering(0f, 0)
        assertEquals(InputEvent.Zone(SteeringZone.LEFT, 1), flipped.steering(15f, 1))
    }

    @Test fun toggleFiresOnlyOnChange() {
        assertNull(m.toggle(ToggleSource.AC, false, 0)) // 기준값
        assertNull(m.toggle(ToggleSource.AC, false, 1)) // 같은 값 반복
        assertEquals(InputEvent.Toggle(ToggleSource.AC, 2), m.toggle(ToggleSource.AC, true, 2))
        assertEquals(InputEvent.Toggle(ToggleSource.AC, 3), m.toggle(ToggleSource.AC, false, 3))
        assertNull(m.toggle(ToggleSource.AUTO, true, 4)) // 항목별 독립 기준값
    }

    @Test fun levelNormalizesByCapabilityRange() {
        assertNull(m.level(LevelSource.FAN_SPEED, 0f, 0f, 8f, 0))
        assertEquals(InputEvent.Level(LevelSource.FAN_SPEED, 0.5f, 1), m.level(LevelSource.FAN_SPEED, 4f, 0f, 8f, 1))
        assertNull(m.level(LevelSource.FAN_SPEED, 4f, 0f, 8f, 2))
        assertNull(m.level(LevelSource.WINDOW, 5f, 0f, 0f, 3)) // 범위를 모르면 무시
    }
}
