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
    /** raw = 정규화 전 원래 값(설정 온도 °C 등), semitones = 설정 온도가 기준 대비 정하는 조(−3..+3). 온도 외에는 기본값. */
    data class Level(val source: LevelSource, val normalized: Float, override val timestampNanos: Long, val raw: Float? = null, val semitones: Int = 0) : InputEvent
    data class Cycle(val source: CycleSource, override val timestampNanos: Long) : InputEvent
}
