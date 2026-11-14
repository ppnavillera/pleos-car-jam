package app.pjam.studio.input

import app.pjam.studio.lock.GearKind
import app.pjam.studio.lock.GearMethod
import app.pjam.studio.lock.GearSink
import app.pjam.studio.lock.GearSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSourceManagerTest {
    private class FakeVehicle(private val onStart: () -> Unit = {}) : VehicleInputSource {
        override val events: SharedFlow<InputEvent> = MutableSharedFlow()
        override val support: StateFlow<Map<InputKind, SupportState>> = MutableStateFlow(mapOf(InputKind.BRAKE to SupportState.UNKNOWN))
        var started = false; var stopped = false
        override fun start() { onStart(); started = true }
        override fun stop() { stopped = true }
    }
    private class FakeGear(private val onStart: () -> Unit = {}) : GearSource {
        var started = false
        override fun start() { onStart(); started = true }
        override fun stop() = Unit
    }
    private class Sink : GearSink {
        val methods = ArrayList<GearMethod>()
        override fun onGear(kind: GearKind, src: String) = Unit
        override fun onUx(restricted: Boolean?, src: String) = Unit
        override fun onMethod(method: GearMethod) { methods += method }
    }

    private fun manager(v: () -> VehicleInputSource, g: () -> GearSource, sink: Sink = Sink()) = InputSourceManager(v, g, sink) to sink

    @Test fun healthySourcesStart() {
        val (m, sink) = manager({ FakeVehicle() }, { FakeGear() })
        m.start()
        assertNotNull(m.vehicleSource); assertTrue((m.vehicleSource as FakeVehicle).started)
        assertEquals(false, m.startFailed.value)
        assertTrue(sink.methods.isEmpty())
    }

    @Test fun sdkInitFailureFallsBackToScreenOnly() {
        for (failure in listOf<Throwable>(IllegalStateException("service not bound"), SecurityException("permission"), RuntimeException("boom"))) {
            val (m, _) = manager({ FakeVehicle { throw failure } }, { FakeGear() })
            m.start() // 예외가 밖으로 나오면 테스트 실패
            assertNull(m.vehicleSource)
            assertEquals(true, m.startFailed.value)
            assertEquals(InputMode.SCREEN_ONLY, InputModes.resolve(emptyMap(), m.startFailed.value))
        }
    }

    @Test fun errorsAreCaughtToo_missingCarLibraryAndNativeFailure() {
        // Car 라이브러리가 없는 기기: 클래스를 만드는 순간 NoClassDefFoundError (Exception이 아니라 Error)
        val (m, sink) = manager(
            { throw NoClassDefFoundError("Landroid/car/Car;") },
            { throw NoClassDefFoundError("Landroid/car/Car;") },
        )
        m.start()
        assertNull(m.vehicleSource)
        assertEquals(true, m.startFailed.value)
        assertEquals(listOf(GearMethod.ASSUMED_PARK), sink.methods) // 기어를 못 읽으면 P로 간주 + 기어 확인 불가
        val (m2, _) = manager({ FakeVehicle { throw UnsatisfiedLinkError("native") } }, { FakeGear() })
        m2.start()
        assertEquals(true, m2.startFailed.value)
    }

    @Test fun gearFailureDoesNotStopVehicleInput() {
        val (m, sink) = manager({ FakeVehicle() }, { FakeGear { throw IllegalStateException("car service") } })
        m.start()
        assertNotNull(m.vehicleSource)
        assertEquals(false, m.startFailed.value)
        assertEquals(listOf(GearMethod.ASSUMED_PARK), sink.methods)
    }

    @Test fun stopIsSafeEvenWhenNothingStartedOrStopThrows() {
        val (m, _) = manager({ throw NoClassDefFoundError("x") }, { throw NoClassDefFoundError("x") })
        m.start(); m.stop()
        val throwingStop = object : VehicleInputSource {
            override val events: SharedFlow<InputEvent> = MutableSharedFlow()
            override val support: StateFlow<Map<InputKind, SupportState>> = MutableStateFlow(emptyMap())
            override fun start() = Unit
            override fun stop() { throw IllegalStateException("release failed") }
        }
        val (m2, _) = manager({ throwingStop }, { FakeGear() })
        m2.start(); m2.stop()
    }

    @Test fun modeResolution() {
        val unknown = mapOf(InputKind.BRAKE to SupportState.UNKNOWN, InputKind.STEERING to SupportState.UNKNOWN)
        assertEquals(InputMode.CHECKING, InputModes.resolve(unknown, false))
        assertEquals(InputMode.VEHICLE, InputModes.resolve(unknown + (InputKind.BRAKE to SupportState.SUPPORTED), false))
        // 일부만 미지원이어도 하나라도 지원되면 차량 모드 (미지원 항목은 화면 조작으로 대체)
        assertEquals(InputMode.VEHICLE, InputModes.resolve(mapOf(InputKind.BRAKE to SupportState.SUPPORTED, InputKind.STEERING to SupportState.UNSUPPORTED), false))
        assertEquals(InputMode.SCREEN_ONLY, InputModes.resolve(mapOf(InputKind.BRAKE to SupportState.FAILED, InputKind.STEERING to SupportState.UNSUPPORTED), false))
        assertEquals(InputMode.SCREEN_ONLY, InputModes.resolve(unknown, true))
    }
}
