package app.pjam.studio.ui

import app.pjam.studio.engine.TrackKind
import app.pjam.studio.engine.TrackStatus

object Labels {
    fun track(k: TrackKind) = when (k) { TrackKind.DRUM -> "DRUM"; TrackKind.BASS -> "BASS"; TrackKind.MELODY -> "MELODY"; TrackKind.FX -> "FX" }

    /** 이 트랙을 맡은 공조 버튼 (기획서 4.4) */
    fun hvac(k: TrackKind) = when (k) { TrackKind.DRUM -> "A/C"; TrackKind.BASS -> "AUTO"; TrackKind.MELODY -> "내기순환"; TrackKind.FX -> "성에 제거" }

    fun status(s: TrackStatus) = when (s) {
        TrackStatus.EMPTY -> "비어 있음"; TrackStatus.ARMED -> "녹음 대기"; TrackStatus.RECORDING -> "녹음 중"
        TrackStatus.PLAYING -> "재생 중"; TrackStatus.MUTED -> "꺼짐"
    }

    fun sample(id: String): String = when (id) {
        "drum_kick" -> "킥"; "drum_snare" -> "스네어"; "drum_hihat" -> "하이햇"; "drum_clap" -> "클랩"
        "fx_impact" -> "임팩트"; "fx_riser" -> "라이저"; "fx_sweep" -> "스윕"; "fx_zap" -> "잽"
        else -> when {
            id.startsWith("bass_") -> {
                val p = id.split("_"); "${p[1]}도 옥타브${p[2].removePrefix("o")}"
            }
            id.startsWith("mel_") -> listOf("도", "레", "미", "솔", "라", "높은 도", "높은 레", "높은 미")[id.removePrefix("mel_").toInt()]
            else -> id
        }
    }
}
