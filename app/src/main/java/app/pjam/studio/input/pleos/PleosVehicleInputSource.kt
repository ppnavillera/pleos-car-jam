package app.pjam.studio.input.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import app.pjam.studio.engine.Clock
import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.InputKind
import app.pjam.studio.input.InputMapper
import app.pjam.studio.input.SupportState
import app.pjam.studio.input.TurnDirection
import app.pjam.studio.input.VehicleInputSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 실제 Pleos Vehicle SDK 입력. **SDK 호출은 이 패키지(input/pleos)에만 둔다.**
 * 시그니처는 SDK 2.3.2 AAR을 javap로 확인했다(docs/FINDINGS.md V1):
 *  - Brake: 값 Boolean?, 리스너 BrakePedalStatusListener, 권한 CAR_DRIVE
 *  - TurnSignal: 값 TurnSignalValue? (OFF/RIGHT/LEFT), 리스너 TurnSignalStateListener, 권한 CAR_LIGHTS
 *  - SteeringWheel: 값 Float?(도, SDK 음수 = 왼쪽), 리스너 SteeringWheelAngleListener, 권한 CAR_DRIVE
 * 콜백 스레드는 Binder로 추정 → 콜백에서는 시각을 찍고 InputMapper → tryEmit만 하고 반환한다.
 * 모든 값이 null일 수 있고, 항목이 실패하면 그 항목만 비활성화한다. 앱은 절대 죽지 않는다.
 */
class PleosVehicleInputSource(
    private val context: Context,
    private val clock: Clock,
) : VehicleInputSource {

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<InputEvent> = _events

    // 이 소스가 실제로 다루는 항목만 담는다(나머지 InputKind는 T2-5 등에서 구현할 때 추가). 그래야 InputModes가 "아직 판정 전"을 정확히 본다.
    private val _support = MutableStateFlow(
        listOf(InputKind.BRAKE, InputKind.TURN_SIGNAL, InputKind.STEERING).associateWith { SupportState.UNKNOWN },
    )
    override val support: StateFlow<Map<InputKind, SupportState>> = _support

    private val mapper = InputMapper(rightIsPositive = STEERING_LEFT_IS_NEGATIVE)
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var started = false
    private var brakeRegistered = false
    private var turnRegistered = false
    private var steeringRegistered = false
    private var attempt = 0

    // 같은 인스턴스를 unregister에 넘겨야 하므로 필드로 보관한다
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = onBrake(status, "listener")
        override fun onFailed(exception: Exception) = fail(InputKind.BRAKE, exception)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = onTurn(state, "listener")
        override fun onFailed(exception: Exception) = fail(InputKind.TURN_SIGNAL, exception)
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = onSteering(angle, "listener")
        override fun onFailed(exception: Exception) = fail(InputKind.STEERING, exception)
    }

    override fun start() {
        if (started) return
        started = true
        attempt = 0
        connect()
    }

    private fun connect() {
        try {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            // initialize 완료 콜백이 공개 API에 없다 → 바로 capability를 묻고, 실패하면 잠시 뒤 재시도한다.
            setupBrake(v)
            setupTurn(v)
            setupSteering(v)
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle init failed: $t")
            mark(InputKind.BRAKE, SupportState.FAILED); mark(InputKind.TURN_SIGNAL, SupportState.FAILED); mark(InputKind.STEERING, SupportState.FAILED)
        }
    }

    private fun setupBrake(v: Vehicle) {
        try {
            v.getBrake().checkBrakePedalStatusCapabilities(
                onComplete = { cfg ->
                    val ok = cfg.accessType == AccessType.READ || cfg.accessType == AccessType.READ_WRITE
                    Log.i(TAG, "capability BRAKE accessType=${cfg.accessType} thread=${Thread.currentThread().name}")
                    if (ok) {
                        mark(InputKind.BRAKE, SupportState.SUPPORTED)
                        try {
                            v.getBrake().registerBrakePedalStatus(brakeListener); brakeRegistered = true
                            v.getBrake().getCurrentBrakePedalStatus({ s -> onBrake(s, "initial") }, { e -> Log.w(TAG, "get BRAKE failed: $e") })
                        } catch (t: Throwable) { fail(InputKind.BRAKE, t) }
                    } else mark(InputKind.BRAKE, SupportState.UNSUPPORTED)
                },
                onFailed = { e -> fail(InputKind.BRAKE, e); retryLater() },
            )
        } catch (t: Throwable) { fail(InputKind.BRAKE, t) }
    }

    private fun setupTurn(v: Vehicle) {
        try {
            v.getTurnSignal().checkTurnSignalStateCapabilities(
                onComplete = { cfg ->
                    val ok = cfg.accessType == AccessType.READ || cfg.accessType == AccessType.READ_WRITE
                    Log.i(TAG, "capability TURN_SIGNAL accessType=${cfg.accessType} thread=${Thread.currentThread().name}")
                    if (ok) {
                        mark(InputKind.TURN_SIGNAL, SupportState.SUPPORTED)
                        try {
                            v.getTurnSignal().registerTurnSignalState(turnListener); turnRegistered = true
                            v.getTurnSignal().getCurrentTurnSignalState({ s -> onTurn(s, "initial") }, { e -> Log.w(TAG, "get TURN failed: $e") })
                        } catch (t: Throwable) { fail(InputKind.TURN_SIGNAL, t) }
                    } else mark(InputKind.TURN_SIGNAL, SupportState.UNSUPPORTED)
                },
                onFailed = { e -> fail(InputKind.TURN_SIGNAL, e); retryLater() },
            )
        } catch (t: Throwable) { fail(InputKind.TURN_SIGNAL, t) }
    }

    private fun setupSteering(v: Vehicle) {
        try {
            v.getSteeringWheel().checkSteeringWheelAngleCapabilities(
                onComplete = { cfg, bounds ->
                    val ok = cfg.accessType == AccessType.READ || cfg.accessType == AccessType.READ_WRITE
                    Log.i(TAG, "capability STEERING accessType=${cfg.accessType} range=${bounds.minValue}..${bounds.maxValue} thread=${Thread.currentThread().name}")
                    if (ok) {
                        mark(InputKind.STEERING, SupportState.SUPPORTED)
                        try {
                            v.getSteeringWheel().registerSteeringWheelAngle(steeringListener); steeringRegistered = true
                            v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> onSteering(a, "initial") }, { e -> Log.w(TAG, "get STEERING failed: $e") })
                        } catch (t: Throwable) { fail(InputKind.STEERING, t) }
                    } else mark(InputKind.STEERING, SupportState.UNSUPPORTED)
                },
                onFailed = { e -> fail(InputKind.STEERING, e); retryLater() },
            )
        } catch (t: Throwable) { fail(InputKind.STEERING, t) }
    }

    /** 서비스 연결 전에 호출했다면 onFailed로 올 수 있다: 최대 4번, 1초 간격으로 다시 시도. */
    private fun retryLater() {
        if (!started || attempt >= 4) return
        attempt++
        main.postDelayed({
            val v = vehicle ?: return@postDelayed
            if (_support.value[InputKind.BRAKE] != SupportState.SUPPORTED) setupBrake(v)
            if (_support.value[InputKind.TURN_SIGNAL] != SupportState.SUPPORTED) setupTurn(v)
            if (_support.value[InputKind.STEERING] != SupportState.SUPPORTED) setupSteering(v)
        }, 1000)
    }

    // ---- 콜백 스레드(Binder 추정). 시각을 찍고 매핑·emit만 한다. ----
    private fun onBrake(status: Boolean?, from: String) {
        val ts = clock.nanos()
        Log.i(SIG, "t=$ts item=BRAKE value=$status src=$from thread=${Thread.currentThread().name}")
        val e = synchronized(mapper) { mapper.brake(status, ts) }
        if (e != null) _events.tryEmit(e)
    }

    private fun onSteering(angle: Float?, from: String) {
        val ts = clock.nanos()
        Log.i(SIG, "t=$ts item=STEERING value=$angle src=$from thread=${Thread.currentThread().name}")
        val e = synchronized(mapper) { mapper.steering(angle, ts) }
        if (e != null) _events.tryEmit(e)
    }

    private fun onTurn(state: TurnSignalValue?, from: String) {
        val ts = clock.nanos()
        Log.i(SIG, "t=$ts item=TURN_SIGNAL value=$state src=$from thread=${Thread.currentThread().name}")
        val dir = when (state) {
            TurnSignalValue.LEFT -> TurnDirection.LEFT
            TurnSignalValue.RIGHT -> TurnDirection.RIGHT
            TurnSignalValue.OFF -> TurnDirection.OFF
            null -> null
        }
        val e = synchronized(mapper) { mapper.turnSignal(dir, ts) }
        if (e != null) _events.tryEmit(e)
    }

    private fun fail(kind: InputKind, t: Throwable) {
        Log.w(TAG, "$kind failed: $t")
        mark(kind, SupportState.FAILED)
    }

    private fun mark(kind: InputKind, s: SupportState) {
        _support.value = _support.value + (kind to s)
    }

    override fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        val v = vehicle ?: return
        // 종료 순서: 모든 unregister → release (콜백 진행 중에는 release하지 않는다)
        try { if (brakeRegistered) v.getBrake().unregisterBrakePedalStatus(brakeListener) } catch (t: Throwable) { Log.w(TAG, "unregister brake: $t") }
        try { if (turnRegistered) v.getTurnSignal().unregisterTurnSignalState(turnListener) } catch (t: Throwable) { Log.w(TAG, "unregister turn: $t") }
        try { if (steeringRegistered) v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) } catch (t: Throwable) { Log.w(TAG, "unregister steering: $t") }
        brakeRegistered = false; turnRegistered = false; steeringRegistered = false
        try { v.release() } catch (t: Throwable) { Log.w(TAG, "release: $t") }
        vehicle = null
    }

    companion object {
        const val TAG = "PJAM_PLEOS"
        const val SIG = "PJAM_SIG"
        /** SDK 핸들 각도는 음수 = 왼쪽 (AAOS 정의, FINDINGS V4). Virtual Console 화면으로 눈 확인 전이다. 반대로 보이면 false로 뒤집는다. */
        const val STEERING_LEFT_IS_NEGATIVE = true
    }
}
