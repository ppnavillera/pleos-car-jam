package app.pjam.studio.engine

import app.pjam.studio.sound.Sampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface EngineCommand {
    /** 연주 입력. 즉시 소리(모니터링)를 내고, 녹음 중이면 timestamp를 퀀타이즈해 기록한다. */
    data class Play(val sampleId: String, val timestampNanos: Long, val velocity: Float = 1f) : EngineCommand
    /** 녹음 버튼. 같은 트랙이 녹음/대기 중이면 취소·확정, 아니면 그 트랙에 녹음을 건다. */
    data class ToggleRecord(val kind: TrackKind) : EngineCommand
    data object TogglePlay : EngineCommand
    data object Stop : EngineCommand
    data class ClearTrack(val kind: TrackKind) : EngineCommand
    data object ClearAll : EngineCommand
    /** 다음 스텝 경계에서 적용된다. */
    data class SetMuted(val kind: TrackKind, val muted: Boolean) : EngineCommand
    data class SetBpm(val bpm: Int) : EngineCommand
    data class SetLatencyNanos(val nanos: Long) : EngineCommand
    data class LoadSong(val song: Song) : EngineCommand
}

enum class TrackStatus { EMPTY, ARMED, RECORDING, PLAYING, MUTED }

data class TrackState(val kind: TrackKind, val notes: List<Note>, val muted: Boolean, val status: TrackStatus)

/** UI에 내보내는 불변 스냅샷. step은 마지막으로 울린 루프 위치(0..31), 멈춤이면 -1. */
data class EngineState(
    val playing: Boolean = false,
    val bpm: Int = DEFAULT_BPM,
    val step: Int = -1,
    val countInBeat: Int = 0, // 0 = 카운트인 아님, 1..4
    val recordingKind: TrackKind? = null,
    val tracks: List<TrackState> = TrackKind.entries.map { TrackState(it, emptyList(), false, TrackStatus.EMPTY) },
    val loopCount: Long = 0,
)

/**
 * 루프 엔진. Android 의존 없음. 시계·Sampler는 주입한다.
 * 모든 상태는 엔진 스레드(= tick을 부르는 스레드) 하나만 바꾸고, 바깥은 send()로 명령을 보낸다.
 * tick(now): 명령 처리 → 도래한 스텝 처리. 테스트에서는 가짜 시계로 tick을 직접 부른다.
 */
class LoopEngine(
    private val sampler: Sampler,
    private val log: (String) -> Unit = {},
) {
    private sealed interface Phase {
        data object Idle : Phase
        /** 카운트인 또는 다음 루프 시작 대기. startStep에서 녹음 시작. */
        class Armed(val kind: TrackKind, val startStep: Long, val startedTransport: Boolean) : Phase {
            val early = ArrayList<Note>()
        }
        class Recording(val kind: TrackKind, val startStep: Long, val endStep: Long, val loopStartNanos: Long) : Phase
    }

    private val queue = ConcurrentLinkedQueue<EngineCommand>()
    /** 명령이 들어오면 엔진 스레드를 깨우는 훅 (EngineRunner가 설정). */
    @Volatile var onWake: (() -> Unit)? = null

    private val transport = Transport()
    private val notes = TrackKind.entries.associateWith { ArrayList<Note>() }
    private val mutedRequested = TrackKind.entries.associateWith { false }.toMutableMap()
    private val mutedApplied = TrackKind.entries.associateWith { false }.toMutableMap()

    private var phase: Phase = Phase.Idle
    private var origin = 0L // 루프 위치 0이 되는 절대 스텝 (mod 32)
    private var bpm = DEFAULT_BPM
    private var latencyNanos = 0L
    private var lastLoopPos = -1
    private var countInBeat = 0
    private var loopCount = 0L

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    fun send(cmd: EngineCommand) {
        queue.add(cmd)
        onWake?.invoke()
    }

    /** 다음에 깨어나야 할 시각(ns). 멈춰 있으면 Long.MAX_VALUE. */
    fun nextWakeNanos(): Long = transport.nextStepTime()

    fun tick(now: Long) {
        while (true) {
            val cmd = queue.poll() ?: break
            handle(cmd, now)
        }
        transport.advanceTo(now) { n, t -> onStep(n, t) }
        publish()
    }

    // ---------------------------------------------------------------- 명령

    private fun handle(cmd: EngineCommand, now: Long) {
        when (cmd) {
            is EngineCommand.Play -> onPlay(cmd)
            is EngineCommand.ToggleRecord -> toggleRecord(cmd.kind, now)
            EngineCommand.TogglePlay -> if (transport.running) stopTransport() else startPlayback(now)
            EngineCommand.Stop -> if (transport.running) stopTransport()
            is EngineCommand.ClearTrack -> notes.getValue(cmd.kind).clear()
            EngineCommand.ClearAll -> notes.values.forEach { it.clear() }
            is EngineCommand.SetMuted -> mutedRequested[cmd.kind] = cmd.muted
            is EngineCommand.SetBpm -> {
                bpm = cmd.bpm.coerceIn(MIN_BPM, MAX_BPM)
                transport.setStepNanos(stepNanosForBpm(bpm))
            }
            is EngineCommand.SetLatencyNanos -> latencyNanos = cmd.nanos
            is EngineCommand.LoadSong -> loadSong(cmd.song)
        }
    }

    private fun loadSong(song: Song) {
        if (transport.running) stopTransport()
        for (t in song.tracks) {
            notes.getValue(t.kind).apply { clear(); addAll(t.notes) }
            mutedRequested[t.kind] = t.muted
            mutedApplied[t.kind] = t.muted
        }
        bpm = song.bpm.coerceIn(MIN_BPM, MAX_BPM)
    }

    private fun startPlayback(now: Long) {
        origin = 0
        transport.start(now, stepNanosForBpm(bpm), 0)
        lastLoopPos = -1
        log("PLAY start")
    }

    private fun stopTransport() {
        transport.stop()
        phase = Phase.Idle // 녹음 중이면 그때까지 찍힌 내용 유지, 대기 중이면 취소
        sampler.stopAll()
        lastLoopPos = -1
        countInBeat = 0
        log("STOP")
    }

    private fun onPlay(cmd: EngineCommand.Play) {
        sampler.play(cmd.sampleId, cmd.velocity) // 모니터링: 퀀타이즈와 무관하게 즉시
        val ph = phase
        val half = transport.stepNanos / 2
        when (ph) {
            is Phase.Recording -> {
                val endTime = transport.timeOfStep(ph.endStep)
                if (cmd.timestampNanos >= ph.loopStartNanos - half && cmd.timestampNanos < endTime) {
                    val step = Quantizer.quantize(cmd.timestampNanos, ph.loopStartNanos, transport.stepNanos, latencyNanos)
                    addNote(ph.kind, Note(step, cmd.sampleId, cmd.velocity))
                }
            }
            is Phase.Armed -> {
                // 시작점보다 반 스텝 이내로 이른 입력은 스텝 0으로
                if (cmd.timestampNanos >= transport.timeOfStep(ph.startStep) - half) {
                    ph.early += Note(0, cmd.sampleId, cmd.velocity)
                }
            }
            Phase.Idle -> Unit
        }
    }

    private fun addNote(kind: TrackKind, note: Note) {
        val list = notes.getValue(kind)
        if (list.none { it.step == note.step && it.sampleId == note.sampleId }) list += note
    }

    private fun toggleRecord(kind: TrackKind, now: Long) {
        val ph = phase
        // 같은 트랙을 다시 누름: 녹음 중이면 확정, 대기 중이면 취소
        if (ph is Phase.Recording && ph.kind == kind) { phase = Phase.Idle; log("REC finish(manual) $kind"); return }
        if (ph is Phase.Armed && ph.kind == kind) {
            phase = Phase.Idle
            if (ph.startedTransport) stopTransport()
            log("REC cancel $kind")
            return
        }
        // 다른 트랙: 현재 녹음은 확정/취소하고 새로 건다
        if (ph is Phase.Armed && ph.startedTransport) { transport.stop(); sampler.stopAll() }
        phase = Phase.Idle

        if (!transport.running) {
            // 정지 상태: 재생 시작 → 1마디 카운트인 → 2마디 녹음. 루프 위치 0 = 절대 스텝 16
            transport.start(now, stepNanosForBpm(bpm), 0)
            origin = STEPS_PER_BAR.toLong()
            phase = Phase.Armed(kind, startStep = origin, startedTransport = true)
            lastLoopPos = -1
        } else {
            // 루프가 도는 중: 다음 루프 시작점에서 녹음. 그 직전 1마디는 메트로놈. 남은 시간이 1마디 미만이면 한 바퀴 더.
            val nn = transport.nextStep
            val s = origin + Math.floorDiv(nn + STEPS_PER_BAR - origin + STEPS_PER_LOOP - 1, STEPS_PER_LOOP.toLong()) * STEPS_PER_LOOP
            phase = Phase.Armed(kind, startStep = s, startedTransport = false)
        }
        log("REC armed $kind start=${(phase as Phase.Armed).startStep}")
    }

    // ---------------------------------------------------------------- 스텝

    private fun onStep(n: Long, scheduledNanos: Long) {
        // 뮤트는 스텝 경계에서 적용
        for (k in TrackKind.entries) mutedApplied[k] = mutedRequested.getValue(k)

        val ph = phase
        if (ph is Phase.Armed && n == ph.startStep) {
            val list = notes.getValue(ph.kind)
            list.clear() // 덮어쓰기: 녹음이 시작되는 순간 기존 노트를 지운다
            for (e in ph.early) addNote(ph.kind, e)
            phase = Phase.Recording(ph.kind, n, n + STEPS_PER_LOOP, scheduledNanos)
            countInBeat = 0
            log("REC start ${ph.kind} step=$n")
        } else if (ph is Phase.Recording && n == ph.endStep) {
            phase = Phase.Idle
            log("REC end ${ph.kind} notes=${notes.getValue(ph.kind).size}")
        }

        val cur = phase
        // 메트로놈: 녹음 시작 직전 1마디
        if (cur is Phase.Armed) {
            val rel = n - (cur.startStep - STEPS_PER_BAR)
            if (rel in 0 until STEPS_PER_BAR && rel % 4 == 0L) {
                val beat = (rel / 4).toInt() + 1
                countInBeat = beat
                sampler.play(if (beat == 1) "metro_hi" else "metro_lo")
            }
        }

        val pos = Math.floorMod(n - origin, STEPS_PER_LOOP.toLong()).toInt()
        lastLoopPos = pos
        if (pos == 0) { loopCount++; log("LOOP wrap n=$n") }
        for (k in TrackKind.entries) {
            if (mutedApplied.getValue(k)) continue
            if (cur is Phase.Recording && cur.kind == k) continue // 녹음 중인 트랙은 모니터링만(이중 발음 방지)
            for (note in notes.getValue(k)) if (note.step == pos) sampler.play(note.sampleId, note.velocity)
        }
    }

    // ---------------------------------------------------------------- 스냅샷

    private fun publish() {
        val ph = phase
        val tracks = TrackKind.entries.map { k ->
            val list = notes.getValue(k)
            val muted = mutedRequested.getValue(k)
            val status = when {
                ph is Phase.Recording && ph.kind == k -> TrackStatus.RECORDING
                ph is Phase.Armed && ph.kind == k -> TrackStatus.ARMED
                muted && list.isNotEmpty() -> TrackStatus.MUTED
                list.isEmpty() -> TrackStatus.EMPTY
                else -> TrackStatus.PLAYING
            }
            TrackState(k, list.sortedBy { it.step }, muted, status)
        }
        _state.value = EngineState(
            playing = transport.running,
            bpm = bpm,
            step = if (transport.running) lastLoopPos else -1,
            countInBeat = if (ph is Phase.Armed) countInBeat else 0,
            recordingKind = (ph as? Phase.Recording)?.kind ?: (ph as? Phase.Armed)?.kind,
            tracks = tracks,
            loopCount = loopCount,
        )
    }
}
