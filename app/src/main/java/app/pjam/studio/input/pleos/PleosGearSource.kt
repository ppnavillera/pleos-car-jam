package app.pjam.studio.input.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.pjam.studio.lock.GearKind
import app.pjam.studio.lock.GearMethod
import app.pjam.studio.lock.GearSink
import app.pjam.studio.lock.GearSource

/**
 * 기어 읽기. **Car·CarPropertyManager·CarUxRestrictions와 Vehicle SDK의 Gear는 여기서만 건드리고, 모든 호출을 Throwable로 감싼다**
 * (Car 라이브러리가 없는 기기에서는 NoClassDefFoundError가 난다).
 *
 * 판정 순서 (계획서 3.6을 FINDINGS V3로 조정):
 *  1. Vehicle SDK `Gear.registerGearSelection` (권한 CAR_DRIVE, 이미 선언). 값이 오면 이것을 쓴다.
 *  2. `CarPropertyManager` GEAR_SELECTION(0x11400400) 구독 — 1이 값을 못 주고 이쪽이 읽힐 때만
 *  3. `CarUxRestrictions`만 (기어를 모름, 제한이 걸리면 잠금)
 *  4. 아무것도 안 되면 P로 간주 + "기어 확인 불가" 표시
 * CarUxRestrictions는 어느 방식이든 항상 구독해서 신호와 시각을 로그로 남긴다(V3 측정용).
 * 콜백은 Binder 스레드에서 온다. 시각은 logcat이 찍고, 여기서는 sink로 넘기기만 한다.
 */
class PleosGearSource(
    private val context: Context,
    private val sink: GearSink,
) : GearSource {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    @Volatile private var sdkValue = false
    @Volatile private var cpmValue = false
    private var vehicle: Vehicle? = null
    private var sdkRegistered = false
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var uxm: CarUxRestrictionsManager? = null
    private var uxReady = false
    private var attempt = 0

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onSdkGear(gear, "sdk-listener")
        override fun onFailed(exception: Exception) { Log.w(TAG, "sdk gear onFailed: $exception") }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        sink.onUx(r.isRequiresDistractionOptimization, "ux-listener")
        Log.i(TAG, "ux changed requiresDO=${r.isRequiresDistractionOptimization} active=${r.activeRestrictions} thread=${Thread.currentThread().name}")
    }

    private val cpmCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            Log.i(TAG, "cpm event prop=0x${Integer.toHexString(value.propertyId)} value=${value.value} status=${value.status} thread=${Thread.currentThread().name}")
            if (value.propertyId == GEAR_SELECTION && value.status == CarPropertyValue.STATUS_AVAILABLE) onCpmGear(value.value as? Int, "cpm-listener")
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(TAG, "cpm error prop=0x${Integer.toHexString(propertyId)} zone=$zone") }
    }

    override fun start() {
        if (started) return
        started = true
        connectSdk()
        connectCar()
        main.postDelayed({ decideMethod() }, 3000) // SDK 값이 안 오면 다음 방식으로
    }

    // ---- 1. Vehicle SDK Gear ----
    private fun connectSdk() {
        try {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            trySdk(v)
        } catch (t: Throwable) {
            Log.w(TAG, "sdk gear init failed: $t")
        }
    }

    private fun trySdk(v: Vehicle) {
        try {
            v.getGear().getCurrentGearSelectionState(
                { g -> onSdkGear(g, "sdk-initial") },
                { e -> Log.w(TAG, "sdk gear get failed: $e"); retryLater() },
            )
            if (!sdkRegistered) { v.getGear().registerGearSelection(gearListener); sdkRegistered = true }
        } catch (t: Throwable) {
            Log.w(TAG, "sdk gear failed: $t"); retryLater()
        }
    }

    private fun retryLater() {
        if (!started || attempt >= 4) return
        attempt++
        main.postDelayed({ vehicle?.let { if (!sdkValue) trySdk(it) } }, 1000)
    }

    private fun onSdkGear(g: VehicleGear?, src: String) {
        val kind = when (g) {
            VehicleGear.GEAR_PARK -> GearKind.PARK
            VehicleGear.GEAR_UNKNOWN, null -> GearKind.UNKNOWN
            else -> GearKind.NOT_PARK
        }
        Log.i(TAG, "sdk gear=$g → $kind src=$src thread=${Thread.currentThread().name}")
        if (!sdkValue && kind != GearKind.UNKNOWN) { sdkValue = true; sink.onMethod(GearMethod.SDK_GEAR) }
        if (sdkValue) sink.onGear(kind, src)
    }

    // ---- 2/3. AAOS Car API ----
    private fun connectCar() {
        try {
            Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) {
                    car = c
                    setupUx(c); setupCpm(c)
                } else Log.w(TAG, "car service not ready")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Car API unavailable: $t")
        }
    }

    private fun setupUx(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxm = m
            m.registerListener(uxListener)
            val cur: CarUxRestrictions? = m.currentCarUxRestrictions
            sink.onUx(cur?.isRequiresDistractionOptimization, "ux-initial")
            uxReady = true
            Log.i(TAG, "ux initial requiresDO=${cur?.isRequiresDistractionOptimization}")
        } catch (t: Throwable) {
            Log.w(TAG, "ux setup failed: $t")
        }
    }

    private fun setupCpm(c: Car) {
        try {
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            cpm = m
            // 읽기 가능 여부 탐사: 권한이 없으면 SecurityException (FINDINGS V3)
            for (id in listOf(GEAR_SELECTION, CURRENT_GEAR, VENDOR_GEAR_SELECTION)) {
                try {
                    val v = m.getIntProperty(id, 0)
                    Log.i(TAG, "cpm probe prop=0x${Integer.toHexString(id)} READ OK value=$v")
                } catch (t: Throwable) {
                    Log.i(TAG, "cpm probe prop=0x${Integer.toHexString(id)} READ FAIL ${t.javaClass.simpleName}: ${t.message?.take(160)}")
                }
            }
            val ok = m.registerCallback(cpmCallback, GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            Log.i(TAG, "cpm register GEAR_SELECTION → $ok")
        } catch (t: Throwable) {
            Log.w(TAG, "cpm setup failed: $t")
        }
    }

    private fun onCpmGear(v: Int?, src: String) {
        val kind = when (v) { null -> GearKind.UNKNOWN; GEAR_PARK -> GearKind.PARK; 0 -> GearKind.UNKNOWN; else -> GearKind.NOT_PARK }
        Log.i(TAG, "cpm gear=$v → $kind src=$src")
        // SDK 값이 있으면 SDK를 우선한다. 없을 때만 이 경로가 판정한다.
        if (!sdkValue && kind != GearKind.UNKNOWN) { cpmValue = true; sink.onMethod(GearMethod.CAR_PROPERTY); sink.onGear(kind, src) }
    }

    private fun decideMethod() {
        if (!started || sdkValue || cpmValue) return
        val m = if (uxReady) GearMethod.UX_RESTRICTIONS else GearMethod.ASSUMED_PARK
        Log.i(TAG, "gear unreadable → method=$m")
        sink.onMethod(m)
    }

    override fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        // 종료 순서: 모든 unregister → release
        try { if (sdkRegistered) vehicle?.getGear()?.unregisterGearSelection(gearListener) } catch (t: Throwable) { Log.w(TAG, "unregister gear: $t") }
        sdkRegistered = false
        try { cpm?.unregisterCallback(cpmCallback) } catch (t: Throwable) { Log.w(TAG, "unregister cpm: $t") }
        try { uxm?.unregisterListener() } catch (t: Throwable) { Log.w(TAG, "unregister ux: $t") }
        try { vehicle?.release() } catch (t: Throwable) { Log.w(TAG, "release: $t") }
        try { car?.disconnect() } catch (t: Throwable) { Log.w(TAG, "car disconnect: $t") }
        vehicle = null; car = null; cpm = null; uxm = null
    }

    companion object {
        const val TAG = "PJAM_GEAR"
        const val GEAR_SELECTION = VehiclePropertyIds.GEAR_SELECTION     // 0x11400400
        const val CURRENT_GEAR = VehiclePropertyIds.CURRENT_GEAR         // 0x11400401
        const val VENDOR_GEAR_SELECTION = 0x21400400                     // Virtual Console 카탈로그의 벤더 속성
        private const val GEAR_PARK = 4                                  // VehicleGear.GEAR_PARK
    }
}
