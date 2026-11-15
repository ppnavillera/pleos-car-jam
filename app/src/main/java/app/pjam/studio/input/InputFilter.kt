package app.pjam.studio.input

/** 조용한 연주 모드 (기획서 4.6): 켜면 방향지시등·와이퍼 입력을 무시한다. 순수 Kotlin. */
object InputFilter {
    fun accepts(event: InputEvent, quietMode: Boolean): Boolean {
        if (!quietMode) return true
        return when (event) {
            is InputEvent.Trigger -> event.source == TriggerSource.BRAKE
            is InputEvent.Cycle -> event.source != CycleSource.WIPER
            else -> true
        }
    }
}
