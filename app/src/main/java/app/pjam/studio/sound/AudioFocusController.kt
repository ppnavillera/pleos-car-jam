package app.pjam.studio.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * 오디오 포커스 (계획서 3.5 기본값): USAGE_MEDIA, AUDIOFOCUS_GAIN.
 * 영구 손실 → onLost() (재생 정지), 일시 손실 → onLost() 후 사용자가 다시 재생.
 */
class AudioFocusController(context: Context, private val onLost: () -> Unit) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            Log.i(TAG, "focus change=$change")
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLost()
        }
        .build()
    private var held = false

    fun acquire(): Boolean {
        if (held) return true
        val r = try { am.requestAudioFocus(request) } catch (t: Throwable) { Log.w(TAG, "focus request failed: $t"); AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "focus request result=$r held=$held")
        return held
    }

    fun release() {
        if (held) {
            try { am.abandonAudioFocusRequest(request) } catch (_: Throwable) {}
            held = false
        }
    }

    companion object { const val TAG = "PJAM_FOCUS" }
}
