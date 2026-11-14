package app.pjam.studio.input

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class InputKind { BRAKE, TURN_SIGNAL, STEERING, AC, AUTO, RECIRCULATION, DEFROSTER, FAN_SPEED, TEMPERATURE, WINDOW, DRIVE_MODE, WIPER }

enum class SupportState { UNKNOWN, SUPPORTED, UNSUPPORTED, FAILED }

/** 차량 입력의 추상화. 구현: PleosVehicleInputSource(실제 SDK), FakeVehicleInputSource(디버그 패널). */
interface VehicleInputSource {
    val events: SharedFlow<InputEvent>
    val support: StateFlow<Map<InputKind, SupportState>>
    fun start()
    fun stop()
}
