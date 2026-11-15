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

    @Test fun uxRestrictionIsASecondIndependentStopSignalEvenWhenGearSaysPark() {
        // SDK 기어 콜백을 놓치거나 아직 P로 읽히는 동안에도 플랫폼이 앱을 가리면(UX 제한) 소리를 멈춘다
        foregroundInPark()
        guard.onUx(true, "ux")
        assertTrue(guard.locked.value); assertFalse(guard.allowInput())
        assertEquals(1, stops.size)
        guard.onUx(false, "ux")
        assertFalse(guard.locked.value); assertTrue(guard.allowInput())
        assertEquals(1, stops.size) // 풀려도 재생을 다시 켜지 않는다
    }

    @Test fun uxThenGearSignalsStopOnlyOnce() {
        foregroundInPark()
        guard.onUx(true, "ux")
        guard.onGear(GearKind.NOT_PARK, "sdk")
        assertEquals(1, stops.size)
    }

    @Test fun forcedLockForRehearsalStopsAndUnlocks() {
        foregroundInPark()
        guard.setForcedLock(true)
        assertTrue(guard.locked.value); assertEquals(1, stops.size)
        guard.setForcedLock(false)
        assertFalse(guard.locked.value); assertTrue(guard.allowInput())
    }

    // ---- P 복귀 시 재생 이어 가기 (사용자 결정, PLAN_CHANGES 2026-11-15 (6)) ----

    private var playing = false
    private val resumes = ArrayList<String>()
    private val order = ArrayList<String>()
    private val resumeGuard = PlaybackGuard(
        onStop = { order += "stop" },
        isPlaying = { order += "read-playing"; playing },
        onResume = { resumes += "r"; order += "resume" },
    )

    private fun resumeForegroundInPark() { resumeGuard.onGear(GearKind.PARK, "t"); resumeGuard.onForeground(true, "t") }

    @Test fun resumesOncePlayingBeforeLockAndReturningToPark() {
        resumeForegroundInPark(); playing = true
        resumeGuard.onGear(GearKind.NOT_PARK, "sdk")
        assertEquals(0, resumes.size)
        resumeGuard.onGear(GearKind.PARK, "sdk")
        assertEquals(1, resumes.size)
        resumeGuard.onGear(GearKind.PARK, "sdk") // 같은 신호가 또 와도 다시 켜지 않는다
        assertEquals(1, resumes.size)
    }

    @Test fun readsPlayingBeforeStopIsSent() {
        resumeForegroundInPark(); playing = true
        resumeGuard.onGear(GearKind.NOT_PARK, "sdk")
        assertEquals(listOf("read-playing", "stop"), order)
    }

    @Test fun staysStoppedWhenNotPlayingBeforeLock() {
        resumeForegroundInPark(); playing = false
        resumeGuard.onGear(GearKind.NOT_PARK, "sdk")
        resumeGuard.onGear(GearKind.PARK, "sdk")
        assertEquals(0, resumes.size)
    }

    @Test fun doesNotResumeAfterBackgroundSwitch() {
        resumeForegroundInPark(); playing = true
        resumeGuard.onForeground(false, "onStop")
        resumeGuard.onForeground(true, "onStart")
        assertEquals(0, resumes.size)
    }

    @Test fun backgroundThenLockThenParkDoesNotResume() {
        resumeForegroundInPark(); playing = true
        resumeGuard.onForeground(false, "onStop")
        resumeGuard.onGear(GearKind.NOT_PARK, "sdk")
        resumeGuard.onGear(GearKind.PARK, "sdk")
        resumeGuard.onForeground(true, "onStart")
        assertEquals(0, resumes.size)
    }

    @Test fun uxAndGearBothClearedBeforeResume() {
        resumeForegroundInPark(); playing = true
        resumeGuard.onGear(GearKind.NOT_PARK, "sdk"); resumeGuard.onUx(true, "ux")
        resumeGuard.onGear(GearKind.PARK, "sdk")
        assertEquals(0, resumes.size) // UX 제한이 아직 걸려 있다
        resumeGuard.onUx(false, "ux")
        assertEquals(1, resumes.size)
    }

    @Test fun lockWhileAlreadyStoppedKeepsStopped() {
        resumeForegroundInPark(); playing = true
        resumeGuard.onGear(GearKind.NOT_PARK, "sdk")
        playing = false // 엔진이 이미 멈춘 뒤 다시 잠금 신호가 와도 재생 여부를 덮어쓰지 않는다
        resumeGuard.onUx(true, "ux")
        resumeGuard.onGear(GearKind.PARK, "sdk"); resumeGuard.onUx(false, "ux")
        assertEquals(1, resumes.size)
    }
}
