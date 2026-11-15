package app.pjam.studio.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2-9 강제 예외 주입: 콜백 본문에서 어떤 Throwable이 나도 그 항목만 FAILED가 되고 예외는 밖으로 나가지 않는다. */
class SupportTrackerTest {
    private fun tracker() = SupportTracker(listOf(InputKind.BRAKE, InputKind.AC, InputKind.STEERING))

    @Test fun exceptionMarksOnlyThatItemFailed() {
        val t = tracker()
        t.mark(InputKind.BRAKE, SupportState.SUPPORTED); t.mark(InputKind.AC, SupportState.SUPPORTED)
        val ok = t.guard(InputKind.AC) { throw IllegalStateException("boom") }
        assertFalse(ok)
        assertEquals(SupportState.FAILED, t.get(InputKind.AC))
        assertEquals(SupportState.SUPPORTED, t.get(InputKind.BRAKE))
        assertEquals(SupportState.UNKNOWN, t.get(InputKind.STEERING))
    }

    @Test fun errorsAndLinkageErrorsAreAlsoContained() {
        val t = tracker()
        assertFalse(t.guard(InputKind.BRAKE) { throw NoClassDefFoundError("x") })
        assertFalse(t.guard(InputKind.STEERING) { throw OutOfMemoryError("y") })
        assertEquals(SupportState.FAILED, t.get(InputKind.BRAKE))
        assertEquals(SupportState.FAILED, t.get(InputKind.STEERING))
    }

    @Test fun normalBodyLeavesStateAlone() {
        val t = tracker()
        t.mark(InputKind.AC, SupportState.SUPPORTED)
        var ran = false
        assertTrue(t.guard(InputKind.AC) { ran = true })
        assertTrue(ran)
        assertEquals(SupportState.SUPPORTED, t.get(InputKind.AC))
    }
}
