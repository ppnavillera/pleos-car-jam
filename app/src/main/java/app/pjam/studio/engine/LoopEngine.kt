package app.pjam.studio.engine

import app.pjam.studio.sound.Sampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

sealed interface EngineCommand {
    /** 연주 입력. 즉시 소리(모니터링)를 내고, 녹음 중이면 timestamp를 퀀타이즈해 기록한다. */
    data class Play(val sampleId: String, val timestampNanos: Long, val velocity: Float = 1f) : EngineCommand
    /**
     * 녹음 버튼. 같은 트랙이 녹음 중이면 끝내고(내용 유지), 대기 중이면 취소, 아니면 그 트랙에 녹음을 건다.
     * 녹음은 이 버튼을 다시 누르거나 FinishRecord/Stop이 올 때까지 계속된다 (PLAN_CHANGES 2026-11-15).
     */
    data class ToggleRecord(val kind: TrackKind) : EngineCommand
    /** 녹음 중이면 끝내고 내용을 유지, 대기 중이면 취소. 트랙 선택 변경이나 기어 이탈 때 쓴다. 재생은 그대로 둔다. */
    data object FinishRecord : EngineCommand
    data object TogglePlay : EngineCommand
    /** 멈춰 있을 때만 루프 처음부터 재생을 켠다. 이미 돌고 있으면 아무것도 하지 않는다(토글이 아니라서 경합에도 정지시키지 않는다). */
    data object Start : EngineCommand
    data object Stop : EngineCommand
    data class ClearTrack(val kind: TrackKind) : EngineCommand
    data object ClearAll : EngineCommand
    /** 다음 스텝 경계에서 적용된다. */
    data class SetMuted(val kind: TrackKind, val muted: Boolean) : EngineCommand
    data class SetBpm(val bpm: Int) : EngineCommand
    /** 조(키): BASS·MELODY 소리에만 반음 단위로 적용된다(−3..+3). 드럼·FX·메트로놈은 그대로. */
    data class SetTranspose(val semitones: Int) : EngineCommand
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
    val transpose: Int = 0,
)

/**
 * 루프 엔진. Android 의존 없음. 시계·Sampler는 주입한다.
 * 모든 상태는 엔진 스레드(= tick을 부르는 스레드) 하나만 바꾸고, 바깥은 send()로 명령을 보낸다.
 * tick(now): 명령 처리 → 도래한 스텝 처리. 테스트에서는 가짜 시계로 tick을 직접 부른다.
 */
class LoopEngine(
    private val sampler: Sampler,
    private val log: (String) -> Unit = {},
    /** 스텝이 울리기 직전 호출된다(절대 스텝 번호와 예정 시각 전달). 지연·누락 측정용. 엔진 스레드에서 불린다. */
    private val onStepFired: ((step: Long, scheduledNanos: Long) -> Unit)? = null,
) {
    private sealed interface Phase {
        data object Idle : Phase
        /** 카운트인 또는 다음 루프 시작 대기. startStep에서 녹음 시작. */
        class Armed(val kind: TrackKind, val startStep: Long, val startedTransport: Boolean) : Phase {
            val early = ArrayList<Note>()
        }
        /** 끝은 없다. 녹음 버튼/FinishRecord/Stop으로만 끝난다. */
        class Recording(val kind: TrackKind, val startStep: Long) : Phase
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
    private var transpose = 0
    private var lastLoopPos = -1
    private var countInBeat = 0
    private var loopCount = 0L
    /** 모니터링으로 이미 울린 입력이 아직 안 온 스텝으로 퀀타이즈된 경우, 그 스텝에서 같은 노트를 한 번 건너뛴다. */
    private val skipOnce = HashSet<Pair<Long, String>>()

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
            EngineCommand.Start -> if (!transport.running) startPlayback(now)
            EngineCommand.Stop -> if (transport.running) stopTransport() else sampler.stopAll() // 모니터링 소리도 끊는다
            EngineCommand.FinishRecord -> finishRecord()
            is EngineCommand.ClearTrack -> notes.getValue(cmd.kind).clear()
            EngineCommand.ClearAll -> notes.values.forEach { it.clear() }
            is EngineCommand.SetMuted -> mutedRequested[cmd.kind] = cmd.muted
            is EngineCommand.SetBpm -> {
                bpm = cmd.bpm.coerceIn(MIN_BPM, MAX_BPM)
                transport.setStepNanos(stepNanosForBpm(bpm))
            }
            is EngineCommand.SetLatencyNanos -> latencyNanos = cmd.nanos
            is EngineCommand.SetTranspose -> transpose = cmd.semitones.coerceIn(-MAX_TRANSPOSE, MAX_TRANSPOSE)
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
        transpose = song.transpose.coerceIn(-MAX_TRANSPOSE, MAX_TRANSPOSE)
    }

    private fun startPlayback(now: Long) {
        origin = 0
        transport.start(now, stepNanosForBpm(bpm), 0)
        lastLoopPos = -1
        log("PLAY start")
    }

    private fun stopTransport() {
        if (phase is Phase.Recording) endRecording("stop") // 그때까지 찍힌 내용 유지
        transport.stop()
        phase = Phase.Idle // 대기 중이면 취소
        skipOnce.clear()
        sampler.stopAll()
        lastLoopPos = -1
        countInBeat = 0
        log("STOP")
    }

    private fun onPlay(cmd: EngineCommand.Play) {
        sampler.play(cmd.sampleId, cmd.velocity, transposeFor(cmd.sampleId)) // 모니터링: 퀀타이즈와 무관하게 즉시
        val ph = phase
        val half = transport.stepNanos / 2
        when (ph) {
            is Phase.Recording -> {
                // 녹음은 여러 바퀴 이어지므로 루프 시작이 아니라 아직 안 울린 다음 스텝을 기준으로 절대 스텝을 구하고,
                // 그것을 루프 위치(0..31)로 감는다. 템포가 바뀌어도 기준이 같이 따라간다.
                val next = transport.nextStep
                val abs = next + Quantizer.roundedSteps(cmd.timestampNanos, transport.timeOfStep(next), transport.stepNanos, latencyNanos)
                if (abs >= ph.startStep) {
                    val pos = Math.floorMod(abs - origin, STEPS_PER_LOOP.toLong()).toInt()
                    addNote(ph.kind, Note(pos, cmd.sampleId, cmd.velocity))
                    if (abs >= next) skipOnce += abs to cmd.sampleId // 이미 모니터링으로 울렸다
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

    private fun transposeFor(sampleId: String) = if (isPitched(sampleId)) transpose else 0

    private fun addNote(kind: TrackKind, note: Note) {
        val list = notes.getValue(kind)
        if (list.none { it.step == note.step && it.sampleId == note.sampleId }) list += note
    }

    private fun toggleRecord(kind: TrackKind, now: Long) {
        val ph = phase
        // 같은 트랙을 다시 누름: 녹음 중이면 확정, 대기 중이면 취소
        if (ph is Phase.Recording && ph.kind == kind) { endRecording("manual"); return }
        if (ph is Phase.Armed && ph.kind == kind) {
            phase = Phase.Idle
            if (ph.startedTransport) stopTransport()
            log("REC cancel $kind")
            return
        }
        // 다른 트랙: 현재 녹음은 끝내고(내용 유지)/대기는 취소하고 새로 건다
        if (ph is Phase.Recording) endRecording("switch")
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

    private fun finishRecord() {
        when (val ph = phase) {
            is Phase.Recording -> endRecording("finish")
            is Phase.Armed -> {
                phase = Phase.Idle
                if (ph.startedTransport) stopTransport()
                log("REC cancel ${ph.kind}")
            }
            Phase.Idle -> Unit
        }
    }

    private fun endRecording(reason: String) {
        val ph = phase as? Phase.Recording ?: return
        phase = Phase.Idle
        log("REC end ${ph.kind} notes=${notes.getValue(ph.kind).size} ($reason)")
    }

    // ---------------------------------------------------------------- 스텝

    private fun onStep(n: Long, scheduledNanos: Long) {
        onStepFired?.invoke(n, scheduledNanos)
        // 뮤트는 스텝 경계에서 적용
        for (k in TrackKind.entries) mutedApplied[k] = mutedRequested.getValue(k)

        val ph = phase
        if (ph is Phase.Armed && n == ph.startStep) {
            val list = notes.getValue(ph.kind)
            list.clear() // 덮어쓰기: 녹음이 시작되는 순간 기존 노트를 지운다
            for (e in ph.early) addNote(ph.kind, e)
            phase = Phase.Recording(ph.kind, n)
            countInBeat = 0
            log("REC start ${ph.kind} step=$n")
        }

        val cur = phase
        val pos = Math.floorMod(n - origin, STEPS_PER_LOOP.toLong()).toInt()
        // 메트로놈: 녹음 시작 직전 1마디(카운트인)와 녹음 중 매 박. 첫 박(마디 머리)은 강세. 트랙에는 기록하지 않는다.
        if (cur is Phase.Armed) {
            val rel = n - (cur.startStep - STEPS_PER_BAR)
            if (rel in 0 until STEPS_PER_BAR && rel % 4 == 0L) {
                val beat = (rel / 4).toInt() + 1
                countInBeat = beat
                sampler.play(if (beat == 1) "metro_hi" else "metro_lo")
            }
        } else if (cur is Phase.Recording && pos % 4 == 0) {
            sampler.play(if (pos % STEPS_PER_BAR == 0) "metro_hi" else "metro_lo")
        }

        lastLoopPos = pos
        if (pos == 0) { loopCount++; log("LOOP wrap n=$n") }
        // 녹음 중인 트랙도 앞 바퀴에 친 노트가 재생된다.
        for (k in TrackKind.entries) {
            if (mutedApplied.getValue(k)) continue
            for (note in notes.getValue(k)) {
                if (note.step != pos) continue
                if (skipOnce.isNotEmpty() && skipOnce.remove(n to note.sampleId)) continue // 방금 모니터링으로 울린 입력 (비어 있으면 Pair를 만들지 않는다)
                sampler.play(note.sampleId, note.velocity, transposeFor(note.sampleId))
            }
        }
        if (skipOnce.isNotEmpty()) skipOnce.removeAll { it.first <= n }
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
            transpose = transpose,
        )
    }
}

const val MAX_TRANSPOSE = 3

/** 조를 옮길 소리인지: 음높이가 있는 BASS·MELODY만. 드럼·FX·메트로놈은 옮기지 않는다. */
fun isPitched(sampleId: String) = sampleId.startsWith("bass_") || sampleId.startsWith("mel_")
