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
        onStepFired = { scheduled ->
            stepLag.add(SystemClock.elapsedRealtimeNanos() - scheduled)
            if (++lagSteps % 200 == 0) Log.i("PJAM_LAG", stepLag.summary().toString())
        },
    )
    private val runner = EngineRunner(engine, clock) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }
    val focus = AudioFocusController(context) { engine.send(EngineCommand.Stop) }

    /**
     * P단 잠금 + 백그라운드 정지. 기어가 P가 아니게 되거나 Activity가 onPause 되면 엔진을 Stop(녹음 중이면 끝내고 내용 유지)하고
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
    val gearSource = PleosGearSource(context, guard)

    /** 디버그 패널이 쓰는 가짜 입력. P7에서 실제 SDK 입력 소스를 병행한다. */
    val fakeInput = FakeVehicleInputSource(clock)
    /** 실제 SDK 입력. 실패해도 앱은 화면 조작만으로 동작한다. */
    val pleosInput = PleosVehicleInputSource(context, clock)
    val inputSources: List<VehicleInputSource> = listOf(fakeInput, pleosInput)

    init {
        runner.start()
        try { pleosInput.start() } catch (t: Throwable) { Log.w("PJAM_PLEOS", "start failed: $t") }
        try { SignalProbe(context).start() } catch (t: Throwable) { Log.w("PJAM_V2", "probe start failed: $t") } // 디버그에서만 동작
        try { gearSource.start() } catch (t: Throwable) { Log.w("PJAM_GEAR", "start failed: $t") }
    }
}
