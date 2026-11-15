package app.pjam.studio.engine

import kotlin.math.roundToInt

/** 풍량(0.0~1.0, capability 범위로 정규화됨) → 템포 (T3-1). 최소=70 BPM, 최대=140 BPM, 사이는 선형. 범위 밖 값은 끝 값. */
object Tempo {
    fun bpmFor(normalized: Float): Int = (MIN_BPM + normalized.coerceIn(0f, 1f) * (MAX_BPM - MIN_BPM)).roundToInt()
}
