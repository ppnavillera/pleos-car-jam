package app.pjam.studio.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * P단 잠금과 백그라운드 정지를 한곳에서 결정한다 (순수 Kotlin, Android 의존 없음).
 *
 * 소리를 멈추는 길은 둘이고 같은 `onStop` 으로 모인다.
 *  - 잠금: 기어가 P가 아님(SDK 기어 신호), **또는 CarUxRestrictions가 주행 제한을 알림**(두 번째 독립 신호), 디버그 강제 잠금
 *    UX 제한(requiresDistractionOptimization=true)은 플랫폼이 주행 최적화 미선언 앱을 가린다는 뜻이다. 그 뒤에서 소리가 계속 나는 게
 *    바로 고친 버그이므로, P로 읽히는데 UX 제한이 걸려도 멈추는 쪽이 맞다(오탐이 아니다). 에뮬레이터 P에서는 false로 측정됐다.
 *  - 비활성: Activity가 onStop(다른 앱으로 전환). onPause·포커스 손실은 쓰지 않는다 — 방향지시등 때 뜨는 사각지대 창에도 오기 때문이다
 * 활성 → 비활성으로 넘어가는 순간 `onStop(사유)`를 정확히 한 번 부른다. 엔진의 Stop은 녹음 중이면 녹음을 끝내고
 * 내용을 유지한다. **잠금 직전에 재생 중이었다면** P로 돌아온 순간 루프 처음부터 재생을 다시 켠다(사용자 결정, PLAN_CHANGES 2026-11-15 (6)).
 * 정지 상태였으면 정지로 두고, 녹음은 이어 가지 않는다. 앱 전환(백그라운드)으로 멈춘 경우는 돌아와도 정지 상태로 둔다.
 * 비활성인 동안 들어오는 입력은 `allowInput()`이 막는다. 플랫폼이 화면을 가려도 SDK 콜백은 계속 오기 때문이다.
 */
class PlaybackGuard(
    private val onStop: (reason: String) -> Unit,
    private val log: (String) -> Unit = {},
    /** 멈추기 직전에 재생 중이었는지. onStop 보다 먼저 읽는다. */
    private val isPlaying: () -> Boolean = { false },
    /** 잠금 때문에 멈췄고 그 직전에 재생 중이었다면, P/전경으로 돌아온 순간 한 번 부른다(루프 처음부터 재생). 녹음은 이어 가지 않는다. */
    private val onResume: () -> Unit = {},
) : GearSink {
    private val lock = Any()
    private var gear = GearKind.UNKNOWN
    private var ux: Boolean? = null
    private var foreground = false
    private var forced = false
    private var wasActive = false
    private var resumeAfterUnlock = false

    private val _locked = MutableStateFlow(false)
    /** 잠금 화면을 띄울지. 백그라운드와 무관하다(화면이 안 보이는 동안은 의미가 없다). */
    val locked: StateFlow<Boolean> = _locked

    private val _gear = MutableStateFlow(GearKind.UNKNOWN)
    val gearState: StateFlow<GearKind> = _gear

    private val _method = MutableStateFlow(GearMethod.NONE_YET)
    val method: StateFlow<GearMethod> = _method

    private val _forced = MutableStateFlow(false)
    val forcedLock: StateFlow<Boolean> = _forced

    val active: Boolean get() = synchronized(lock) { !_locked.value && foreground }

    /** 소리·녹음·재생 요청을 받아도 되는지. */
    fun allowInput(): Boolean = active

    override fun onGear(kind: GearKind, src: String) = update("gear=$kind src=$src") { gear = kind; _gear.value = kind }
    override fun onUx(restricted: Boolean?, src: String) = update("ux=$restricted src=$src") { ux = restricted }
    override fun onMethod(method: GearMethod) { _method.value = method; log("method=$method") }
    fun onForeground(fg: Boolean, src: String) = update("foreground=$fg src=$src") { foreground = fg }
    fun setForcedLock(on: Boolean) = update("forced=$on src=debug") { forced = on; _forced.value = on }

    private inline fun update(what: String, change: () -> Unit) {
        var stopReason: String? = null
        var stopByLock = false
        var resume = false
        synchronized(lock) {
            change()
            val lockedNow = forced || gear == GearKind.NOT_PARK || ux == true
            _locked.value = lockedNow
            val activeNow = !lockedNow && foreground
            log("$what → locked=$lockedNow active=$activeNow")
            if (wasActive && !activeNow) { stopReason = what; stopByLock = lockedNow }
            if (!wasActive && activeNow && resumeAfterUnlock) { resumeAfterUnlock = false; resume = true }
            wasActive = activeNow
        }
        // 락 밖에서 부른다
        stopReason?.let {
            // 재생 여부는 Stop 이 엔진에 가기 전에 읽어야 한다. 앱 전환(백그라운드)으로 멈춘 경우는 이어 가지 않는다.
            val playing = isPlaying()
            synchronized(lock) { resumeAfterUnlock = stopByLock && playing }
            log("stop playing=$playing byLock=$stopByLock resumeAfterUnlock=${stopByLock && playing}")
            onStop(it)
        }
        if (resume) { log("RESUME after unlock"); onResume() }
    }
}
