package app.pjam.studio.input

enum class TurnDirection { OFF, LEFT, RIGHT }

/**
 * 원시 신호 → InputEvent (계획서 3.4). Android 의존 없음.
 * 원칙: "콜백이 왔다"가 아니라 "값이 직전과 다르다"일 때만 이벤트를 만든다. 첫 값은 기준값이라 이벤트를 만들지 않는다.
 * SDK 값(예: 방향지시등 Blink/Flash)은 호출하는 쪽이 이 클래스의 단순한 입력 타입으로 바꿔서 넘긴다.
 */
class InputMapper(
    private val steeringEnter: Float = 10f,    // 좌우 구간에 들어가는 각도(절댓값)
    private val steeringExit: Float = 7f,      // 중앙으로 돌아오는 각도 (= 10 − 히스테리시스 3)
    private val rightIsPositive: Boolean = true, // V4에서 부호 확정
    private val brakePress: Float = 5f,        // % 이상으로 올라가면 Trigger
    private val brakeRelease: Float = 2f,      // % 미만으로 내려가야 다시 받음
) {
    private var brakePressed: Boolean? = null
    private var turn: TurnDirection? = null
    private var zone: SteeringZone? = null
    private val toggles = HashMap<ToggleSource, Boolean>()
    private val levels = HashMap<LevelSource, Float>()

    /** 브레이크 0..100%. 수치가 아니라 불리언만 오면 true=100, false=0으로 넘긴다. */
    fun brake(percent: Float?, ts: Long): InputEvent? {
        if (percent == null) return null
        val was = brakePressed
        if (was == null) { brakePressed = percent >= brakePress; return null } // 기준값
        if (!was && percent >= brakePress) { brakePressed = true; return InputEvent.Trigger(TriggerSource.BRAKE, ts) }
        if (was && percent < brakeRelease) brakePressed = false
        return null
    }

    /** 방향지시등. 꺼짐→좌/우, 좌↔우 직접 전환만 Trigger. 같은 방향 안의 Blink↔Flash는 같은 값으로 들어와 무시된다. */
    fun turnSignal(dir: TurnDirection?, ts: Long): InputEvent? {
        if (dir == null) return null
        val prev = turn
        turn = dir
        if (prev == null || prev == dir) return null
        return when (dir) {
            TurnDirection.LEFT -> InputEvent.Trigger(TriggerSource.TURN_LEFT, ts)
            TurnDirection.RIGHT -> InputEvent.Trigger(TriggerSource.TURN_RIGHT, ts)
            TurnDirection.OFF -> null
        }
    }

    /** 핸들 각도(도). ±steeringEnter 이상이면 좌/우, ±steeringExit 안으로 들어오면 중앙. 사이는 이전 구간 유지. */
    fun steering(degrees: Float?, ts: Long): InputEvent? {
        if (degrees == null) return null
        val d = if (rightIsPositive) degrees else -degrees // 이후 양수 = 오른쪽
        val prev = zone
        val next = when {
            d >= steeringEnter -> SteeringZone.RIGHT
            d <= -steeringEnter -> SteeringZone.LEFT
            kotlin.math.abs(d) < steeringExit -> SteeringZone.CENTER
            else -> prev ?: SteeringZone.CENTER
        }
        zone = next
        return if (prev != null && prev != next) InputEvent.Zone(next, ts) else null
    }

    /** 공조 토글 항목. 켜짐/꺼짐 자체는 의미가 없고 "바뀌는 순간"이 이벤트다. */
    fun toggle(source: ToggleSource, on: Boolean?, ts: Long): InputEvent? {
        if (on == null) return null
        val prev = toggles.put(source, on)
        return if (prev != null && prev != on) InputEvent.Toggle(source, ts) else null
    }

    /** 수준 값. capability의 min..max로 0..1 정규화한다(범위를 모르면 null). */
    fun level(source: LevelSource, value: Float?, min: Float, max: Float, ts: Long): InputEvent? {
        if (value == null || max <= min) return null
        val n = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val prev = levels.put(source, n)
        return if (prev != null && prev != n) InputEvent.Level(source, n, ts) else null
    }
}
