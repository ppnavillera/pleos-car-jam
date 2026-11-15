package app.pjam.studio.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 항목별 지원 상태. 콜백 안에서 예외가 나면 그 항목만 FAILED로 바꾸고 앱은 계속 돈다 (T2-9, 기획서 7.1 마지막 항목).
 * SDK 콜백은 Binder 스레드에서 오므로 여기서 던진 예외를 삼키지 않으면 프로세스가 죽을 수 있다. 순수 Kotlin.
 */
class SupportTracker(initial: Collection<InputKind>, private val log: (String) -> Unit = {}) {
    private val _state = MutableStateFlow(initial.associateWith { SupportState.UNKNOWN })
    val state: StateFlow<Map<InputKind, SupportState>> = _state

    fun get(kind: InputKind): SupportState = _state.value[kind] ?: SupportState.UNKNOWN

    fun mark(kind: InputKind, s: SupportState) {
        while (true) {
            val cur = _state.value
            if (_state.compareAndSet(cur, cur + (kind to s))) return
        }
    }

    fun fail(kind: InputKind, t: Throwable) {
        log("$kind failed: $t")
        mark(kind, SupportState.FAILED)
    }

    /** body를 실행하고, 어떤 Throwable이 나도 그 항목만 FAILED로 표시한다. 정상이면 true. */
    inline fun guard(kind: InputKind, body: () -> Unit): Boolean = try { body(); true } catch (t: Throwable) { fail(kind, t); false }
}
