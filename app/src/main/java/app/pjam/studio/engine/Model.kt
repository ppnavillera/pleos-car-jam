package app.pjam.studio.engine

import kotlinx.serialization.Serializable

const val STEPS_PER_LOOP = 32
const val STEPS_PER_BAR = 16
const val MIN_BPM = 70
const val MAX_BPM = 140
const val DEFAULT_BPM = 100

enum class TrackKind { DRUM, BASS, MELODY, FX }

@Serializable
data class Note(val step: Int, val sampleId: String, val velocity: Float = 1f)

@Serializable
data class Track(val kind: TrackKind, val notes: List<Note> = emptyList(), val muted: Boolean = false)

@Serializable
data class Song(
    val id: String,
    val name: String,
    val createdAt: Long,
    val bpm: Int = DEFAULT_BPM,
    val transpose: Int = 0,
    val soundPackId: String = "basic",
    val tracks: List<Track> = TrackKind.entries.map { Track(it) },
    val inputSummary: Map<String, Int> = emptyMap(),
)

/** 16분음표 하나의 길이(ns). 100 BPM → 150ms. */
fun stepNanosForBpm(bpm: Int): Long = 60_000_000_000L / bpm.coerceIn(MIN_BPM, MAX_BPM) / 4
