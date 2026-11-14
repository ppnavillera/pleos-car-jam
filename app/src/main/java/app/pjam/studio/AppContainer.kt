package app.pjam.studio

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.pjam.studio.engine.Clock
import app.pjam.studio.engine.EngineCommand
import app.pjam.studio.engine.EngineRunner
import app.pjam.studio.engine.LoopEngine
import app.pjam.studio.input.FakeVehicleInputSource
import app.pjam.studio.input.VehicleInputSource
import app.pjam.studio.sound.AudioFocusController
import app.pjam.studio.sound.SoundPoolSampler

/** 수동 DI. 엔진은 Activity 밖(앱 범위)에서 보유한다. */
class AppContainer(context: Context) {
    val clock = Clock { SystemClock.elapsedRealtimeNanos() }
    val sampler = SoundPoolSampler(context)
    val engine = LoopEngine(sampler) { Log.i("PJAM_ENG", it) }
    private val runner = EngineRunner(engine, clock) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }
    val focus = AudioFocusController(context) { engine.send(EngineCommand.Stop) }

    /** 디버그 패널이 쓰는 가짜 입력. P7에서 실제 SDK 입력 소스를 병행한다. */
    val fakeInput = FakeVehicleInputSource(clock)
    val inputSources: List<VehicleInputSource> = listOf(fakeInput)

    init { runner.start() }
}
