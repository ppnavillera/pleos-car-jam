package app.pjam.studio

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.pjam.studio.data.BundledSongs
import app.pjam.studio.data.FileSettingsStore
import app.pjam.studio.data.FileSongRepository
import app.pjam.studio.data.Settings
import app.pjam.studio.data.SongRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 수동 DI. 엔진은 Activity 밖(앱 범위)에서 보유한다. */
class AppContainer(private val context: Context) {
    val clock = Clock { SystemClock.elapsedRealtimeNanos() }
    private val initialSettings = FileSettingsStore(File(context.filesDir, "settings.json")) { Log.w("PJAM_DATA", it) }.load()
    val sampler = SoundPoolSampler(context, initialSettings.soundPackId.takeIf { app.pjam.studio.sound.SoundPacks.isKnown(it) } ?: app.pjam.studio.sound.SoundPacks.DEFAULT)
    /** 스텝 호출 시각 오차(T0-13/T2-9). 200스텝(100BPM에서 30초)마다 한 줄 요약. */
    val stepLag = StepLagStats()
    private var lagSteps = 0
    val engine = LoopEngine(
        sampler,
        log = { Log.i("PJAM_ENG", it) },
        onStepFired = { step, scheduled ->
            val lag = SystemClock.elapsedRealtimeNanos() - scheduled
            stepLag.onStep(step, lag)
            // 10ms 넘게 늦은 스텝은 GC 로그(art)와 맞춰 볼 수 있게 따로 남긴다. 드물게만 찍히므로 오차를 키우지 않는다.
            if (lag > SLOW_STEP_NANOS) Log.w("PJAM_LAG", "slow step=$step lag=${lag / 1_000_000.0}ms")
            if (++lagSteps % 200 == 0) Log.i("PJAM_LAG", stepLag.summary().toString())
        },
    )
    private val runner = EngineRunner(engine, clock) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }
    val focus = AudioFocusController(context) { engine.send(EngineCommand.Stop) }

    /**
     * P단 잠금 + 백그라운드 정지. 기어가 P가 아니게 되거나 Activity가 onStop 되면 엔진을 Stop(녹음 중이면 끝내고 내용 유지)하고
     * 오디오 포커스를 반납한다. 잠금 직전에 재생 중이었다면 P로 돌아올 때 루프 처음부터 재생을 다시 켠다(녹음은 이어 가지 않음).
     * 앱 전환으로 멈춘 경우는 돌아와도 정지 상태다.
     */
    val guard = PlaybackGuard(
        onStop = { reason ->
            Log.i("PJAM_LOCK", "STOP reason=[$reason]")
            engine.send(EngineCommand.Stop)
            focus.release()
        },
        log = { Log.i("PJAM_LOCK", it) },
        isPlaying = { engine.state.value.playing },
        onResume = {
            Log.i("PJAM_LOCK", "RESUME playback")
            focus.acquire()
            engine.send(EngineCommand.Start)
        },
    )

    // ---- 저장·설정 (T3-2, T3-3) ----
    val songs: SongRepository = FileSongRepository(File(context.filesDir, "songs")) { Log.w("PJAM_DATA", it) }
    /** APK에 들어 있는 읽기 전용 예시 곡. 첫 실행 여부와 무관하게 항상 보관함에 보인다. */
    val bundledSongs by lazy { listOfNotNull(BundledSongs.load(context, BundledSongs.EXAMPLE_FILE)) }
    private val settingsStore = FileSettingsStore(File(context.filesDir, "settings.json")) { Log.w("PJAM_DATA", it) }
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings> = _settings

    /** 설정을 바꾸고 바로 저장·적용한다. */
    fun updateSettings(change: (Settings) -> Settings) {
        val next = change(_settings.value).sanitized()
        _settings.value = next
        settingsStore.save(next)
        applySettings(next)
        Log.i("PJAM_DATA", "settings $next")
    }

    private fun applySettings(s: Settings) {
        engine.send(EngineCommand.SetLatencyNanos(s.latencyMs * 1_000_000L))
        sampler.setPack(s.soundPackId)
    }

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
        applySettings(_settings.value)
        startDebugExample(context)
        inputManager.start()
        try { SignalProbe(context).start() } catch (t: Throwable) { Log.w("PJAM_V2", "probe start failed: $t") } // 디버그에서만 동작
    }

    /**
     * 디버그 전용 시험 훅: `run-as app.pjam.studio touch files/load_example` 가 있으면 시작할 때 4트랙이 꽉 찬 예시 곡을 불러와 재생한다
     * (3분 최대 지연 측정과 소리 확인용). release 에서는 동작하지 않는다.
     */
    private fun startDebugExample(context: Context) {
        if (!BuildConfig.DEBUG || !java.io.File(context.filesDir, "load_example").exists()) return
        val song = app.pjam.studio.data.BundledSongs.load(context, app.pjam.studio.data.BundledSongs.EXAMPLE_FILE) ?: return
        Thread({
            while (!sampler.allLoaded) Thread.sleep(50)
            engine.send(EngineCommand.LoadSong(song))
            engine.send(EngineCommand.Start)
            Log.i("PJAM_LAG", "debug example started: ${song.tracks.sumOf { it.notes.size }} notes")
        }, "pjam-debug-example").start()
    }

    private companion object { const val SLOW_STEP_NANOS = 10_000_000L }
}
