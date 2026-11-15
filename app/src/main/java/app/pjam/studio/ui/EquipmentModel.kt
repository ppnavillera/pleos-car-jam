package app.pjam.studio.ui

import app.pjam.studio.engine.TrackKind
import app.pjam.studio.input.HvacAssignment
import app.pjam.studio.input.InputKind
import app.pjam.studio.input.SupportState
import app.pjam.studio.input.ToggleSource

enum class EquipState { CONNECTED, CHECKING, UNSUPPORTED, FAILED, UNUSED }

/** "내 차 장비" 한 줄 (기획서 5.2). Android 의존 없음. */
data class EquipRow(
    val kind: InputKind,
    val title: String,
    /** 이 조작이 맡은 역할. 미지원이면 대체 수단을 적는다. */
    val role: String,
    val state: EquipState,
    val stateText: String,
    /** 사유·안내(예: 공조 전원이 꺼져 있음). 없으면 null. */
    val note: String? = null,
)

object EquipmentModel {
    private val ORDER = listOf(
        InputKind.BRAKE, InputKind.TURN_SIGNAL, InputKind.STEERING,
        InputKind.HVAC_POWER, InputKind.AC, InputKind.AUTO, InputKind.RECIRCULATION, InputKind.DEFROSTER,
        InputKind.FAN_SPEED, InputKind.TEMPERATURE, InputKind.WINDOW, InputKind.WIPER, InputKind.DRIVE_MODE,
    )
    private val UNUSED_KINDS = setOf(InputKind.WINDOW, InputKind.WIPER, InputKind.DRIVE_MODE)
    private val TOGGLE_KINDS = HvacAssignment.TOGGLE_ORDER.associateBy { HvacAssignment.kindOf(it) }

    fun title(k: InputKind) = when (k) {
        InputKind.BRAKE -> "브레이크"; InputKind.TURN_SIGNAL -> "방향지시등"; InputKind.STEERING -> "핸들 각도"
        InputKind.AC -> "A/C 버튼"; InputKind.AUTO -> "AUTO 버튼"; InputKind.RECIRCULATION -> "내기순환 버튼"; InputKind.DEFROSTER -> "성에 제거 버튼"
        InputKind.HVAC_POWER -> "공조 전원"
        InputKind.FAN_SPEED -> "풍량"; InputKind.TEMPERATURE -> "설정 온도"; InputKind.WINDOW -> "창문 위치"
        InputKind.WIPER -> "와이퍼"; InputKind.DRIVE_MODE -> "드라이브 모드"
    }

    fun rows(
        support: Map<InputKind, SupportState>,
        assignment: Map<ToggleSource, TrackKind>,
        hvacPower: Boolean?,
        vehicleAvailable: Boolean,
    ): List<EquipRow> = ORDER.map { k -> row(k, support, assignment, hvacPower, vehicleAvailable) }

    private fun row(k: InputKind, support: Map<InputKind, SupportState>, assignment: Map<ToggleSource, TrackKind>, power: Boolean?, available: Boolean): EquipRow {
        if (k in UNUSED_KINDS) {
            val role = if (k == InputKind.DRIVE_MODE) "사용 안 함 · 사운드팩은 화면 버튼으로 바꿔요" else "이번 버전에서는 안 써요 · 화면 조작으로 대체"
            return EquipRow(k, title(k), role, EquipState.UNUSED, "연결 안 함")
        }
        val s = if (!available) SupportState.UNSUPPORTED else support[k] ?: SupportState.UNKNOWN
        val (state, text) = when {
            !available -> EquipState.UNSUPPORTED to "차량 신호 없음"
            s == SupportState.SUPPORTED -> EquipState.CONNECTED to "연결됨"
            s == SupportState.UNKNOWN -> EquipState.CHECKING to "확인 중"
            s == SupportState.FAILED -> EquipState.FAILED to "오류"
            else -> EquipState.UNSUPPORTED to "미지원"
        }
        val connected = state == EquipState.CONNECTED
        val role = when (k) {
            InputKind.BRAKE -> if (connected) "누르면 소리: 킥 / 현재 음 / 임팩트 (선택한 트랙에 따라)" else "화면 패드로 대체"
            InputKind.TURN_SIGNAL -> if (connected) "켜는 순간 소리: 좌=스네어·한 옥타브 아래·이전 음·라이저, 우=하이햇·한 옥타브 위·다음 음·스윕" else "화면 패드로 대체"
            InputKind.STEERING -> if (connected) "BASS는 1도·4도·5도, MELODY는 음역을 고름 (소리는 안 남)" else "화면 3버튼으로 대체"
            InputKind.HVAC_POWER -> "공조가 켜져 있어야 공조 버튼 신호가 와요"
            InputKind.TEMPERATURE -> if (connected) "설정 온도가 조(키)를 정해요 (처음 온도 기준, 0.5°C마다 반음, ±3)" else "화면 조 − / + 버튼으로 조절"
            InputKind.FAN_SPEED -> if (connected) "풍량이 템포를 정해요 (최소 70 · 최대 140 BPM)" else "화면 − / + 버튼으로 템포 조절"
            else -> {
                val src = TOGGLE_KINDS[k]
                val track = src?.let { assignment[it] }
                if (connected && track != null) "${Labels.track(track)} 트랙 켜기/끄기" else "트랙 스위치는 화면으로 대체"
            }
        }
        var note: String? = null
        var stateText = text
        if (k == InputKind.HVAC_POWER && connected) {
            stateText = when (power) { true -> "연결됨 · 켜짐"; false -> "연결됨 · 꺼짐"; null -> "연결됨" }
            if (power == false) note = "공조 전원이 꺼져 있어요. 켜면 A/C·AUTO·내기순환·성에 제거가 들어와요."
        }
        if ((k in TOGGLE_KINDS || k == InputKind.FAN_SPEED || k == InputKind.TEMPERATURE) && connected && power == false) note = "사유: 공조 전원이 꺼져 있어 신호가 안 올 수 있어요."
        if (!available && k != InputKind.HVAC_POWER) note = "화면 조작으로 대체"
        return EquipRow(k, title(k), role, state, stateText, note)
    }
}
