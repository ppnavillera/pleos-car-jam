package app.pjam.studio.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SongSerializationTest {
    @Test fun roundTrip() {
        val song = Song(
            id = "s1", name = "11월 15일 세션 1", createdAt = 123L, bpm = 120, transpose = -2,
            tracks = listOf(
                Track(TrackKind.DRUM, listOf(Note(0, "drum_kick"), Note(8, "drum_snare", 0.8f))),
                Track(TrackKind.BASS, muted = true),
                Track(TrackKind.MELODY), Track(TrackKind.FX),
            ),
            inputSummary = mapOf("BRAKE" to 4),
        )
        val json = Json.encodeToString(song)
        assertEquals(song, Json.decodeFromString<Song>(json))
    }
}
