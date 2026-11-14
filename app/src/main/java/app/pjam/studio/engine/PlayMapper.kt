package app.pjam.studio.engine

import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.TriggerSource

/** 연주 입력이 소리를 내는 방식 (기획서 4.3 + 계획서 3.7 확정안). 순수 Kotlin. */
class PlayMapper {
    var bassDegree = 1
        private set
    var melodyCursor = 3
        private set

    /** 방향지시등·브레이크 Trigger → 재생할 sampleId. */
    fun onTrigger(kind: TrackKind, source: TriggerSource): String = when (kind) {
        TrackKind.DRUM -> when (source) {
            TriggerSource.BRAKE -> "drum_kick"
            TriggerSource.TURN_LEFT -> "drum_snare"
            TriggerSource.TURN_RIGHT -> "drum_hihat"
        }
        TrackKind.BASS -> when (source) {
            TriggerSource.BRAKE -> bass(bassDegree, 2)
            TriggerSource.TURN_LEFT -> bass(bassDegree, 1)
            TriggerSource.TURN_RIGHT -> bass(bassDegree, 3)
        }
        TrackKind.MELODY -> {
            when (source) {
                TriggerSource.TURN_LEFT -> melodyCursor = (melodyCursor - 1).coerceAtLeast(0)
                TriggerSource.TURN_RIGHT -> melodyCursor = (melodyCursor + 1).coerceAtMost(MELODY_NOTES - 1)
                TriggerSource.BRAKE -> Unit
            }
            "mel_$melodyCursor"
        }
        TrackKind.FX -> when (source) {
            TriggerSource.BRAKE -> "fx_impact"
            TriggerSource.TURN_LEFT -> "fx_riser"
            TriggerSource.TURN_RIGHT -> "fx_sweep"
        }
    }

    /** 핸들 구간: 소리를 내지 않고 현재 음/커서만 옮긴다. */
    fun onZone(kind: TrackKind, zone: SteeringZone) {
        when (kind) {
            TrackKind.BASS -> bassDegree = when (zone) { SteeringZone.CENTER -> 1; SteeringZone.LEFT -> 4; SteeringZone.RIGHT -> 5 }
            TrackKind.MELODY -> melodyCursor = when (zone) { SteeringZone.LEFT -> 0; SteeringZone.CENTER -> 3; SteeringZone.RIGHT -> 6 }
            else -> Unit
        }
    }

    /** 화면 패드를 누름: 누른 음을 연주하고 현재 음/커서도 그 음으로 옮긴다. */
    fun onPad(sampleId: String): String {
        if (sampleId.startsWith("mel_")) melodyCursor = sampleId.removePrefix("mel_").toInt()
        if (sampleId.startsWith("bass_")) bassDegree = sampleId.split("_")[1].toInt()
        return sampleId
    }

    companion object {
        const val MELODY_NOTES = 8
        private fun bass(degree: Int, octave: Int) = "bass_${degree}_o$octave"

        /** 트랙별 화면 패드 (기획서 4.3 / 계획서 3.7). */
        fun pads(kind: TrackKind): List<String> = when (kind) {
            TrackKind.DRUM -> listOf("drum_kick", "drum_snare", "drum_hihat", "drum_clap")
            TrackKind.BASS -> listOf("bass_1_o2", "bass_4_o2", "bass_5_o2")
            TrackKind.MELODY -> (0 until MELODY_NOTES).map { "mel_$it" }
            TrackKind.FX -> listOf("fx_impact", "fx_riser", "fx_sweep", "fx_zap")
        }
    }
}
