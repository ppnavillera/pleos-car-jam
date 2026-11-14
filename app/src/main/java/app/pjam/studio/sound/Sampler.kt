package app.pjam.studio.sound

/** 소리 재생기. 엔진은 이 인터페이스만 안다(순수 Kotlin). 구현: SoundPoolSampler, 테스트용 가짜. */
interface Sampler {
    /** 즉시 재생한다. transpose는 반음 단위(-3..+3). */
    fun play(sampleId: String, velocity: Float = 1f, transpose: Int = 0)

    fun stopAll()
}
