package app.pjam.studio.input

import app.pjam.studio.engine.TrackKind

/**
 * 공조 버튼 → 트랙 배정 (기획서 4.4, 6.3). Android 의존 없음.
 * 4종이 모두 지원되면 A/C→DRUM, AUTO→BASS, 내기순환→MELODY, 성에 제거→FX.
 * 일부만 지원되면 지원되는 항목을 이 순서대로 DRUM→BASS→MELODY→FX에 차례로 배정한다(남는 트랙은 화면 스위치만).
 */
object HvacAssignment {
    val TOGGLE_ORDER = listOf(ToggleSource.AC, ToggleSource.AUTO, ToggleSource.RECIRCULATION, ToggleSource.DEFROSTER)

    fun kindOf(source: ToggleSource): InputKind = when (source) {
        ToggleSource.AC -> InputKind.AC
        ToggleSource.AUTO -> InputKind.AUTO
        ToggleSource.RECIRCULATION -> InputKind.RECIRCULATION
        ToggleSource.DEFROSTER -> InputKind.DEFROSTER
    }

    fun supportedToggles(support: Map<InputKind, SupportState>): Set<ToggleSource> =
        TOGGLE_ORDER.filter { support[kindOf(it)] == SupportState.SUPPORTED }.toSet()

    fun assign(supported: Set<ToggleSource>): Map<ToggleSource, TrackKind> {
        val tracks = TrackKind.entries.iterator()
        val out = LinkedHashMap<ToggleSource, TrackKind>()
        for (s in TOGGLE_ORDER) if (s in supported && tracks.hasNext()) out[s] = tracks.next()
        return out
    }

    fun assign(support: Map<InputKind, SupportState>): Map<ToggleSource, TrackKind> = assign(supportedToggles(support))

    /** 이 트랙을 맡은 공조 버튼. 없으면 null(화면 스위치만). */
    fun sourceOf(track: TrackKind, assignment: Map<ToggleSource, TrackKind>): ToggleSource? =
        assignment.entries.firstOrNull { it.value == track }?.key
}
