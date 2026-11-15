package app.pjam.studio.ui

import app.pjam.studio.input.HvacAssignment
import app.pjam.studio.input.InputKind
import app.pjam.studio.input.SupportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EquipmentModelTest {
    private val all = InputKind.entries.associateWith { SupportState.SUPPORTED }

    private fun rows(support: Map<InputKind, SupportState>, power: Boolean? = true, available: Boolean = true) =
        EquipmentModel.rows(support, HvacAssignment.assign(support), power, available).associateBy { it.kind }

    @Test fun everyKindHasARow() {
        assertEquals(InputKind.entries.toSet(), rows(all).keys)
    }

    @Test fun unsupportedItemShowsScreenFallback() {
        val r = rows(all + (InputKind.STEERING to SupportState.UNSUPPORTED))[InputKind.STEERING]!!
        assertEquals(EquipState.UNSUPPORTED, r.state)
        assertTrue(r.role.contains("화면"))
    }

    @Test fun togglesShowAssignedTrack() {
        val r = rows(all)
        assertTrue(r[InputKind.AC]!!.role.contains("DRUM"))
        assertTrue(r[InputKind.DEFROSTER]!!.role.contains("FX"))
        val partial = rows(all + (InputKind.AC to SupportState.UNSUPPORTED) + (InputKind.AUTO to SupportState.UNSUPPORTED))
        assertTrue(partial[InputKind.RECIRCULATION]!!.role.contains("DRUM")) // 남은 지원 항목이 앞 트랙으로
        assertTrue(partial[InputKind.AC]!!.role.contains("화면"))
    }

    @Test fun powerOffExplainsWhyHvacSignalsMayNotArrive() {
        val r = rows(all, power = false)
        assertNotNull(r[InputKind.HVAC_POWER]!!.note)
        assertTrue(r[InputKind.HVAC_POWER]!!.stateText.contains("꺼짐"))
        assertTrue(r[InputKind.AC]!!.note!!.contains("전원"))
        assertTrue(r[InputKind.DEFROSTER]!!.note!!.contains("전원"))
        assertNull(r[InputKind.BRAKE]!!.note)
    }

    @Test fun powerOnHasNoHvacNote() {
        val r = rows(all, power = true)
        assertNull(r[InputKind.AC]!!.note)
        assertNull(r[InputKind.HVAC_POWER]!!.note)
    }

    @Test fun noVehicleSourceMarksEverythingScreenOnly() {
        val r = rows(emptyMap(), available = false)
        assertEquals(EquipState.UNSUPPORTED, r[InputKind.BRAKE]!!.state)
        assertEquals("화면 조작으로 대체", r[InputKind.BRAKE]!!.note)
    }

    @Test fun fanSpeedIsConnectedAndExplainsTempoRole() {
        val r = rows(all)[InputKind.FAN_SPEED]!!
        assertEquals(EquipState.CONNECTED, r.state)
        assertTrue(r.role.contains("템포"))
        assertTrue(rows(all + (InputKind.FAN_SPEED to SupportState.UNSUPPORTED))[InputKind.FAN_SPEED]!!.role.contains("화면"))
    }

    @Test fun unusedItemsAreLabelledNotConnected() {
        val r = rows(all)
        for (k in listOf(InputKind.TEMPERATURE, InputKind.WINDOW, InputKind.WIPER, InputKind.DRIVE_MODE)) {
            assertEquals(EquipState.UNUSED, r[k]!!.state)
        }
    }
}
