package app.pjam.studio.input.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
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
 *  2. `CarPropertyManager` GEAR_SELECTION(0x11400400) 구독 (AaosGearSignals) — 1이 값을 못 주고 이쪽이 읽힐 때만
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
    private var vehicle: Vehicle? = null
    private var sdkRegistered = false
    /** AAOS Car API 부분. Car 라이브러리가 없으면 만드는 순간 NoClassDefFoundError가 나므로 try 안에서만 만든다(SDK Gear 경로는 살아남는다). */
    private var aaos: AaosGearSignals? = null
    private var attempt = 0

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onSdkGear(gear, "sdk-listener")
        override fun onFailed(exception: Exception) { Log.w(TAG, "sdk gear onFailed: $exception") }
    }

    override fun start() {
        if (started) return
        started = true
        connectSdk()
        try {
            aaos = AaosGearSignals(context, sink) { sdkValue }.also { it.start() }
        } catch (t: Throwable) {
            Log.w(TAG, "Car API unavailable (SDK gear still works): $t")
        }
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

    /** Binder 스레드 콜백. 본문에서 예외가 나도 프로세스가 죽지 않게 감싼다(T2-9). 마지막으로 알던 상태는 그대로 둔다. */
    private fun onSdkGear(g: VehicleGear?, src: String) {
        try {
            val kind = when (g) {
                VehicleGear.GEAR_PARK -> GearKind.PARK
                VehicleGear.GEAR_UNKNOWN, null -> GearKind.UNKNOWN
                else -> GearKind.NOT_PARK
            }
            Log.i(TAG, "sdk gear=$g → $kind src=$src thread=${Thread.currentThread().name}")
            if (!sdkValue && kind != GearKind.UNKNOWN) { sdkValue = true; sink.onMethod(GearMethod.SDK_GEAR) }
            if (sdkValue) sink.onGear(kind, src)
        } catch (t: Throwable) {
            Log.w(TAG, "sdk gear callback failed: $t")
        }
    }

    private fun decideMethod() {
        val a = aaos
        if (!started || sdkValue || (a?.cpmGearValue == true)) return
        val m = if (a?.uxReady == true) GearMethod.UX_RESTRICTIONS else GearMethod.ASSUMED_PARK
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
        try { aaos?.stop() } catch (t: Throwable) { Log.w(TAG, "aaos stop: $t") }
        try { vehicle?.release() } catch (t: Throwable) { Log.w(TAG, "release: $t") }
        vehicle = null; aaos = null
    }

    companion object {
        const val TAG = "PJAM_GEAR"
    }
}
