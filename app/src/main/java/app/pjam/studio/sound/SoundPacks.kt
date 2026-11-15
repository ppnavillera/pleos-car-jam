package app.pjam.studio.sound

/** 사운드팩 목록 (T3-5). 팩 폴더는 assets/soundpacks/<id>/ 이고 sampleId는 팩마다 같다. 순수 Kotlin. */
object SoundPacks {
    const val DEFAULT = "basic"
    const val CAR = "car"
    val ALL = listOf(DEFAULT, CAR)

    fun label(id: String) = when (id) { CAR -> "차량"; else -> "기본" }
    fun isKnown(id: String) = id in ALL
    /** 화면 버튼으로 다음 팩 (끝이면 처음으로). 모르는 팩이면 기본으로. */
    fun next(id: String): String = ALL[(ALL.indexOf(id).takeIf { it >= 0 }?.plus(1) ?: 0) % ALL.size]
}
