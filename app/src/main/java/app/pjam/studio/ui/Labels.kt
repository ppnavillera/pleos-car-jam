package app.pjam.studio.ui

import app.pjam.studio.engine.TrackKind
import app.pjam.studio.engine.TrackStatus
import app.pjam.studio.input.HvacAssignment
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource

object Labels {
    fun track(k: TrackKind) = when (k) { TrackKind.DRUM -> "DRUM"; TrackKind.BASS -> "BASS"; TrackKind.MELODY -> "MELODY"; TrackKind.FX -> "FX" }

    fun toggle(s: ToggleSource) = when (s) { ToggleSource.AC -> "A/C"; ToggleSource.AUTO -> "AUTO"; ToggleSource.RECIRCULATION -> "내기순환"; ToggleSource.DEFROSTER -> "성에 제거" }

    /** 이 트랙을 맡은 공조 버튼 이름. 배정이 없으면 "화면 스위치" (기획서 4.4, 6.3). */
    fun hvacFor(k: TrackKind, assignment: Map<ToggleSource, TrackKind>): String =
        HvacAssignment.sourceOf(k, assignment)?.let { toggle(it) } ?: "화면 스위치"

    /** 핸들 구간이 정하는 음 (계획서 3.7). */
    fun zone(k: TrackKind, z: SteeringZone): String = when (k) {
        TrackKind.BASS -> when (z) { SteeringZone.LEFT -> "4도"; SteeringZone.CENTER -> "1도"; SteeringZone.RIGHT -> "5도" }
        TrackKind.MELODY -> when (z) { SteeringZone.LEFT -> "낮은 음역"; SteeringZone.CENTER -> "중간 음역"; SteeringZone.RIGHT -> "높은 음역" }
        else -> when (z) { SteeringZone.LEFT -> "좌"; SteeringZone.CENTER -> "중앙"; SteeringZone.RIGHT -> "우" }
    }

    fun status(s: TrackStatus) = when (s) {
        TrackStatus.EMPTY -> "비어 있음"; TrackStatus.ARMED -> "녹음 대기"; TrackStatus.RECORDING -> "녹음 중"
        TrackStatus.PLAYING -> "재생 중"; TrackStatus.MUTED -> "꺼짐"
    }

    /** 선택한 트랙에서 차량 조작이 내는 소리 (기획서 4.3, 계획서 3.7). */
    fun guide(k: TrackKind): String = when (k) {
        TrackKind.DRUM -> "브레이크 = 킥 · 방향지시등 좌 = 스네어 · 방향지시등 우 = 하이햇"
        TrackKind.BASS -> "핸들로 음 고르기(가운데 1도 · 왼쪽 4도 · 오른쪽 5도) → 브레이크 = 그 음 · 방향지시등 좌 = 한 옥타브 아래 · 우 = 한 옥타브 위"
        TrackKind.MELODY -> "핸들로 음역 고르기(왼쪽 낮음 · 가운데 중간 · 오른쪽 높음) → 브레이크 = 그 음 · 방향지시등 좌 = 이전 음 · 우 = 다음 음"
        TrackKind.FX -> "브레이크 = 임팩트 · 방향지시등 좌 = 라이저 · 방향지시등 우 = 스윕"
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
