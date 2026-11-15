package app.pjam.studio.data

import android.content.Context
import app.pjam.studio.engine.Song
import kotlinx.serialization.json.Json

/** APK에 들어 있는 읽기 전용 곡(assets/songs/). 첫 실행 여부와 무관하게 언제나 있다. */
object BundledSongs {
    const val EXAMPLE_FILE = "example_full.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context, file: String): Song? = try {
        context.assets.open("songs/$file").bufferedReader().use { json.decodeFromString<Song>(it.readText()) }
    } catch (t: Throwable) {
        android.util.Log.w("PJAM_DATA", "cannot load bundled song $file: $t")
        null
    }
}
