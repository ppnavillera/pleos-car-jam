package app.pjam.studio.input

import app.pjam.studio.lock.GearMethod
import app.pjam.studio.lock.GearSink
import app.pjam.studio.lock.GearSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 차량 신호를 쓸 수 있는지. SCREEN_ONLY면 화면 조작만으로 동작한다(앱은 계속 쓸 수 있다). */
enum class InputMode { CHECKING, VEHICLE, SCREEN_ONLY }

object InputModes {
    /** 시작이 실패했거나 모든 항목이 미지원/실패면 SCREEN_ONLY, 하나라도 지원되면 VEHICLE, 아직 판정 전이면 CHECKING. */
    fun resolve(support: Map<InputKind, SupportState>, startFailed: Boolean): InputMode {
        if (startFailed) return InputMode.SCREEN_ONLY
        val states = support.values
        if (states.any { it == SupportState.SUPPORTED }) return InputMode.VEHICLE
        if (states.any { it == SupportState.UNKNOWN }) return InputMode.CHECKING
        return InputMode.SCREEN_ONLY
    }
}

/**
 * 실제 차량 입력과 기어 읽기를 시작한다 (T1-12). 어느 쪽이든 만들거나 시작하다가 어떤 Throwable이 나도
 * (SDK 초기화 실패, Car 라이브러리가 없는 기기의 NoClassDefFoundError 등) 앱은 죽지 않고 화면 조작만 되는 상태로 넘어간다.
 * 기어를 못 읽으면 "P로 간주 + 기어 확인 불가"(계획서 3.6 단계 4)다. 순수 Kotlin이라 JVM 단위 테스트에서 실패를 주입한다.
 */
class InputSourceManager(
    private val vehicleFactory: () -> VehicleInputSource,
    private val gearFactory: () -> GearSource,
    private val gearSink: GearSink,
    private val log: (String) -> Unit = {},
) {
    @Volatile var vehicleSource: VehicleInputSource? = null
        private set
    private var gearSource: GearSource? = null

    private val _startFailed = MutableStateFlow(false)
    val startFailed: StateFlow<Boolean> = _startFailed

    fun start() {
        try {
            vehicleSource = vehicleFactory().also { it.start() }
        } catch (t: Throwable) {
            log("vehicle input unavailable → screen only: $t")
            vehicleSource = null
            _startFailed.value = true
        }
        try {
            gearSource = gearFactory().also { it.start() }
        } catch (t: Throwable) {
            log("gear source unavailable → assume P: $t")
            gearSource = null
            gearSink.onMethod(GearMethod.ASSUMED_PARK)
        }
    }

    fun stop() {
        try { vehicleSource?.stop() } catch (t: Throwable) { log("vehicle stop: $t") }
        try { gearSource?.stop() } catch (t: Throwable) { log("gear stop: $t") }
    }
}
