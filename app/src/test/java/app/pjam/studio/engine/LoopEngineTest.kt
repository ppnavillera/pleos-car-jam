package app.pjam.studio.engine

import app.pjam.studio.sound.Sampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSampler : Sampler {
    data class Hit(val id: String, val atNanos: Long, val transpose: Int = 0)
    var now = 0L
    val hits = ArrayList<Hit>()
    var stops = 0
    override fun play(sampleId: String, velocity: Float, transpose: Int) { hits += Hit(sampleId, now, transpose) }
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

        // 2마디가 지나도 녹음은 끝나지 않는다. 앞 바퀴의 킥이 다시 울린다.
        assertEquals(TrackStatus.RECORDING, kind(TrackKind.DRUM).status)
        assertEquals(2, sampler.count("drum_kick")) // 모니터링 1 + 앞 바퀴 노트 재생 1
        assertEquals(1, sampler.count("drum_hihat")) // 방금 친 하이햇(→스텝 0)은 같은 스텝에서 또 울리지 않는다

        // 녹음 버튼을 다시 누르면 끝나고 재생이 이어진다. 31.6 → 스텝 0 으로 기록
        engine.send(EngineCommand.ToggleRecord(TrackKind.DRUM))
        runUntil(at(48.0) + 4 * ms)
        val st = kind(TrackKind.DRUM)
        assertEquals(TrackStatus.PLAYING, st.status)
        assertEquals(listOf(0 to "drum_kick", 0 to "drum_hihat", 8 to "drum_snare"), st.notes.map { it.step to it.sampleId }.sortedBy { it.first })
        assertEquals(true, engine.state.value.playing)

        runUntil(at(56.0) + 5 * ms)
        assertEquals(2, sampler.count("drum_snare")) // 모니터링 1 + 재생 1
        runUntil(at(80.0) + 5 * ms) // 다음 루프에서도 반복
        assertEquals(3, sampler.count("drum_kick"))
    }

    @Test fun recordingContinuesPastTwoBarsUntilButtonPressed() {
        startRecordDrum()
        runUntil(at(16.0) + 2 * ms)
        runUntil(at(16.0 + 32 * 3 + 5)) // 3바퀴 넘게
        assertEquals(TrackStatus.RECORDING, kind(TrackKind.DRUM).status)
        assertEquals(TrackKind.DRUM, engine.state.value.recordingKind)
        engine.send(EngineCommand.ToggleRecord(TrackKind.DRUM))
        runUntil(now + 2 * ms)
        assertEquals(null, engine.state.value.recordingKind)
        assertEquals(true, engine.state.value.playing)
    }

    @Test fun overdubStacksOnPreviousLapNotesAndPlaysThemBack() {
        startRecordDrum()
        runUntil(at(16.0) + 2 * ms)
        play("drum_kick", at(16.0)) // 1바퀴째 위치 0
        runUntil(at(40.0)); play("drum_snare", at(40.0)) // 1바퀴째 위치 24
        runUntil(at(48.0) + 2 * ms) // 2바퀴 시작: 킥 재생
        assertEquals(2, sampler.count("drum_kick"))
        runUntil(at(52.0)); play("drum_hihat", at(52.0)) // 2바퀴째 위치 4 → 앞 바퀴 노트 위에 쌓임
        runUntil(at(80.0) + 2 * ms) // 3바퀴 시작 (루프 위치 0 = 스텝 16, 48, 80)
        assertEquals(3, sampler.count("drum_kick"))
        assertEquals(2, sampler.count("drum_snare")) // 모니터링 1 + 2바퀴째 위치 24에서 재생 1
        assertEquals(setOf(0 to "drum_kick", 24 to "drum_snare", 4 to "drum_hihat"),
            kind(TrackKind.DRUM).notes.map { it.step to it.sampleId }.toSet())
    }

    @Test fun existingContentClearedOnceAtRecordStartThenOnlyStacks() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(2, "drum_snare"))), Track(TrackKind.BASS), Track(TrackKind.MELODY), Track(TrackKind.FX)))))
        engine.tick(now)
        startRecordDrum()
        runUntil(at(16.0) + 2 * ms)
        assertEquals(0, kind(TrackKind.DRUM).notes.size) // 시작 순간 한 번 지움
        runUntil(at(20.0)); play("drum_kick", at(20.0))
        runUntil(at(48.0) + 2 * ms) // 바퀴가 돌아도 다시 지우지 않는다
        runUntil(at(52.0)); play("drum_hihat", at(52.0))
        runUntil(at(80.0) + 2 * ms)
        assertEquals(2, kind(TrackKind.DRUM).notes.size)
    }

    @Test fun metronomeEveryBeatWhileRecordingAccentOnBarStartAndNeverRecorded() {
        startRecordDrum()
        runUntil(at(16.0) + 2 * ms)
        val before = sampler.hits.size
        runUntil(at(48.0) - ms) // 녹음 2마디: 박 8개 (위치 0,4,...,28)
        val metro = sampler.hits.drop(before).map { it.id }.filter { it.startsWith("metro") }
        assertEquals(listOf("metro_lo", "metro_lo", "metro_lo", "metro_hi", "metro_lo", "metro_lo", "metro_lo"), metro) // 위치 4..28
        runUntil(at(48.0) + 2 * ms)
        assertEquals("metro_hi", sampler.hits.last { it.id.startsWith("metro") }.id) // 3바퀴째 머리 박
        assertEquals(0, kind(TrackKind.DRUM).notes.size) // 메트로놈은 트랙에 안 남는다

        engine.send(EngineCommand.ToggleRecord(TrackKind.DRUM))
        runUntil(now + 2 * ms)
        val atEnd = sampler.count("metro_lo") + sampler.count("metro_hi")
        runUntil(at(80.0))
        assertEquals(atEnd, sampler.count("metro_lo") + sampler.count("metro_hi")) // 녹음이 끝나면 꺼진다
    }

    @Test fun recordingTrackIsNotDoubleTriggeredInSameLap() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        // 21.6스텝(루프 5.6)에 입력 → 스텝 22(위치 6)로 기록. 같은 바퀴에서 22에 또 울리면 안 된다.
        runUntil(at(21.6)); play("drum_kick", at(21.6))
        runUntil(at(30.0))
        assertEquals(1, sampler.count("drum_kick"))
        // 다음 바퀴(54)에서는 앞 바퀴 노트로 다시 울린다
        runUntil(at(54.0) + 3 * ms)
        assertEquals(2, sampler.count("drum_kick"))
    }

    @Test fun inputQuantizedBackwardPlaysInNextLap() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(22.4)); play("drum_kick", at(22.4)) // 22로 반올림, 이미 지나간 스텝
        runUntil(at(30.0))
        assertEquals(1, sampler.count("drum_kick"))
        runUntil(at(54.0) + 3 * ms)
        assertEquals(2, sampler.count("drum_kick"))
    }

    @Test fun lapNotesQuantizeCorrectlyAcrossTempoChange() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.5))
        engine.send(EngineCommand.SetBpm(140)) // 다음 스텝(21)부터 새 길이
        val newStep = stepNanosForBpm(140)
        val ts = at(21.0) + 3 * newStep // 스텝 24
        runUntil(ts + 5 * ms)
        play("drum_kick", ts)
        runUntil(ts + 10 * ms)
        assertEquals(listOf(8), kind(TrackKind.DRUM).notes.map { it.step }) // 24 - 16
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

    @Test fun finishRecordKeepsContentAndKeepsPlaying() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.0)); play("drum_kick", at(20.0))
        runUntil(at(60.0))
        engine.send(EngineCommand.FinishRecord)
        runUntil(at(60.0) + 2 * ms)
        assertEquals(null, engine.state.value.recordingKind)
        assertEquals(true, engine.state.value.playing)
        assertEquals(TrackStatus.PLAYING, kind(TrackKind.DRUM).status)
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
    }

    @Test fun finishRecordDuringCountInCancelsAndStopsTransport() {
        startRecordDrum()
        runUntil(at(5.0))
        engine.send(EngineCommand.FinishRecord)
        runUntil(at(5.0) + 2 * ms)
        assertEquals(false, engine.state.value.playing)
        assertEquals(null, engine.state.value.recordingKind)
        assertEquals(TrackStatus.EMPTY, kind(TrackKind.DRUM).status)
    }

    @Test fun finishRecordWhenIdleDoesNothing() {
        engine.send(EngineCommand.FinishRecord); engine.tick(now)
        assertEquals(false, engine.state.value.playing)
    }

    @Test fun stoppingTransportEndsRecordingAndKeepsNotes() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.0)); play("drum_kick", at(20.0))
        runUntil(at(60.0))
        engine.send(EngineCommand.Stop)
        runUntil(at(60.0) + 2 * ms)
        assertEquals(null, engine.state.value.recordingKind)
        assertEquals(false, engine.state.value.playing)
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
        // 다시 재생하면 녹음 상태가 아니라 재생만 한다
        engine.send(EngineCommand.TogglePlay)
        runUntil(now + 2 * ms)
        assertEquals(null, engine.state.value.recordingKind)
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

    @Test fun startIsNotAToggleAndDoesNothingWhileRunning() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"))), Track(TrackKind.BASS), Track(TrackKind.MELODY), Track(TrackKind.FX)))))
        engine.send(EngineCommand.Start); engine.tick(now)
        runUntil(at(40.0))
        engine.send(EngineCommand.Start) // 이미 돌고 있으면 무시: 처음부터 다시 시작하거나 멈추면 안 된다
        runUntil(at(41.0))
        assertEquals(true, engine.state.value.playing)
        assertEquals(2, sampler.count("drum_kick")) // 스텝 0, 32
    }

    @Test fun stopWhileRecordingThenStartResumesPlaybackFromLoopStartWithoutRecording() {
        startRecordDrum()
        runUntil(at(16.0) + ms)
        runUntil(at(20.0)); play("drum_kick", at(20.0))
        runUntil(at(24.0))
        engine.send(EngineCommand.Stop) // 기어 D 등: 녹음은 내용을 유지하고 끝난다
        runUntil(at(24.0) + 2 * ms)
        assertEquals(false, engine.state.value.playing)
        assertEquals(null, engine.state.value.recordingKind)
        assertEquals(1, kind(TrackKind.DRUM).notes.size)
        sampler.hits.clear()
        engine.send(EngineCommand.Start) // P 복귀
        val resumeAt = now
        runUntil(resumeAt + 5 * step)
        assertEquals(true, engine.state.value.playing)
        assertEquals(null, engine.state.value.recordingKind)
        assertEquals(0, sampler.hits.count { it.id.startsWith("metro") }) // 메트로놈도 없음
        assertEquals(TrackStatus.PLAYING, kind(TrackKind.DRUM).status)
        // 루프 위치 4(= 재개 후 4스텝)에서 앞서 찍은 킥(위치 4)이 한 번 울린다
        assertEquals(1, sampler.count("drum_kick"))
    }

    @Test fun transposeAppliesToBassAndMelodyOnlyAndKeepsMetronomeAndDrumsPlain() {
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"))), Track(TrackKind.BASS, listOf(Note(0, "bass_1_o2"))),
            Track(TrackKind.MELODY, listOf(Note(0, "mel_3"))), Track(TrackKind.FX, listOf(Note(0, "fx_impact")))))))
        engine.send(EngineCommand.SetTranspose(2))
        engine.send(EngineCommand.Start); engine.tick(now)
        assertEquals(2, engine.state.value.transpose)
        val byId = sampler.hits.associate { it.id to it.transpose }
        assertEquals(0, byId["drum_kick"]); assertEquals(2, byId["bass_1_o2"]); assertEquals(2, byId["mel_3"]); assertEquals(0, byId["fx_impact"])
        // 모니터링(연주 입력)도 같다
        sampler.hits.clear()
        engine.send(EngineCommand.Play("mel_5", now)); engine.tick(now + ms)
        assertEquals(2, sampler.hits.single { it.id == "mel_5" }.transpose)
    }

    @Test fun transposeIsClampedAndLoadedFromSong() {
        engine.send(EngineCommand.SetTranspose(9)); engine.tick(now)
        assertEquals(3, engine.state.value.transpose)
        engine.send(EngineCommand.SetTranspose(-9)); engine.tick(now)
        assertEquals(-3, engine.state.value.transpose)
        engine.send(EngineCommand.LoadSong(Song("x", "x", 0, transpose = 2))); engine.tick(now)
        assertEquals(2, engine.state.value.transpose)
    }
}
