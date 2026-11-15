package app.pjam.studio.input.pleos

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacAutoStateListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import app.pjam.studio.engine.Clock
import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.InputKind
import app.pjam.studio.input.InputMapper
import app.pjam.studio.input.LevelSource
import app.pjam.studio.input.SupportState
import app.pjam.studio.input.SupportTracker
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TurnDirection
import app.pjam.studio.input.VehicleInputSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 실제 Pleos Vehicle SDK 입력. **SDK 호출은 이 패키지(input/pleos)에만 둔다.**
 * 시그니처는 SDK 2.3.2 AAR을 javap로 확인했다(docs/FINDINGS.md V1):
 *  - Brake: 값 Boolean?, 권한 CAR_DRIVE / TurnSignal: TurnSignalValue? (OFF/RIGHT/LEFT), CAR_LIGHTS / SteeringWheel: Float?(도, SDK 음수 = 왼쪽), CAR_DRIVE
 *  - HVAC A/C·AUTO·내기순환: 영역 SeatArea, 성에 제거: WindowArea, 전원: SeatArea. 값 Boolean?, 권한 CAR_CLIMATE.
 *    **영역은 capability가 돌려준 것을 그대로 get/register에 넘긴다**(낱개 영역은 IllegalArgumentException, FINDINGS V1).
 * 콜백은 Binder 스레드에서 온다 → 콜백에서는 시각을 찍고 InputMapper → tryEmit만 하고 반환한다.
 * 모든 값이 null일 수 있다. 콜백 본문은 모두 `tracker.guard`로 감싸서, 예외가 나도 그 항목만 FAILED가 되고 앱은 죽지 않는다.
 */
class PleosVehicleInputSource(
    private val context: Context,
    private val clock: Clock,
) : VehicleInputSource {

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<InputEvent> = _events

    private val _activity = MutableSharedFlow<InputKind>(extraBufferCapacity = 64)
    override val activity: SharedFlow<InputKind> = _activity

    private val _hvacPower = MutableStateFlow<Boolean?>(null)
    override val hvacPower: StateFlow<Boolean?> = _hvacPower

    // 이 소스가 실제로 다루는 항목만 담는다(나머지 InputKind는 T3에서 구현할 때 추가). 그래야 InputModes가 "아직 판정 전"을 정확히 본다.
    private val tracker = SupportTracker(
        listOf(InputKind.BRAKE, InputKind.TURN_SIGNAL, InputKind.STEERING, InputKind.AC, InputKind.AUTO, InputKind.RECIRCULATION, InputKind.DEFROSTER, InputKind.HVAC_POWER, InputKind.FAN_SPEED),
    ) { Log.w(TAG, it) }
    override val support: StateFlow<Map<InputKind, SupportState>> = tracker.state

    private val mapper = InputMapper(rightIsPositive = STEERING_LEFT_IS_NEGATIVE)
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var started = false
    private var attempt = 0

    // register에 넘긴 영역. unregister에도 같은 영역·같은 리스너 인스턴스를 넘겨야 한다.
    private var brakeRegistered = false
    private var turnRegistered = false
    private var steeringRegistered = false
    private var acArea: SeatArea? = null
    private var autoArea: SeatArea? = null
    private var recircArea: SeatArea? = null
    private var defrostArea: WindowArea? = null
    private var powerArea: SeatArea? = null
    private var fanArea: SeatArea? = null
    // 풍량 범위는 capability에서 받은 값을 쓴다 (실측 1..8, 값 0도 콜백으로 옴). 범위 밖 값은 InputMapper가 끝 값으로 처리한다.
    @Volatile private var fanMin = 0f
    @Volatile private var fanMax = 0f

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = onBrake(status, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.BRAKE, exception)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = onTurn(state, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.TURN_SIGNAL, exception)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = onSteering(angle, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.STEERING, exception)
    }
    private val acListener = object : HvacAcStateListener {
        override fun onHvacAcStateUpdated(state: Boolean?) = onToggle(InputKind.AC, ToggleSource.AC, state, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.AC, exception)
    }
    private val autoListener = object : HvacAutoStateListener {
        override fun onHvacAutoStateUpdated(state: Boolean?) = onToggle(InputKind.AUTO, ToggleSource.AUTO, state, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.AUTO, exception)
    }
    private val recircListener = object : HvacRecirculationStateListener {
        // SDK 메서드 이름이 AutoRecirculation이지만 getHvacRecirculationState에 쓰는 리스너다(FINDINGS V1)
        override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) = onToggle(InputKind.RECIRCULATION, ToggleSource.RECIRCULATION, state, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.RECIRCULATION, exception)
    }
    private val defrostListener = object : HvacDefrosterStateListener {
        override fun onHvacDefrosterStateUpdated(state: Boolean?) = onToggle(InputKind.DEFROSTER, ToggleSource.DEFROSTER, state, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.DEFROSTER, exception)
    }
    private val powerListener = object : HvacPowerStateListener {
        override fun onHvacPowerStateUpdated(state: Boolean?) = onPower(state, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.HVAC_POWER, exception)
    }

    private val fanListener = object : HvacFanSpeedListener {
        override fun onHvacFanSpeedUpdated(value: Int?) = onFan(value, "listener")
        override fun onFailed(exception: Exception) = tracker.fail(InputKind.FAN_SPEED, exception)
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
            setupAll(v)
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle init failed: $t")
            for (k in ALL_KINDS) tracker.mark(k, SupportState.FAILED)
        }
    }

    private fun setupAll(v: Vehicle) {
        if (tracker.get(InputKind.BRAKE) != SupportState.SUPPORTED) setupBrake(v)
        if (tracker.get(InputKind.TURN_SIGNAL) != SupportState.SUPPORTED) setupTurn(v)
        if (tracker.get(InputKind.STEERING) != SupportState.SUPPORTED) setupSteering(v)
        if (tracker.get(InputKind.AC) != SupportState.SUPPORTED) setupAc(v)
        if (tracker.get(InputKind.AUTO) != SupportState.SUPPORTED) setupAuto(v)
        if (tracker.get(InputKind.RECIRCULATION) != SupportState.SUPPORTED) setupRecirc(v)
        if (tracker.get(InputKind.DEFROSTER) != SupportState.SUPPORTED) setupDefroster(v)
        if (tracker.get(InputKind.HVAC_POWER) != SupportState.SUPPORTED) setupPower(v)
        if (tracker.get(InputKind.FAN_SPEED) != SupportState.SUPPORTED) setupFan(v)
    }

    private fun readable(a: AccessType) = a == AccessType.READ || a == AccessType.READ_WRITE

    private fun setupBrake(v: Vehicle) {
        tracker.guard(InputKind.BRAKE) {
            v.getBrake().checkBrakePedalStatusCapabilities(
                onComplete = { cfg ->
                    tracker.guard(InputKind.BRAKE) {
                        Log.i(TAG, "capability BRAKE accessType=${cfg.accessType} thread=${Thread.currentThread().name}")
                        if (readable(cfg.accessType)) {
                            tracker.mark(InputKind.BRAKE, SupportState.SUPPORTED)
                            v.getBrake().registerBrakePedalStatus(brakeListener); brakeRegistered = true
                            v.getBrake().getCurrentBrakePedalStatus({ s -> onBrake(s, "initial") }, { e -> Log.w(TAG, "get BRAKE failed: $e") })
                        } else tracker.mark(InputKind.BRAKE, SupportState.UNSUPPORTED)
                    }
                },
                onFailed = { e -> tracker.fail(InputKind.BRAKE, e); retryLater() },
            )
        }
    }

    private fun setupTurn(v: Vehicle) {
        tracker.guard(InputKind.TURN_SIGNAL) {
            v.getTurnSignal().checkTurnSignalStateCapabilities(
                onComplete = { cfg ->
                    tracker.guard(InputKind.TURN_SIGNAL) {
                        Log.i(TAG, "capability TURN_SIGNAL accessType=${cfg.accessType} thread=${Thread.currentThread().name}")
                        if (readable(cfg.accessType)) {
                            tracker.mark(InputKind.TURN_SIGNAL, SupportState.SUPPORTED)
                            v.getTurnSignal().registerTurnSignalState(turnListener); turnRegistered = true
                            v.getTurnSignal().getCurrentTurnSignalState({ s -> onTurn(s, "initial") }, { e -> Log.w(TAG, "get TURN failed: $e") })
                        } else tracker.mark(InputKind.TURN_SIGNAL, SupportState.UNSUPPORTED)
                    }
                },
                onFailed = { e -> tracker.fail(InputKind.TURN_SIGNAL, e); retryLater() },
            )
        }
    }

    private fun setupSteering(v: Vehicle) {
        tracker.guard(InputKind.STEERING) {
            v.getSteeringWheel().checkSteeringWheelAngleCapabilities(
                onComplete = { cfg, bounds ->
                    tracker.guard(InputKind.STEERING) {
                        Log.i(TAG, "capability STEERING accessType=${cfg.accessType} range=${bounds.minValue}..${bounds.maxValue} thread=${Thread.currentThread().name}")
                        if (readable(cfg.accessType)) {
                            tracker.mark(InputKind.STEERING, SupportState.SUPPORTED)
                            v.getSteeringWheel().registerSteeringWheelAngle(steeringListener); steeringRegistered = true
                            v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> onSteering(a, "initial") }, { e -> Log.w(TAG, "get STEERING failed: $e") })
                        } else tracker.mark(InputKind.STEERING, SupportState.UNSUPPORTED)
                    }
                },
                onFailed = { e -> tracker.fail(InputKind.STEERING, e); retryLater() },
            )
        }
    }

    // ---- 공조. 영역은 capability가 돌려준 것을 그대로 쓴다 ----
    private fun setupAc(v: Vehicle) {
        tracker.guard(InputKind.AC) {
            v.getHVAC().checkHvacAcCapabilities(
                { cfg, areas ->
                    tracker.guard(InputKind.AC) {
                        val area = areas.firstOrNull()
                        Log.i(TAG, "capability AC accessType=${cfg.accessType} areas=${areas.size}")
                        if (readable(cfg.accessType) && area != null) {
                            tracker.mark(InputKind.AC, SupportState.SUPPORTED)
                            v.getHVAC().registerHvacAcState(area, acListener); acArea = area
                            v.getHVAC().getHvacAcState(area, { s -> onToggle(InputKind.AC, ToggleSource.AC, s, "initial") }, { e -> Log.w(TAG, "get AC failed: $e") })
                        } else tracker.mark(InputKind.AC, SupportState.UNSUPPORTED)
                    }
                },
                { e -> tracker.fail(InputKind.AC, e); retryLater() },
            )
        }
    }

    private fun setupAuto(v: Vehicle) {
        tracker.guard(InputKind.AUTO) {
            v.getHVAC().checkHvacAutoCapabilities(
                { cfg, areas ->
                    tracker.guard(InputKind.AUTO) {
                        val area = areas.firstOrNull()
                        Log.i(TAG, "capability AUTO accessType=${cfg.accessType} areas=${areas.size}")
                        if (readable(cfg.accessType) && area != null) {
                            tracker.mark(InputKind.AUTO, SupportState.SUPPORTED)
                            v.getHVAC().registerHvacAutoState(area, autoListener); autoArea = area
                            v.getHVAC().getHvacAutoState(area, { s -> onToggle(InputKind.AUTO, ToggleSource.AUTO, s, "initial") }, { e -> Log.w(TAG, "get AUTO failed: $e") })
                        } else tracker.mark(InputKind.AUTO, SupportState.UNSUPPORTED)
                    }
                },
                { e -> tracker.fail(InputKind.AUTO, e); retryLater() },
            )
        }
    }

    private fun setupRecirc(v: Vehicle) {
        tracker.guard(InputKind.RECIRCULATION) {
            v.getHVAC().checkHvacRecirculationCapabilities(
                { cfg, areas ->
                    tracker.guard(InputKind.RECIRCULATION) {
                        val area = areas.firstOrNull()
                        Log.i(TAG, "capability RECIRCULATION accessType=${cfg.accessType} areas=${areas.size}")
                        if (readable(cfg.accessType) && area != null) {
                            tracker.mark(InputKind.RECIRCULATION, SupportState.SUPPORTED)
                            v.getHVAC().registerHvacRecirculationState(area, recircListener); recircArea = area
                            v.getHVAC().getHvacRecirculationState(area, { s -> onToggle(InputKind.RECIRCULATION, ToggleSource.RECIRCULATION, s, "initial") }, { e -> Log.w(TAG, "get RECIRC failed: $e") })
                        } else tracker.mark(InputKind.RECIRCULATION, SupportState.UNSUPPORTED)
                    }
                },
                { e -> tracker.fail(InputKind.RECIRCULATION, e); retryLater() },
            )
        }
    }

    private fun setupDefroster(v: Vehicle) {
        tracker.guard(InputKind.DEFROSTER) {
            v.getHVAC().checkHvacDefrosterCapabilities(
                { cfg, areas ->
                    tracker.guard(InputKind.DEFROSTER) {
                        // 앞유리 영역을 우선 쓴다. 없으면 capability가 돌려준 첫 영역.
                        val area = areas.firstOrNull { a -> a.values.contains(WindowValue.FRONT_WINDSHIELD) } ?: areas.firstOrNull()
                        Log.i(TAG, "capability DEFROSTER accessType=${cfg.accessType} areas=${areas.size} chosen=${area?.values}")
                        if (readable(cfg.accessType) && area != null) {
                            tracker.mark(InputKind.DEFROSTER, SupportState.SUPPORTED)
                            v.getHVAC().registerHvacDefrosterState(area, defrostListener); defrostArea = area
                            v.getHVAC().getHvacDefrosterState(area, { s -> onToggle(InputKind.DEFROSTER, ToggleSource.DEFROSTER, s, "initial") }, { e -> Log.w(TAG, "get DEFROSTER failed: $e") })
                        } else tracker.mark(InputKind.DEFROSTER, SupportState.UNSUPPORTED)
                    }
                },
                { e -> tracker.fail(InputKind.DEFROSTER, e); retryLater() },
            )
        }
    }

    private fun setupPower(v: Vehicle) {
        tracker.guard(InputKind.HVAC_POWER) {
            v.getHVAC().checkHvacPowerCapabilities(
                { cfg, areas ->
                    tracker.guard(InputKind.HVAC_POWER) {
                        val area = areas.firstOrNull()
                        Log.i(TAG, "capability HVAC_POWER accessType=${cfg.accessType} areas=${areas.size}")
                        if (readable(cfg.accessType) && area != null) {
                            tracker.mark(InputKind.HVAC_POWER, SupportState.SUPPORTED)
                            v.getHVAC().registerHvacPowerState(area, powerListener); powerArea = area
                            v.getHVAC().getHvacPowerState(area, { s -> onPower(s, "initial") }, { e -> Log.w(TAG, "get HVAC_POWER failed: $e") })
                        } else tracker.mark(InputKind.HVAC_POWER, SupportState.UNSUPPORTED)
                    }
                },
                { e -> tracker.fail(InputKind.HVAC_POWER, e); retryLater() },
            )
        }
    }

    /** 풍량(T3-1): 영역과 범위는 capability가 돌려준 것을 그대로 쓴다. 범위를 못 받으면 미지원으로 둔다. */
    private fun setupFan(v: Vehicle) {
        tracker.guard(InputKind.FAN_SPEED) {
            v.getHVAC().checkHvacFanSpeedCapabilities(
                { cfg, areas ->
                    tracker.guard(InputKind.FAN_SPEED) {
                        val entry = areas.entries.firstOrNull()
                        val bounds = entry?.value
                        Log.i(TAG, "capability FAN_SPEED accessType=${cfg.accessType} areas=${areas.size} range=${bounds?.minValue}..${bounds?.maxValue}")
                        val lo = bounds?.minValue
                        val hi = bounds?.maxValue
                        if (readable(cfg.accessType) && entry != null && lo != null && hi != null && hi > lo) {
                            fanMin = lo.toFloat(); fanMax = hi.toFloat()
                            tracker.mark(InputKind.FAN_SPEED, SupportState.SUPPORTED)
                            v.getHVAC().registerHvacFanSpeed(entry.key, fanListener); fanArea = entry.key
                            v.getHVAC().getHvacFanSpeed(entry.key, { s -> onFan(s, "initial") }, { e -> Log.w(TAG, "get FAN_SPEED failed: $e") })
                        } else tracker.mark(InputKind.FAN_SPEED, SupportState.UNSUPPORTED)
                    }
                },
                { e -> tracker.fail(InputKind.FAN_SPEED, e); retryLater() },
            )
        }
    }

    /** 서비스 연결 전에 호출했다면 onFailed로 올 수 있다: 최대 4번, 1초 간격으로 다시 시도. */
    private fun retryLater() {
        if (!started || attempt >= 4) return
        attempt++
        main.postDelayed({
            val v = vehicle ?: return@postDelayed
            try { setupAll(v) } catch (t: Throwable) { Log.w(TAG, "retry failed: $t") }
        }, 1000)
    }

    // ---- 콜백 스레드(Binder). 시각을 찍고 매핑·emit만 한다. 본문이 던져도 그 항목만 FAILED. ----
    private fun onBrake(status: Boolean?, from: String) {
        tracker.guard(InputKind.BRAKE) {
            val ts = clock.nanos()
            Log.i(SIG, "t=$ts item=BRAKE value=$status src=$from thread=${Thread.currentThread().name}")
            if (from == "listener") _activity.tryEmit(InputKind.BRAKE)
            val e = synchronized(mapper) { mapper.brake(status, ts) }
            if (e != null) _events.tryEmit(e)
        }
    }

    private fun onSteering(angle: Float?, from: String) {
        tracker.guard(InputKind.STEERING) {
            val ts = clock.nanos()
            Log.i(SIG, "t=$ts item=STEERING value=$angle src=$from thread=${Thread.currentThread().name}")
            if (from == "listener") _activity.tryEmit(InputKind.STEERING)
            val e = synchronized(mapper) { mapper.steering(angle, ts) }
            if (e != null) _events.tryEmit(e)
        }
    }

    private fun onTurn(state: TurnSignalValue?, from: String) {
        tracker.guard(InputKind.TURN_SIGNAL) {
            val ts = clock.nanos()
            Log.i(SIG, "t=$ts item=TURN_SIGNAL value=$state src=$from thread=${Thread.currentThread().name}")
            if (from == "listener") _activity.tryEmit(InputKind.TURN_SIGNAL)
            val dir = when (state) {
                TurnSignalValue.LEFT -> TurnDirection.LEFT
                TurnSignalValue.RIGHT -> TurnDirection.RIGHT
                TurnSignalValue.OFF -> TurnDirection.OFF
                null -> null
            }
            val e = synchronized(mapper) { mapper.turnSignal(dir, ts) }
            if (e != null) _events.tryEmit(e)
        }
    }

    private fun onToggle(kind: InputKind, source: ToggleSource, on: Boolean?, from: String) {
        tracker.guard(kind) {
            val ts = clock.nanos()
            Log.i(SIG, "t=$ts item=$kind value=$on src=$from thread=${Thread.currentThread().name}")
            if (from == "listener") _activity.tryEmit(kind)
            val e = synchronized(mapper) { mapper.toggle(source, on, ts) }
            if (e != null) _events.tryEmit(e)
        }
    }

    private fun onFan(value: Int?, from: String) {
        tracker.guard(InputKind.FAN_SPEED) {
            val ts = clock.nanos()
            Log.i(SIG, "t=$ts item=FAN_SPEED value=$value range=$fanMin..$fanMax src=$from thread=${Thread.currentThread().name}")
            if (from == "listener") _activity.tryEmit(InputKind.FAN_SPEED)
            val e = synchronized(mapper) { mapper.level(LevelSource.FAN_SPEED, value?.toFloat(), fanMin, fanMax, ts) }
            if (e != null) _events.tryEmit(e)
        }
    }

    private fun onPower(on: Boolean?, from: String) {
        tracker.guard(InputKind.HVAC_POWER) {
            Log.i(SIG, "t=${clock.nanos()} item=HVAC_POWER value=$on src=$from thread=${Thread.currentThread().name}")
            if (on != null) _hvacPower.value = on
            if (from == "listener") _activity.tryEmit(InputKind.HVAC_POWER)
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        val v = vehicle ?: return
        // 종료 순서: 모든 unregister → release (콜백 진행 중에는 release하지 않는다)
        fun un(name: String, f: () -> Unit) { try { f() } catch (t: Throwable) { Log.w(TAG, "unregister $name: $t") } }
        un("brake") { if (brakeRegistered) v.getBrake().unregisterBrakePedalStatus(brakeListener) }
        un("turn") { if (turnRegistered) v.getTurnSignal().unregisterTurnSignalState(turnListener) }
        un("steering") { if (steeringRegistered) v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        un("ac") { acArea?.let { v.getHVAC().unregisterHvacAcState(it, acListener) } }
        un("auto") { autoArea?.let { v.getHVAC().unregisterHvacAutoState(it, autoListener) } }
        un("recirc") { recircArea?.let { v.getHVAC().unregisterHvacRecirculationState(it, recircListener) } }
        un("defroster") { defrostArea?.let { v.getHVAC().unregisterHvacDefrosterState(it, defrostListener) } }
        un("power") { powerArea?.let { v.getHVAC().unregisterHvacPowerState(it, powerListener) } }
        un("fan") { fanArea?.let { v.getHVAC().unregisterHvacFanSpeed(it, fanListener) } }
        brakeRegistered = false; turnRegistered = false; steeringRegistered = false
        acArea = null; autoArea = null; recircArea = null; defrostArea = null; powerArea = null; fanArea = null
        try { v.release() } catch (t: Throwable) { Log.w(TAG, "release: $t") }
        vehicle = null
    }

    companion object {
        const val TAG = "PJAM_PLEOS"
        const val SIG = "PJAM_SIG"
        private val ALL_KINDS = listOf(InputKind.BRAKE, InputKind.TURN_SIGNAL, InputKind.STEERING, InputKind.AC, InputKind.AUTO, InputKind.RECIRCULATION, InputKind.DEFROSTER, InputKind.HVAC_POWER, InputKind.FAN_SPEED)
        /** SDK 핸들 각도는 음수 = 왼쪽 (AAOS 정의, FINDINGS V4). Virtual Console 화면으로 눈 확인 전이다. 반대로 보이면 false로 뒤집는다. */
        const val STEERING_LEFT_IS_NEGATIVE = true
    }
}
