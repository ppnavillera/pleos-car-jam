package app.pjam.studio.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * SoundPool 기반 재생기 (1차 구현). 스텝 시각에 맞춰 즉시 play() 한다(예약 재생 없음).
 * 옥타브는 별도 샘플이고 rate는 ±3반음 조옮김에만 쓴다(2^(n/12) = 0.84~1.19).
 * 로드는 비동기라 `PJAM_SOUND loaded N/N` 로그로 완료를 알린다.
 */
class SoundPoolSampler(context: Context, private val packId: String = "basic") : Sampler {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(16)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .build()

    private val soundIds = ConcurrentHashMap<String, Int>()
    private val loadedPoolIds = ConcurrentHashMap.newKeySet<Int>()
    private val loadedCount = AtomicInteger(0)
    @Volatile var totalCount: Int = 0
        private set
    /** 최근에 시작한 스트림. stopAll이 이것들을 실제로 멈춘다. 샘플이 1초 이내라 32개면 충분하다. */
    private val recentStreams = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    val allLoaded: Boolean get() = totalCount > 0 && loadedCount.get() == totalCount

    init {
        val dir = "soundpacks/$packId"
        val names = (context.assets.list(dir) ?: emptyArray()).filter { it.endsWith(".wav") }
        totalCount = names.size
        pool.setOnLoadCompleteListener { _, soundId, status ->
            if (status == 0) {
                loadedPoolIds.add(soundId)
                val n = loadedCount.incrementAndGet()
                Log.i(TAG, "loaded $n/$totalCount")
            } else {
                Log.w(TAG, "load failed soundId=$soundId status=$status")
            }
        }
        for (name in names) {
            try {
                context.assets.openFd("$dir/$name").use { afd ->
                    soundIds[name.removeSuffix(".wav")] = pool.load(afd, 1)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cannot load $name: $t")
            }
        }
        Log.i(TAG, "loading $totalCount samples from $dir")
    }

    override fun play(sampleId: String, velocity: Float, transpose: Int) {
        val id = soundIds[sampleId]
        if (id == null || id !in loadedPoolIds) {
            Log.w(TAG, "skip $sampleId (unknown or not loaded)")
            return
        }
        val vol = velocity.coerceIn(0f, 1f)
        val rate = 2.0.pow(transpose / 12.0).toFloat().coerceIn(0.5f, 2.0f)
        val stream = pool.play(id, vol, vol, 1, 0, rate)
        if (stream != 0) {
            recentStreams.add(stream)
            while (recentStreams.size > 32) recentStreams.poll()
        }
    }

    override fun stopAll() {
        // SoundPool은 전체 정지가 없어 시작한 스트림을 하나씩 stop() 한다.
        // (이전 구현의 autoPause+autoResume은 멈췄다가 바로 다시 재생해서 아무것도 멈추지 못했다.)
        while (true) { val s = recentStreams.poll() ?: break; pool.stop(s) }
    }

    fun release() {
        pool.release()
    }

    companion object {
        const val TAG = "PJAM_SOUND"
    }
}
