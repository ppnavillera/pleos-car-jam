package app.pjam.studio.sound

import app.pjam.studio.ui.Labels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoundPacksTest {
    private fun packDir(id: String) = listOf(File("../assets/soundpacks/$id"), File("assets/soundpacks/$id")).first { it.isDirectory }

    @Test fun nextCyclesAndFallsBackToDefault() {
        assertEquals(SoundPacks.CAR, SoundPacks.next(SoundPacks.DEFAULT))
        assertEquals(SoundPacks.DEFAULT, SoundPacks.next(SoundPacks.CAR))
        assertEquals(SoundPacks.DEFAULT, SoundPacks.next("nonexistent")) // 모르는 팩이면 기본으로
        assertTrue(SoundPacks.isKnown("car")); assertTrue(!SoundPacks.isKnown("electronic"))
    }

    /** 앱 코드는 sampleId에 의존하므로 두 팩의 파일 이름이 정확히 같아야 한다 (팩 전환은 이름 갈아 끼우기). */
    @Test fun everyPackHasExactlyTheSameSampleIds() {
        val basic = packDir("basic").list()!!.filter { it.endsWith(".wav") }.sorted()
        for (id in SoundPacks.ALL) {
            assertEquals("pack $id", basic, packDir(id).list()!!.filter { it.endsWith(".wav") }.sorted())
        }
        assertEquals(27, basic.size)
    }

    @Test fun carPackLabelsDiffer() {
        for (id in listOf("drum_kick", "drum_snare", "drum_hihat", "drum_clap", "fx_impact", "fx_riser", "fx_sweep", "fx_zap")) {
            assertNotEquals(id, Labels.sample(id), Labels.sample(id, "car"))
        }
        assertEquals("도어", Labels.sample("drum_kick", "car"))
        assertEquals("킥", Labels.sample("drum_kick", "basic"))
        assertTrue(Labels.sample("bass_4_o2", "car").startsWith("경적"))
    }
}
