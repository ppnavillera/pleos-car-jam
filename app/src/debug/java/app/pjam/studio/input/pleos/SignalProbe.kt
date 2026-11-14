package app.pjam.studio.input.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacAutoStateListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 디버그 전용 신호 로그 (계획서 T0-6, V2 점검). 14개 항목을 capability → 조회 → 구독해서 결과를 logcat에 남긴다.
 *   PJAM_V2  item=<항목> cap=<접근 유형·범위> / get=<값> / reg=ok|FAIL / <단계> FAIL <예외>
 *   PJAM_SIG item=<항목> value=<값> t=<elapsedRealtimeNanos> thread=<스레드>   (값이 바뀔 때마다)
 * 이 프로브가 쓰는 권한(CAR_CLIMATE, CAR_WINDOWS, CAR_ENERGY)은 디버그 매니페스트에만 있다. **SDK 호출은 이 패키지에만 둔다.**
 */
class SignalProbe(private val context: Context) {
    private var vehicle: Vehicle? = null
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val frontGlass = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val winLeft = WindowArea(WindowValue.ROW_1_LEFT)
    private val winRight = WindowArea(WindowValue.ROW_1_RIGHT)

    fun start() {
        try {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            Handler(Looper.getMainLooper()).postDelayed({ runAll(v) }, 1500)
        } catch (t: Throwable) { Log.w(V2, "probe init failed: $t") }
    }

    private fun v2(item: String, msg: String) { Log.i(V2, "item=$item $msg") }
    private fun sig(item: String, value: Any?) {
        Log.i(SIG, "item=$item value=$value t=${SystemClock.elapsedRealtimeNanos()} thread=${Thread.currentThread().name}")
    }
    private fun fail(item: String, step: String, e: Throwable) { Log.i(V2, "item=$item $step FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
    private inline fun probe(item: String, body: () -> Unit) { try { body() } catch (t: Throwable) { fail(item, "call", t) } }
    private fun get(item: String) = { x: Any? -> v2(item, "get=$x") }
    private fun gf(item: String) = { e: Exception -> fail(item, "get", e) }
    private fun regOk(item: String) = v2(item, "reg=ok")

    private fun runAll(v: Vehicle) {
        steering(v); hvac(v); window(v); drivingMode(v); wiper(v); ev(v); ambient(v)
    }

    private fun steering(v: Vehicle) = probe("STEERING") {
        val s = v.getSteeringWheel()
        s.checkSteeringWheelAngleCapabilities({ cfg, b -> v2("STEERING", "cap=${cfg.accessType} range=${b.minValue}..${b.maxValue}") }, { e -> fail("STEERING", "cap", e) })
        s.getCurrentSteeringWheelAngle({ x -> v2("STEERING", "get=$x") }, gf("STEERING"))
        s.registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = sig("STEERING", angle)
            override fun onFailed(exception: Exception) = fail("STEERING", "listener", exception)
        }); regOk("STEERING")
    }

    private fun hvac(v: Vehicle) {
        val h = v.getHVAC()
        probe("AC") {
            h.checkHvacAcCapabilities({ cfg, areas ->
                v2("AC", "cap=${cfg.accessType} areas=" + areas.joinToString { it.values.joinToString("+") })
                val a = areas.firstOrNull() ?: return@checkHvacAcCapabilities
                h.getHvacAcState(a, { x -> v2("AC", "get=$x") }, gf("AC"))
                h.registerHvacAcState(a, object : HvacAcStateListener {
                    override fun onHvacAcStateUpdated(state: Boolean?) = sig("AC", state)
                    override fun onFailed(exception: Exception) = fail("AC", "listener", exception)
                }); regOk("AC")
            }, { e -> fail("AC", "cap", e) })
        }
        probe("AUTO") {
            h.checkHvacAutoCapabilities({ cfg, areas ->
                v2("AUTO", "cap=${cfg.accessType} areas=" + areas.joinToString { it.values.joinToString("+") })
                val a = areas.firstOrNull() ?: return@checkHvacAutoCapabilities
                h.getHvacAutoState(a, { x -> v2("AUTO", "get=$x") }, gf("AUTO"))
                h.registerHvacAutoState(a, object : HvacAutoStateListener {
                    override fun onHvacAutoStateUpdated(state: Boolean?) = sig("AUTO", state)
                    override fun onFailed(exception: Exception) = fail("AUTO", "listener", exception)
                }); regOk("AUTO")
            }, { e -> fail("AUTO", "cap", e) })
        }
        probe("RECIRC") {
            h.checkHvacRecirculationCapabilities({ cfg, areas ->
                v2("RECIRC", "cap=${cfg.accessType} areas=" + areas.joinToString { it.values.joinToString("+") })
                val a = areas.firstOrNull() ?: return@checkHvacRecirculationCapabilities
                h.getHvacRecirculationState(a, { x -> v2("RECIRC", "get=$x") }, gf("RECIRC"))
                h.registerHvacRecirculationState(a, object : HvacRecirculationStateListener {
                    override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) = sig("RECIRC", state)
                    override fun onFailed(exception: Exception) = fail("RECIRC", "listener", exception)
                }); regOk("RECIRC")
            }, { e -> fail("RECIRC", "cap", e) })
        }
        probe("DEFROSTER") {
            h.checkHvacDefrosterCapabilities({ cfg, areas -> v2("DEFROSTER", "cap=${cfg.accessType} areas=${areas.size}") }, { e -> fail("DEFROSTER", "cap", e) })
            h.getHvacDefrosterState(frontGlass, { x -> v2("DEFROSTER", "get(front)=$x") }, gf("DEFROSTER"))
            h.registerHvacDefrosterState(frontGlass, object : HvacDefrosterStateListener {
                override fun onHvacDefrosterStateUpdated(state: Boolean?) = sig("DEFROSTER", state)
                override fun onFailed(exception: Exception) = fail("DEFROSTER", "listener", exception)
            }); regOk("DEFROSTER")
        }
        probe("FAN_SPEED") {
            h.checkHvacFanSpeedCapabilities({ cfg, m ->
                v2("FAN_SPEED", "cap=${cfg.accessType} " + m.entries.joinToString { "${it.key.values.joinToString("+")}:${it.value.minValue}..${it.value.maxValue}" })
                val a = m.keys.firstOrNull() ?: return@checkHvacFanSpeedCapabilities
                h.getHvacFanSpeed(a, { x -> v2("FAN_SPEED", "get=$x") }, gf("FAN_SPEED"))
                h.registerHvacFanSpeed(a, object : HvacFanSpeedListener {
                    override fun onHvacFanSpeedUpdated(speed: Int?) = sig("FAN_SPEED", speed)
                    override fun onFailed(exception: Exception) = fail("FAN_SPEED", "listener", exception)
                }); regOk("FAN_SPEED")
            }, { e -> fail("FAN_SPEED", "cap", e) })
        }
        probe("TEMPERATURE") {
            h.checkHvacTemperatureCapabilities({ cfg, m -> v2("TEMPERATURE", "cap=${cfg.accessType} ranges=" + m.values.joinToString { "${it.minValue}..${it.maxValue}" }) }, { e -> fail("TEMPERATURE", "cap", e) })
            h.getHvacTemperature(driver, TemperatureUnit.CELSIUS, { x -> v2("TEMPERATURE", "get=$x") }, gf("TEMPERATURE"))
            h.registerHvacTemperature(driver, object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) = sig("TEMPERATURE", temperature)
                override fun onFailed(exception: Exception) = fail("TEMPERATURE", "listener", exception)
            }); regOk("TEMPERATURE")
        }
    }

    private fun window(v: Vehicle) = probe("WINDOW") {
        val w = v.getWindow()
        w.checkWindowPositionCapabilities({ cfg, m -> v2("WINDOW", "cap=${cfg.accessType} ranges=" + m.entries.joinToString { "${it.key.values.joinToString()}:${it.value.minValue}..${it.value.maxValue}" }) }, { e -> fail("WINDOW", "cap", e) })
        for ((name, area) in listOf("FL" to winLeft, "FR" to winRight)) {
            w.getCurrentWindowPosition(area, { x -> v2("WINDOW", "get($name)=$x") }, gf("WINDOW"))
            w.registerWindowPosition(area, object : WindowPositionListener {
                override fun onWindowPositionUpdated(position: Int?) = sig("WINDOW_$name", position)
                override fun onFailed(exception: Exception) = fail("WINDOW", "listener($name)", exception)
            })
        }
        regOk("WINDOW")
    }

    private fun drivingMode(v: Vehicle) = probe("DRIVE_MODE") {
        val d = v.getDrivingMode()
        d.checkDrivingModeStateCapabilities({ cfg -> v2("DRIVE_MODE", "cap=${cfg.accessType}") }, { e -> fail("DRIVE_MODE", "cap", e) })
        d.getCurrentDrivingModeState({ x: DrivingModeType? -> v2("DRIVE_MODE", "get=$x") }, gf("DRIVE_MODE"))
        d.registerDrivingModeState(object : DrivingModeStateListener {
            override fun onDrivingModeStateUpdated(mode: DrivingModeType?) = sig("DRIVE_MODE", mode)
            override fun onFailed(exception: Exception) = fail("DRIVE_MODE", "listener", exception)
        }); regOk("DRIVE_MODE")
    }

    private fun wiper(v: Vehicle) = probe("WIPER") {
        val w = v.getWiper()
        w.checkWiperStateCapabilities({ cfg, m -> v2("WIPER", "state cap=${cfg.accessType} areas=${m.size}") }, { e -> fail("WIPER", "cap(state)", e) })
        w.checkWiperSwitchCapabilities({ cfg, m -> v2("WIPER", "switch cap=${cfg.accessType} areas=${m.size}") }, { e -> fail("WIPER", "cap(switch)", e) })
        w.getCurrentWiperState(frontGlass, { x: WiperState? -> v2("WIPER", "getState(front)=$x") }, gf("WIPER"))
        w.getCurrentWiperSwitch(frontGlass, { x -> v2("WIPER", "getSwitch(front)=$x") }, gf("WIPER"))
        w.registerWiperState(frontGlass, object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = sig("WIPER", state)
            override fun onFailed(exception: Exception) = fail("WIPER", "listener", exception)
        }); regOk("WIPER")
    }

    private fun ev(v: Vehicle) = probe("EV_CHARGE") {
        val e = v.getEvBattery()
        e.checkEvChargeSwitchCapabilities({ cfg -> v2("EV_CHARGE", "switch cap=${cfg.accessType}") }, { x -> fail("EV_CHARGE", "cap(switch)", x) })
        e.checkEvChargePortOpenCapabilities({ cfg -> v2("EV_CHARGE", "port cap=${cfg.accessType}") }, { x -> fail("EV_CHARGE", "cap(port)", x) })
        e.getEvChargeSwitchState({ x -> v2("EV_CHARGE", "getSwitch=$x") }, gf("EV_CHARGE"))
        e.registerEvChargePortOpen(object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(open: Boolean?) = sig("EV_CHARGE_PORT", open)
            override fun onFailed(exception: Exception) = fail("EV_CHARGE", "listener(port)", exception)
        }); regOk("EV_CHARGE")
    }

    private fun ambient(v: Vehicle) = probe("AMBIENT") {
        val l = v.getLight()
        l.checkAmbientLightBrightnessCapabilities({ cfg, m ->
            v2("AMBIENT", "cap=${cfg.accessType} " + m.entries.joinToString { "${it.key.values.joinToString("+")}:${it.value.minValue}..${it.value.maxValue}" })
            val a = m.keys.firstOrNull() ?: return@checkAmbientLightBrightnessCapabilities
            l.getAmbientLightBrightness(a, { x -> v2("AMBIENT", "get=$x") }, gf("AMBIENT"))
            l.registerAmbientLightBrightnessListener(a, object : AmbientLightBrightnessListener {
                override fun onBrightnessUpdated(brightness: Int?) = sig("AMBIENT", brightness)
                override fun onFailed(exception: Exception) = fail("AMBIENT", "listener", exception)
            }); regOk("AMBIENT")
        }, { e -> fail("AMBIENT", "cap", e) })
    }

    companion object {
        const val V2 = "PJAM_V2"
        const val SIG = "PJAM_SIG"
    }
}
