package app.pjam.studio.input.pleos

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import app.pjam.studio.lock.GearKind
import app.pjam.studio.lock.GearMethod
import app.pjam.studio.lock.GearSink

/**
 * AAOS 표준 Car API로 기어 신호를 읽는다: CarUxRestrictions(권한 불필요)와 CarPropertyManager(GEAR_SELECTION, 디버그 매니페스트의
 * android.car.permission.CAR_POWERTRAIN이 있을 때만). **Car 라이브러리가 없는 기기에서는 이 클래스를 만드는 순간 NoClassDefFoundError가 난다.**
 * 그래서 PleosGearSource가 try(Throwable) 안에서만 만들고, 실패해도 Vehicle SDK의 Gear 경로는 영향을 받지 않는다.
 * @param sdkHasGear Vehicle SDK가 이미 기어 값을 줬는지. 줬으면 SDK 값을 우선하고 이 클래스는 UX 신호와 로그만 낸다.
 */
class AaosGearSignals(
    private val context: Context,
    private val sink: GearSink,
    private val sdkHasGear: () -> Boolean,
) {
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var uxm: CarUxRestrictionsManager? = null
    @Volatile var uxReady = false
        private set
    @Volatile var cpmGearValue = false
        private set

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        try {
            sink.onUx(r.isRequiresDistractionOptimization, "ux-listener")
            Log.i(TAG, "ux changed requiresDO=${r.isRequiresDistractionOptimization} active=${r.activeRestrictions} thread=${Thread.currentThread().name}")
        } catch (t: Throwable) { Log.w(TAG, "ux callback failed: $t") }
    }

    private val cpmCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            try {
                Log.i(TAG, "cpm event prop=0x${Integer.toHexString(value.propertyId)} value=${value.value} status=${value.status} thread=${Thread.currentThread().name}")
                if (value.propertyId == GEAR_SELECTION && value.status == CarPropertyValue.STATUS_AVAILABLE) onCpmGear(value.value as? Int, "cpm-listener")
            } catch (t: Throwable) { Log.w(TAG, "cpm callback failed: $t") }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(TAG, "cpm error prop=0x${Integer.toHexString(propertyId)} zone=$zone") }
    }

    fun start() {
        Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            try {
                if (ready) { car = c; setupUx(c); setupCpm(c) } else Log.w(TAG, "car service not ready")
            } catch (t: Throwable) { Log.w(TAG, "car ready callback failed: $t") }
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
        if (!sdkHasGear() && kind != GearKind.UNKNOWN) { cpmGearValue = true; sink.onMethod(GearMethod.CAR_PROPERTY); sink.onGear(kind, src) }
    }

    fun stop() {
        try { cpm?.unregisterCallback(cpmCallback) } catch (t: Throwable) { Log.w(TAG, "unregister cpm: $t") }
        try { uxm?.unregisterListener() } catch (t: Throwable) { Log.w(TAG, "unregister ux: $t") }
        try { car?.disconnect() } catch (t: Throwable) { Log.w(TAG, "car disconnect: $t") }
        car = null; cpm = null; uxm = null
    }

    private companion object {
        const val TAG = "PJAM_GEAR"
        const val GEAR_SELECTION = VehiclePropertyIds.GEAR_SELECTION     // 0x11400400
        const val CURRENT_GEAR = VehiclePropertyIds.CURRENT_GEAR         // 0x11400401
        const val VENDOR_GEAR_SELECTION = 0x21400400                     // Virtual Console 카탈로그의 벤더 속성 (CAR_VENDOR_EXTENSION 필요, 읽기 불가)
        const val GEAR_PARK = 4                                          // VehicleGear.GEAR_PARK
    }
}
