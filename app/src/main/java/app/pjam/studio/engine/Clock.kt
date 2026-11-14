package app.pjam.studio.engine

/** 단조 시계. 기기에서는 SystemClock.elapsedRealtimeNanos, 테스트에서는 가짜 시계. 엔진과 입력은 같은 시계를 쓴다. */
fun interface Clock {
    fun nanos(): Long
}
