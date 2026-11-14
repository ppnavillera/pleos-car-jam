package app.pjam.studio.input

enum class TriggerSource { BRAKE, TURN_LEFT, TURN_RIGHT }
enum class SteeringZone { LEFT, CENTER, RIGHT }
enum class ToggleSource { AC, AUTO, RECIRCULATION, DEFROSTER }
enum class LevelSource { FAN_SPEED, TEMPERATURE, WINDOW }
enum class CycleSource { DRIVE_MODE, WIPER }

/** 모든 차량 신호가 거치는 공통 이벤트 (기획서 6.2). timestampNanos = elapsedRealtimeNanos. */
sealed interface InputEvent {
    val timestampNanos: Long

    data class Trigger(val source: TriggerSource, override val timestampNanos: Long) : InputEvent
    data class Zone(val zone: SteeringZone, override val timestampNanos: Long) : InputEvent
    data class Toggle(val source: ToggleSource, override val timestampNanos: Long) : InputEvent
    data class Level(val source: LevelSource, val normalized: Float, override val timestampNanos: Long) : InputEvent
    data class Cycle(val source: CycleSource, override val timestampNanos: Long) : InputEvent
}
