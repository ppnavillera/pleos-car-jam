package app.pjam.studio.engine

import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayMapperTest {
    private val m = PlayMapper()

    @Test fun drum() {
        assertEquals("drum_kick", m.onTrigger(TrackKind.DRUM, TriggerSource.BRAKE))
        assertEquals("drum_snare", m.onTrigger(TrackKind.DRUM, TriggerSource.TURN_LEFT))
        assertEquals("drum_hihat", m.onTrigger(TrackKind.DRUM, TriggerSource.TURN_RIGHT))
    }

    @Test fun bassUsesZoneDegreeAndOctaves() {
        assertEquals("bass_1_o2", m.onTrigger(TrackKind.BASS, TriggerSource.BRAKE)) // 기본 1도
        m.onZone(TrackKind.BASS, SteeringZone.LEFT)
        assertEquals("bass_4_o2", m.onTrigger(TrackKind.BASS, TriggerSource.BRAKE))
        assertEquals("bass_4_o1", m.onTrigger(TrackKind.BASS, TriggerSource.TURN_LEFT))
        m.onZone(TrackKind.BASS, SteeringZone.RIGHT)
        assertEquals("bass_5_o3", m.onTrigger(TrackKind.BASS, TriggerSource.TURN_RIGHT))
        m.onZone(TrackKind.BASS, SteeringZone.CENTER)
        assertEquals("bass_1_o2", m.onTrigger(TrackKind.BASS, TriggerSource.BRAKE))
    }

    @Test fun melodyCursorMovesAndClamps() {
        assertEquals("mel_3", m.onTrigger(TrackKind.MELODY, TriggerSource.BRAKE))
        assertEquals("mel_4", m.onTrigger(TrackKind.MELODY, TriggerSource.TURN_RIGHT))
        assertEquals("mel_3", m.onTrigger(TrackKind.MELODY, TriggerSource.TURN_LEFT))
        m.onZone(TrackKind.MELODY, SteeringZone.RIGHT)
        assertEquals("mel_6", m.onTrigger(TrackKind.MELODY, TriggerSource.BRAKE))
        m.onTrigger(TrackKind.MELODY, TriggerSource.TURN_RIGHT)
        assertEquals("mel_7", m.onTrigger(TrackKind.MELODY, TriggerSource.TURN_RIGHT)) // 끝에서 같은 음
        m.onZone(TrackKind.MELODY, SteeringZone.LEFT)
        assertEquals("mel_0", m.onTrigger(TrackKind.MELODY, TriggerSource.TURN_LEFT))
    }

    @Test fun padMovesCursor() {
        assertEquals("mel_5", m.onPad("mel_5"))
        assertEquals("mel_5", m.onTrigger(TrackKind.MELODY, TriggerSource.BRAKE))
    }

    @Test fun padCountsPerTrack() {
        assertEquals(listOf(4, 3, 8, 4), TrackKind.entries.map { PlayMapper.pads(it).size })
    }
}
