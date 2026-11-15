package app.pjam.studio.data

import app.pjam.studio.engine.Note
import app.pjam.studio.engine.Song
import app.pjam.studio.engine.Track
import app.pjam.studio.engine.TrackKind
import app.pjam.studio.input.CycleSource
import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.InputFilter
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class DataTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun song(id: String, at: Long = 1, name: String = "n") = Song(
        id = id, name = name, createdAt = at, bpm = 120, transpose = 2, soundPackId = "car",
        tracks = listOf(
            Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"), Note(31, "drum_snare", 0.5f))),
            Track(TrackKind.BASS, muted = true), Track(TrackKind.MELODY, listOf(Note(5, "mel_3"))), Track(TrackKind.FX),
        ),
        inputSummary = mapOf("BRAKE" to 3, "PAD" to 2),
    )

    // ---- T3-2: 저장 → 불러오기 왕복, 손상 파일 무시 ----
    @Test fun saveThenListRoundTripsEveryField() {
        val repo = FileSongRepository(File(tmp.root, "songs"))
        val s = song("a")
        repo.save(s)
        assertEquals(listOf(s), repo.list())
    }

    @Test fun newestFirstAndDeleteWorks() {
        val repo = FileSongRepository(tmp.newFolder("songs"))
        repo.save(song("old", at = 1)); repo.save(song("new", at = 2))
        assertEquals(listOf("new", "old"), repo.list().map { it.id })
        assertTrue(repo.delete("old"))
        assertEquals(listOf("new"), repo.list().map { it.id })
        assertFalse(repo.delete("old"))
    }

    @Test fun corruptAndInvalidFilesAreIgnoredAndGoodOnesSurvive() {
        val dir = tmp.newFolder("songs")
        val repo = FileSongRepository(dir)
        repo.save(song("good"))
        File(dir, "garbage.json").writeText("{ not json")
        File(dir, "empty.json").writeText("")
        File(dir, "truncated.json").writeText(File(dir, "good.json").readText().take(40))
        File(dir, "badstep.json").writeText(File(dir, "good.json").readText().replace("\"id\":\"good\"", "\"id\":\"badstep\"").replace("\"step\":31", "\"step\":99"))
        File(dir, "mismatch.json").writeText(File(dir, "good.json").readText()) // 파일 이름과 id 가 다름
        val logs = ArrayList<String>()
        val listed = FileSongRepository(dir) { logs += it }.list()
        assertEquals(listOf("good"), listed.map { it.id })
        assertTrue(logs.size >= 5)
    }

    @Test fun invalidSongCannotBeSaved() {
        val repo = FileSongRepository(tmp.newFolder("songs"))
        val bad = song("x").copy(tracks = song("x").tracks.drop(1)) // 3트랙
        try { repo.save(bad); throw AssertionError("saved invalid song") } catch (_: IllegalArgumentException) {}
        assertEquals(emptyList<Song>(), repo.list())
    }

    @Test fun pathLikeIdsAreRejected() {
        assertFalse(SongValidator.isValid(song("../evil")))
        assertFalse(SongValidator.isValid(song("a/b")))
    }

    @Test fun autoNameCountsUpPerDay() {
        val d = LocalDate.of(2026, 11, 15)
        assertEquals("11월 15일 세션 1", SongNamer.next(emptyList(), d))
        assertEquals("11월 15일 세션 3", SongNamer.next(listOf("11월 15일 세션 1", "11월 15일 세션 2", "11월 14일 세션 9"), d))
        assertEquals("11월 15일 세션 1", SongNamer.next(listOf("11월 14일 세션 4"), d))
    }

    @Test fun summaryTextListsCounts() {
        assertEquals("브레이크 3 · 화면 패드 2", InputSummaryText.of(mapOf("BRAKE" to 3, "PAD" to 2, "HVAC" to 0)))
        assertEquals("기록된 조작 없음", InputSummaryText.of(emptyMap()))
    }

    // ---- T3-3: 설정 유지, 조용한 연주 모드 ----
    @Test fun settingsSurviveRestartAndBadFileFallsBackToDefaults() {
        val f = File(tmp.root, "settings.json")
        FileSettingsStore(f).save(Settings(quietMode = true, latencyMs = 40, soundPackId = "car", onboardingDone = true))
        assertEquals(Settings(true, 40, "car", true), FileSettingsStore(f).load())
        f.writeText("%%%")
        assertEquals(Settings(), FileSettingsStore(f).load())
        assertEquals(Settings(), FileSettingsStore(File(tmp.root, "none.json")).load())
    }

    @Test fun latencyIsClampedToItsRange() {
        val f = File(tmp.root, "s.json")
        FileSettingsStore(f).save(Settings(latencyMs = 9999))
        assertEquals(Settings.MAX_LATENCY_MS, FileSettingsStore(f).load().latencyMs)
        assertEquals(Settings.MIN_LATENCY_MS, Settings(latencyMs = -9999).sanitized().latencyMs)
    }

    @Test fun quietModeIgnoresTurnSignalsAndWiperButKeepsTheRest() {
        val ts = 0L
        val left = InputEvent.Trigger(TriggerSource.TURN_LEFT, ts)
        val right = InputEvent.Trigger(TriggerSource.TURN_RIGHT, ts)
        val brake = InputEvent.Trigger(TriggerSource.BRAKE, ts)
        val wiper = InputEvent.Cycle(CycleSource.WIPER, ts)
        val drive = InputEvent.Cycle(CycleSource.DRIVE_MODE, ts)
        for (e in listOf(left, right, wiper)) { assertFalse("$e", InputFilter.accepts(e, quietMode = true)); assertTrue("$e", InputFilter.accepts(e, quietMode = false)) }
        for (e in listOf(brake, drive, InputEvent.Zone(SteeringZone.LEFT, ts), InputEvent.Toggle(ToggleSource.AC, ts), InputEvent.Level(app.pjam.studio.input.LevelSource.FAN_SPEED, 0.5f, ts))) {
            assertTrue("$e", InputFilter.accepts(e, quietMode = true))
        }
    }

    @Test fun bundledExampleFileIsAValidFullSong() {
        val f = File("../assets/songs/example_full.json").takeIf { it.isFile } ?: File("assets/songs/example_full.json")
        val s = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Song>(f.readText())
        assertTrue(SongValidator.isValid(s))
        assertTrue(s.tracks.all { it.notes.isNotEmpty() })
        assertEquals(4, s.tracks.size)
    }
}
