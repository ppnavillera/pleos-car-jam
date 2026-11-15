package app.pjam.studio.input

import app.pjam.studio.engine.TrackKind
import app.pjam.studio.input.ToggleSource.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HvacAssignmentTest {
    private fun sup(vararg kinds: InputKind) = InputKind.entries.associateWith { if (it in kinds) SupportState.SUPPORTED else SupportState.UNSUPPORTED }

    @Test fun allFourSupportedUsesDefaultTable() {
        val a = HvacAssignment.assign(sup(InputKind.AC, InputKind.AUTO, InputKind.RECIRCULATION, InputKind.DEFROSTER))
        assertEquals(mapOf(AC to TrackKind.DRUM, AUTO to TrackKind.BASS, RECIRCULATION to TrackKind.MELODY, DEFROSTER to TrackKind.FX), a)
    }

    /** 계획서 T2-5 완료 확인: 4종 중 2종만 지원하면 지원되는 항목이 DRUM·BASS에 배정된다. */
    @Test fun twoSupportedGoToDrumAndBass() {
        val a = HvacAssignment.assign(sup(InputKind.RECIRCULATION, InputKind.DEFROSTER))
        assertEquals(mapOf(RECIRCULATION to TrackKind.DRUM, DEFROSTER to TrackKind.BASS), a)
        assertNull(HvacAssignment.sourceOf(TrackKind.MELODY, a)) // 남는 트랙은 화면 스위치만
        assertNull(HvacAssignment.sourceOf(TrackKind.FX, a))
        assertEquals(RECIRCULATION, HvacAssignment.sourceOf(TrackKind.DRUM, a))
    }

    @Test fun noneSupportedMeansScreenSwitchesOnly() {
        assertEquals(emptyMap<ToggleSource, TrackKind>(), HvacAssignment.assign(sup()))
    }

    @Test fun unknownAndFailedAreNotAssigned() {
        val m = mapOf(InputKind.AC to SupportState.UNKNOWN, InputKind.AUTO to SupportState.FAILED, InputKind.RECIRCULATION to SupportState.SUPPORTED)
        assertEquals(mapOf(RECIRCULATION to TrackKind.DRUM), HvacAssignment.assign(m))
    }
}
