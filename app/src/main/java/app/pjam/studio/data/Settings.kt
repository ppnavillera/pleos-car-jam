package app.pjam.studio.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 앱 설정 (기획서 5.2, T3-3). 바뀌면 바로 파일에 저장하고 다음 실행에도 유지한다. */
@Serializable
data class Settings(
    /** 켜면 방향지시등·와이퍼 입력을 무시한다(차 밖으로 드러나는 조작). 기본 꺼짐 (기획서 4.6). */
    val quietMode: Boolean = false,
    /** 입력 지연 보정(ms). 입력 시각에서 이만큼 빼고 퀀타이즈한다. 기본 0 (V6: PUT→콜백 평균 6.6ms). */
    val latencyMs: Int = 0,
    val soundPackId: String = "basic",
    /** 첫 실행 안내를 봤는지 (T3-8). */
    val onboardingDone: Boolean = false,
) {
    fun sanitized(): Settings = copy(latencyMs = latencyMs.coerceIn(MIN_LATENCY_MS, MAX_LATENCY_MS))

    companion object {
        const val MIN_LATENCY_MS = -50
        const val MAX_LATENCY_MS = 150
        const val LATENCY_STEP_MS = 10
    }
}

interface SettingsStore {
    fun load(): Settings
    fun save(settings: Settings)
}

class FileSettingsStore(private val file: File, private val log: (String) -> Unit = {}) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): Settings = try {
        if (file.isFile) json.decodeFromString<Settings>(file.readText()).sanitized() else Settings()
    } catch (t: Throwable) {
        log("settings unreadable → defaults: $t")
        Settings()
    }

    override fun save(settings: Settings) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(settings.sanitized()))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (t: Throwable) {
            log("settings save failed: $t")
        }
    }
}
