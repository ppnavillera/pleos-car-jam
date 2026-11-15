package app.pjam.studio.data

import app.pjam.studio.engine.Song
import app.pjam.studio.engine.STEPS_PER_LOOP
import app.pjam.studio.engine.TrackKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** 곡 저장소 (T3-2). 네트워크 없이 기기 안의 JSON 파일만 쓴다. Android 의존 없음(java.io), JVM 단위 테스트로 검증한다. */
interface SongRepository {
    /** 저장된 곡, 최근 것이 앞. 읽을 수 없는 파일은 건너뛴다. */
    fun list(): List<Song>
    fun save(song: Song)
    fun delete(id: String): Boolean
}

/** 손상됐거나 사람이 고친 파일을 걸러 낸다. 여기를 통과한 곡만 엔진에 들어간다. */
object SongValidator {
    fun isValid(song: Song): Boolean {
        if (song.id.isBlank() || song.id.contains('/') || song.id.contains('\\') || song.id.contains("..")) return false
        if (song.tracks.map { it.kind }.sorted() != TrackKind.entries.sorted()) return false // 4트랙이 하나씩
        if (song.bpm !in app.pjam.studio.engine.MIN_BPM..app.pjam.studio.engine.MAX_BPM) return false
        return song.tracks.all { t ->
            t.notes.size <= 4 * STEPS_PER_LOOP &&
                t.notes.all { it.step in 0 until STEPS_PER_LOOP && it.sampleId.isNotBlank() && it.velocity.isFinite() && it.velocity in 0f..1f }
        }
    }
}

class FileSongRepository(private val dir: File, private val log: (String) -> Unit = {}) : SongRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun list(): List<Song> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val s = json.decodeFromString<Song>(f.readText())
                if (SongValidator.isValid(s) && f.name == "${s.id}.json") s else { log("ignored invalid song file ${f.name}"); null }
            } catch (t: Throwable) {
                log("ignored unreadable song file ${f.name}: $t")
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    override fun save(song: Song) {
        require(SongValidator.isValid(song)) { "invalid song" }
        dir.mkdirs()
        val tmp = File(dir, "${song.id}.json.tmp")
        tmp.writeText(json.encodeToString(song))
        val dst = File(dir, "${song.id}.json")
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw java.io.IOException("cannot move $tmp") }
    }

    override fun delete(id: String): Boolean = File(dir, "$id.json").takeIf { id.isNotBlank() && !id.contains('/') && !id.contains("..") }?.delete() ?: false
}

/** 곡 이름 자동 생성 (기획서 5.1: 텍스트 입력을 요구하지 않는다). 예: "11월 15일 세션 2" */
object SongNamer {
    fun next(existingNames: Collection<String>, today: LocalDate): String {
        val prefix = "${today.monthValue}월 ${today.dayOfMonth}일 세션 "
        val used = existingNames.mapNotNull { n -> if (n.startsWith(prefix)) n.removePrefix(prefix).trim().toIntOrNull() else null }
        return prefix + ((used.maxOrNull() ?: 0) + 1)
    }
}

/** 곡 카드에 보일 "어떤 조작이 몇 번" 한 줄 요약 (기획서 4.7). */
object InputSummaryText {
    private val ORDER = listOf("BRAKE" to "브레이크", "TURN_LEFT" to "방향지시등 좌", "TURN_RIGHT" to "방향지시등 우", "STEERING" to "핸들", "HVAC" to "공조", "PAD" to "화면 패드")
    fun of(summary: Map<String, Int>): String {
        val parts = ORDER.mapNotNull { (k, label) -> summary[k]?.takeIf { it > 0 }?.let { "$label $it" } }
        return if (parts.isEmpty()) "기록된 조작 없음" else parts.joinToString(" · ")
    }
}
