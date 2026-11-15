package app.pjam.studio

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.pjam.studio.engine.Clock
import app.pjam.studio.engine.EngineCommand
import app.pjam.studio.engine.EngineRunner
import app.pjam.studio.engine.LoopEngine
import app.pjam.studio.engine.StepLagStats
import app.pjam.studio.input.FakeVehicleInputSource
import app.pjam.studio.input.InputSourceManager
import app.pjam.studio.input.VehicleInputSource
import app.pjam.studio.input.pleos.PleosGearSource
import app.pjam.studio.input.pleos.PleosVehicleInputSource
import app.pjam.studio.input.pleos.SignalProbe
import app.pjam.studio.lock.PlaybackGuard
import app.pjam.studio.sound.AudioFocusController
import app.pjam.studio.sound.SoundPoolSampler

/** 수동 DI. 엔진은 Activity 밖(앱 범위)에서 보유한다. */
class AppContainer(context: Context) {
    val clock = Clock { SystemClock.elapsedRealtimeNanos() }
    val sampler = SoundPoolSampler(context)
    /** 스텝 호출 시각 오차(T0-13/T2-9). 200스텝(100BPM에서 30초)마다 한 줄 요약. */
    val stepLag = StepLagStats()
    private var lagSteps = 0
    val engine = LoopEngine(
        sampler,
        log = { Log.i("PJAM_ENG", it) },
        onStepFired = { step, scheduled ->
            stepLag.onStep(step, SystemClock.elapsedRealtimeNanos() - scheduled)
            if (++lagSteps % 200 == 0) Log.i("PJAM_LAG", stepLag.summary().toString())
        },
    )
    private val runner = EngineRunner(engine, clock) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }
    val focus = AudioFocusController(context) { engine.send(EngineCommand.Stop) }

    /**
     * P단 잠금 + 백그라운드 정지. 기어가 P가 아니게 되거나 Activity가 onStop 되면 엔진을 Stop(녹음 중이면 끝내고 내용 유지)하고
     * 오디오 포커스를 반납한다. P/전경으로 돌아와도 재생은 자동으로 켜지 않는다.
     */
    val guard = PlaybackGuard(
        onStop = { reason ->
            Log.i("PJAM_LOCK", "STOP reason=[$reason]")
            engine.send(EngineCommand.Stop)
            focus.release()
        },
        log = { Log.i("PJAM_LOCK", it) },
    )

    /** 디버그 패널이 쓰는 가짜 입력. 실제 SDK 입력과 병행한다. */
    val fakeInput = FakeVehicleInputSource(clock)

    /**
     * 실제 SDK 입력과 기어 읽기. 만들거나 시작하다 Throwable이 나도 앱은 계속 돌고 화면 조작만 된다 (T1-12).
     */
    /** 디버그 전용 시험 훅: `run-as app.pjam.studio touch files/screen_only`가 있으면 차량 입력 없이(화면 조작만) 시작한다 (7.1 "차량 입력이 하나도 지원되지 않는 상황"). */
    private val forceScreenOnly = BuildConfig.DEBUG && java.io.File(context.filesDir, "screen_only").exists()

    val inputManager = InputSourceManager(
        vehicleFactory = {
            if (forceScreenOnly) throw IllegalStateException("debug flag files/screen_only: vehicle input disabled")
            PleosVehicleInputSource(context, clock)
        },
        gearFactory = { PleosGearSource(context, guard) },
        gearSink = guard,
        log = { Log.w("PJAM_PLEOS", it) },
    )
    val inputSources: List<VehicleInputSource> get() = listOfNotNull(fakeInput, inputManager.vehicleSource)

    init {
        runner.start()
        inputManager.start()
        try { SignalProbe(context).start() } catch (t: Throwable) { Log.w("PJAM_V2", "probe start failed: $t") } // 디버그에서만 동작
    }
}
