package app.pjam.studio.lock

/** 앱이 구분하는 기어 상태. P가 아니면 전부 NOT_PARK다. */
enum class GearKind { PARK, NOT_PARK, UNKNOWN }

/**
 * 기어 판정 방식 (계획서 3.6, FINDINGS V3).
 * SDK_GEAR는 계획서의 단계 1(기어 속성 구독)에 해당한다. Vehicle SDK의 `Gear.registerGearSelection`이
 * 같은 속성을 대신 읽어 주므로 CarPropertyManager 권한이 필요 없다.
 */
enum class GearMethod { SDK_GEAR, CAR_PROPERTY, UX_RESTRICTIONS, ASSUMED_PARK, NONE_YET }

/** GearSource가 신호를 내보내는 곳 (PlaybackGuard가 구현). 어느 스레드에서나 불릴 수 있다. */
interface GearSink {
    fun onGear(kind: GearKind, src: String)
    fun onUx(restricted: Boolean?, src: String)
    fun onMethod(method: GearMethod)
}

/** 기어를 읽는 쪽. Vehicle SDK·Car API 호출은 구현(input/pleos)에만 둔다. */
interface GearSource {
    fun start()
    fun stop()
}
