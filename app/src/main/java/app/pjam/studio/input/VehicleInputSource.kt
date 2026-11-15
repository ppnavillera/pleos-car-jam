package app.pjam.studio.input

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class InputKind { BRAKE, TURN_SIGNAL, STEERING, AC, AUTO, RECIRCULATION, DEFROSTER, HVAC_POWER, FAN_SPEED, TEMPERATURE, WINDOW, DRIVE_MODE, WIPER }

enum class SupportState { UNKNOWN, SUPPORTED, UNSUPPORTED, FAILED }

/** 차량 입력의 추상화. 구현: PleosVehicleInputSource(실제 SDK), FakeVehicleInputSource(디버그 패널). */
interface VehicleInputSource {
    val events: SharedFlow<InputEvent>
    val support: StateFlow<Map<InputKind, SupportState>>
    /** 항목의 원시 콜백이 올 때마다 그 항목을 내보낸다. "내 차 장비" 줄 반짝임용(이벤트 매핑 결과와 무관). */
    val activity: SharedFlow<InputKind>
    /** 공조 전원. 꺼져 있으면 공조 콜백이 안 올 수 있다. 읽을 수 없으면 null. */
    val hvacPower: StateFlow<Boolean?>
    fun start()
    fun stop()
}
