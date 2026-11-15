package app.pjam.studio.input

import app.pjam.studio.engine.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 개발·테스트용 가짜 입력. 디버그 패널의 버튼이 emit()을 부른다. 시계는 엔진과 같은 것을 쓴다. */
class FakeVehicleInputSource(private val clock: Clock) : VehicleInputSource {
    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<InputEvent> = _events

    private val _support = MutableStateFlow(InputKind.entries.associateWith { SupportState.SUPPORTED })
    override val support: StateFlow<Map<InputKind, SupportState>> = _support

    private val _activity = MutableSharedFlow<InputKind>(extraBufferCapacity = 64)
    override val activity: SharedFlow<InputKind> = _activity
    override val hvacPower: StateFlow<Boolean?> = MutableStateFlow(null)

    override fun start() = Unit
    override fun stop() = Unit

    fun trigger(source: TriggerSource) = emit(InputEvent.Trigger(source, clock.nanos()))
    fun zone(zone: SteeringZone) = emit(InputEvent.Zone(zone, clock.nanos()))
    fun toggle(source: ToggleSource) = emit(InputEvent.Toggle(source, clock.nanos()))
    fun level(source: LevelSource, normalized: Float) = emit(InputEvent.Level(source, normalized, clock.nanos()))
    fun cycle(source: CycleSource) = emit(InputEvent.Cycle(source, clock.nanos()))

    fun emit(event: InputEvent): Boolean {
        _activity.tryEmit(kindOf(event))
        return _events.tryEmit(event)
    }

    private fun kindOf(e: InputEvent): InputKind = when (e) {
        is InputEvent.Trigger -> if (e.source == TriggerSource.BRAKE) InputKind.BRAKE else InputKind.TURN_SIGNAL
        is InputEvent.Zone -> InputKind.STEERING
        is InputEvent.Toggle -> HvacAssignment.kindOf(e.source)
        is InputEvent.Level -> when (e.source) { LevelSource.FAN_SPEED -> InputKind.FAN_SPEED; LevelSource.TEMPERATURE -> InputKind.TEMPERATURE; LevelSource.WINDOW -> InputKind.WINDOW }
        is InputEvent.Cycle -> if (e.source == CycleSource.WIPER) InputKind.WIPER else InputKind.DRIVE_MODE
    }
}
