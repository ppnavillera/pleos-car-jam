package app.pjam.studio.engine

import app.pjam.studio.sound.Sampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSampler : Sampler {
    data class Hit(val id: String, val atNanos: Long)
    var now = 0L
    val hits = ArrayList<Hit>()
    var stops = 0
    override fun play(sampleId: String, velocity: Float, transpose: Int) { hits += Hit(sampleId, now) }
    override fun stopAll() { stops++ }
    fun count(id: String) = hits.count { it.id == id }
}

class LoopEngineTest {
    private val sampler = FakeSampler()
    private val engine = LoopEngine(sampler)
    private val step = stepNanosForBpm(100) // 150ms
    private val ms = 1_000_000L
    private var now = 10_000_000_000L // t0
    private val t0 = now

    /** 1ms 단위로 시계를 진행하며 tick */
    private fun runUntil(target: Long) {
        while (now < target) { now += ms; sampler.now = now; engine.tick(now) }
    }
    private fun at(stepsFromT0: Double) = t0 + (stepsFromT0 * step).toLong()
    private fun kind(k: TrackKind) = engine.state.value.tracks.first { it.kind == k }

    private fun startRecordDrum() {
        sampler.now = now
        engine.send(EngineCommand.ToggleRecord(TrackKind.DRUM))
        engine.tick(now) // 명령 처리 + 스텝 0 발사 (t0)
    }

    private fun play(id: String, ts: Long) = engine.send(EngineCommand.Play(id, ts))

    @Test fun countInThenRecordThenAutoLoop() {
        startRecordDrum()
        assertEquals(TrackStatus.ARMED, kind(TrackKind.DRUM).status)
        runUntil(at(15.9))
        // 카운트인 4박: 0,4,8,12 스텝에서 메트로놈 (hi, lo, lo, lo)
        assertEquals(listOf("metro_hi", "metro_lo", "metro_lo", "metro_lo"), sampler.hits.map { it.id })

        // 녹음 시작 = 스텝 16. 이후 루프 위치 0에 킥, 8에 스네어, 31.6에 하이햇(→0)
        runUntil(at(16.0) + 2 * ms)
        assertEquals(TrackStatus.RECORDING, kind(TrackKind.DRUM).status)
        play("drum_kick", at(16.0))
        runUntil(at(24.0)); play("drum_snare", at(24.0))
        runUntil(at(47.6)); play("drum_hihat", at(47.6))
        runUntil(at(48.0) + 2 * ms)

        // 자동 재생 상태로 전환, 31.6 → 스텝 0 으로 기록
        val st = kind(TrackKind.DRUM)
        assertEquals(TrackStatus.PLAYING, st.status)
        assertEquals(listOf(0 to "drum_kick", 0 to "drum_hihat", 8 to "drum_snare"), st.notes.map { it.step to it.sampleId }.sortedBy { it.first })
        assertEquals(true, engine.state.value.playing)

        // 스텝 48(= 루프 위치 0)에서 킥이 자동 재생돼 이미 2회(모니터링 1 + 재생 1). 스텝 56에서 스네어 재생
        assertEquals(2, sampler.count("drum_kick"))
        runUntil(at(56.0) + 5 * ms)
        assertEquals(2, sampler.count("drum_kick"))
        assertEquals(2, sampler.count("drum_snare")) // 모니터링 1 + 재생 1
        // 다음 루프(80)에서도 반복
        runUntil(at(80.0) + 5 * ms)
        assertEquals(3, sampler.count("drum_kick"))
    }

    @Test fun recordingTrackIsNotDoubleTriggered() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        // 5.6스텝 지점(=루프 21.6)에 입력 → 스텝 6으로 기록되지만 같은 녹음 구간 안에서 다시 울리면 안 됨
        runUntil(at(21.6)); play("drum_kick", at(21.6))
        runUntil(at(30.0))
        assertEquals(1, sampler.count("drum_kick"))
    }

    @Test fun overwriteClearsOnlyWhenRecordingStarts() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(2, "drum_snare"))), Track(TrackKind.BASS), Track(TrackKind.MELODY), Track(TrackKind.FX)))))
        engine.tick(now)
        // 정지 상태에서 DRUM 재녹음: 카운트인 동안 기존 노트는 남아 있다
        startRecordDrum()
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
        runUntil(at(16.0) + 2 * ms)
        assertEquals(0, kind(TrackKind.DRUM).notes.size) // 녹음 시작 순간 삭제
    }

    @Test fun earlyInputBeforeStartBecomesStepZeroAndSurvivesOverwrite() {
        startRecordDrum()
        runUntil(at(15.7)); play("drum_kick", at(15.7)) // 시작점보다 0.3스텝 이름
        runUntil(at(16.0) + 2 * ms)
        assertEquals(listOf(0 to "drum_kick"), kind(TrackKind.DRUM).notes.map { it.step to it.sampleId })
    }

    @Test fun duplicateSameStepSameSampleMerged() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.0)); play("drum_kick", at(20.0)); play("drum_kick", at(20.1))
        runUntil(at(48.0) + ms)
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
    }

    @Test fun cancelDuringRecordingKeepsNotesSoFar() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.0)); play("drum_kick", at(20.0))
        runUntil(at(30.0))
        engine.send(EngineCommand.ToggleRecord(TrackKind.DRUM))
        runUntil(at(30.0) + 2 * ms)
        assertEquals(TrackStatus.PLAYING, kind(TrackKind.DRUM).status)
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
        assertEquals(true, engine.state.value.playing) // 재생은 계속
    }

    @Test fun stopDuringRecordingKeepsNotesAndStops() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.0)); play("drum_kick", at(20.0))
        runUntil(at(25.0))
        engine.send(EngineCommand.Stop)
        runUntil(at(25.0) + 2 * ms)
        assertEquals(false, engine.state.value.playing)
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
        assertEquals(TrackStatus.PLAYING, kind(TrackKind.DRUM).status)
    }

    @Test fun cancelCountInStopsTransport() {
        startRecordDrum()
        runUntil(at(5.0))
        engine.send(EngineCommand.ToggleRecord(TrackKind.DRUM))
        runUntil(at(5.0) + 2 * ms)
        assertEquals(false, engine.state.value.playing)
        assertEquals(TrackStatus.EMPTY, kind(TrackKind.DRUM).status)
    }

    @Test fun mutedTrackSilentFromNextStepButNotesKept() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"), Note(4, "drum_kick"), Note(8, "drum_kick"))),
            Track(TrackKind.BASS), Track(TrackKind.MELODY), Track(TrackKind.FX)))))
        engine.send(EngineCommand.TogglePlay)
        engine.tick(now)
        runUntil(at(1.0))
        assertEquals(1, sampler.count("drum_kick")) // step 0
        engine.send(EngineCommand.SetMuted(TrackKind.DRUM, true))
        runUntil(at(9.0))
        assertEquals(1, sampler.count("drum_kick")) // 4, 8은 무음
        assertEquals(3, kind(TrackKind.DRUM).notes.size)
        assertEquals(TrackStatus.MUTED, kind(TrackKind.DRUM).status)
        engine.send(EngineCommand.SetMuted(TrackKind.DRUM, false))
        runUntil(at(33.0)) // 다음 루프 위치 0(스텝 32)에서 다시 울림
        assertEquals(2, sampler.count("drum_kick"))
    }

    @Test fun bassRecordedWhileDrumPlaysKeepsDrumAndStartsAtLoopBoundary() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"), Note(16, "drum_kick"))),
            Track(TrackKind.BASS), Track(TrackKind.MELODY), Track(TrackKind.FX)))))
        engine.send(EngineCommand.TogglePlay)
        engine.tick(now)
        runUntil(at(5.0)) // 루프 위치 5
        engine.send(EngineCommand.ToggleRecord(TrackKind.BASS)) // 남은 27스텝 ≥ 16 → 다음 루프(32)에서 시작
        runUntil(at(6.0))
        assertEquals(TrackStatus.ARMED, kind(TrackKind.BASS).status)
        val drumBefore = sampler.count("drum_kick")
        // 메트로놈은 스텝 16..31 사이 4번
        runUntil(at(31.9))
        assertEquals(listOf("metro_hi", "metro_lo", "metro_lo", "metro_lo"), sampler.hits.map { it.id }.filter { it.startsWith("metro") })
        assertEquals(drumBefore + 1, sampler.count("drum_kick")) // 스텝 16 킥 계속 재생
        runUntil(at(32.0) + 2 * ms)
        assertEquals(TrackStatus.RECORDING, kind(TrackKind.BASS).status)
        // 녹음 중에도 DRUM 유지
        runUntil(at(48.0) + 2 * ms)
        assertEquals(TrackStatus.PLAYING, kind(TrackKind.DRUM).status)
        assertEquals(2, kind(TrackKind.DRUM).notes.size)
        assertTrue(sampler.count("drum_kick") >= drumBefore + 3)
    }

    @Test fun recordRequestedWithLessThanOneBarLeftWaitsAnotherLap() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0)))
        engine.send(EngineCommand.TogglePlay)
        engine.tick(now)
        runUntil(at(20.0)) // 남은 12스텝 < 16
        engine.send(EngineCommand.ToggleRecord(TrackKind.BASS))
        runUntil(at(40.0))
        assertEquals(TrackStatus.ARMED, kind(TrackKind.BASS).status) // 스텝 32에서 시작하지 않고 64로 미룸
        runUntil(at(64.0) + 2 * ms)
        assertEquals(TrackStatus.RECORDING, kind(TrackKind.BASS).status)
    }

    @Test fun clearTrackAndClearAll() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"))), Track(TrackKind.BASS, listOf(Note(1, "bass_1_o2"))), Track(TrackKind.MELODY), Track(TrackKind.FX)))))
        engine.send(EngineCommand.ClearTrack(TrackKind.DRUM)); engine.tick(now)
        assertEquals(0, kind(TrackKind.DRUM).notes.size)
        assertEquals(1, kind(TrackKind.BASS).notes.size)
        engine.send(EngineCommand.ClearAll); engine.tick(now)
        assertEquals(0, kind(TrackKind.BASS).notes.size)
    }

    @Test fun monitoringSoundsImmediatelyWhenIdle() {
        engine.send(EngineCommand.Play("drum_kick", now)); engine.tick(now)
        assertEquals(1, sampler.count("drum_kick"))
    }
}
