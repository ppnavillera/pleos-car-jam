package app.pjam.studio.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGuardTest {
    private val stops = ArrayList<String>()
    private val guard = PlaybackGuard(onStop = { stops += it })

    private fun foregroundInPark() {
        guard.onGear(GearKind.PARK, "t"); guard.onForeground(true, "t")
    }

    @Test fun activeInParkAndForeground() {
        foregroundInPark()
        assertTrue(guard.active); assertFalse(guard.locked.value); assertTrue(guard.allowInput())
    }

    @Test fun leavingParkLocksAndStopsExactlyOnce() {
        foregroundInPark()
        guard.onGear(GearKind.NOT_PARK, "sdk")
        assertTrue(guard.locked.value); assertFalse(guard.allowInput())
        assertEquals(1, stops.size)
        guard.onUx(true, "ux") // 뒤따르는 UX 제한 신호는 다시 멈추지 않는다
        guard.onGear(GearKind.NOT_PARK, "sdk")
        assertEquals(1, stops.size)
    }

    @Test fun returningToParkUnlocksButDoesNotRestartAnything() {
        foregroundInPark()
        guard.onGear(GearKind.NOT_PARK, "sdk")
        guard.onGear(GearKind.PARK, "sdk")
        assertFalse(guard.locked.value); assertTrue(guard.allowInput())
        assertEquals(1, stops.size) // 정지 호출은 잠글 때 한 번뿐, 재생을 켜는 콜백은 없다
    }

    @Test fun backgroundStopsAndBlocksInputUntilForegroundAgain() {
        foregroundInPark()
        guard.onForeground(false, "onStop")
        assertEquals(1, stops.size); assertFalse(guard.allowInput())
        guard.onForeground(false, "onStop")
        assertEquals(1, stops.size)
        guard.onForeground(true, "onResume")
        assertTrue(guard.allowInput())
    }

    @Test fun lockedWhileBackgroundedDoesNotStopTwice() {
        foregroundInPark()
        guard.onForeground(false, "onStop")
        guard.onGear(GearKind.NOT_PARK, "sdk")
        assertEquals(1, stops.size)
        assertTrue(guard.locked.value)
    }

    @Test fun unknownGearLocksOnlyWhenUxRestricted() {
        guard.onForeground(true, "t")
        guard.onUx(false, "ux")
        assertFalse(guard.locked.value)
        guard.onUx(true, "ux")
        assertTrue(guard.locked.value); assertEquals(1, stops.size)
    }

    @Test fun knownParkIgnoresUxRestriction() {
        foregroundInPark()
        guard.onUx(true, "ux")
        assertFalse(guard.locked.value); assertTrue(guard.active)
    }

    @Test fun forcedLockForRehearsalStopsAndUnlocks() {
        foregroundInPark()
        guard.setForcedLock(true)
        assertTrue(guard.locked.value); assertEquals(1, stops.size)
        guard.setForcedLock(false)
        assertFalse(guard.locked.value); assertTrue(guard.allowInput())
    }
}
