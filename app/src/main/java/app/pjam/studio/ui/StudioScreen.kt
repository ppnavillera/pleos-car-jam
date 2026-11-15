package app.pjam.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pjam.studio.engine.EngineState
import app.pjam.studio.engine.PlayMapper
import app.pjam.studio.engine.STEPS_PER_BAR
import app.pjam.studio.engine.STEPS_PER_LOOP
import app.pjam.studio.engine.TrackKind
import app.pjam.studio.engine.TrackState
import app.pjam.studio.engine.TrackStatus
import app.pjam.studio.input.InputMode
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource
import kotlinx.coroutines.delay

internal val Bg = Color(0xFF101418)
internal val CardBg = Color(0xFF1A2128)
internal val CellOff = Color(0xFF232C35)
internal val CellBeat = Color(0xFF2E3944)
internal val TextDim = Color(0xFF9AA7B3)

/**
 * 방향지시등을 켜면 플랫폼이 사각지대 카메라 창을 앱 영역의 왼쪽 위에 띄운다 (FINDINGS "방향지시등 카메라 창").
 * 앱 창 기준 약 752×423, 켜 둔 동안 계속 떠 있다. 넓은 화면은 이 구역(왼쪽 열 위쪽)에 제목·안내만 두고,
 * 트랙 카드·재생·녹음 버튼·입력 표시줄·패드는 모두 이 구역 밖에 둔다. check.sh가 노드 bounds로 겹침을 확인한다.
 */
internal val CameraZoneWidth = 744.dp
internal val CameraZoneHeight = 430.dp
/** 이 폭 이상이면 두 열 배치(카메라 안전 구역 포함). 미만이면 한 열 + 세로 스크롤. */
private val WideBreakpoint = 1400.dp

internal fun accent(k: TrackKind) = when (k) {
    TrackKind.DRUM -> Color(0xFFFF8A3D)
    TrackKind.BASS -> Color(0xFF4DA3FF)
    TrackKind.MELODY -> Color(0xFF5BD68B)
    TrackKind.FX -> Color(0xFFE05BD6)
}

@Composable
fun StudioScreen(vm: StudioViewModel, debugPanel: Boolean) {
    val st by vm.engineState.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val bar by vm.inputBar.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val gear by vm.gear.collectAsStateWithLifecycle()
    val method by vm.gearMethod.collectAsStateWithLifecycle()
    val inputMode by vm.inputMode.collectAsStateWithLifecycle()
    val assignment by vm.toggleAssignment.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val pack by vm.settings.collectAsStateWithLifecycle()

    val screenOnly = inputMode == InputMode.SCREEN_ONLY
    val gearLabel = gearText(gear, method) + if (screenOnly) " · 차량 신호 없음, 화면 조작" else ""

    MaterialTheme(colorScheme = darkColorScheme()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Bg).semantics { contentDescription = "pjam_root" }) {
            val wide = maxWidth >= WideBreakpoint
            when {
                page == Page.EQUIPMENT -> EquipmentScreen(vm, wide, gearLabel)
                page == Page.LIBRARY -> LibraryScreen(vm, wide)
                page == Page.SETTINGS -> SettingsScreen(vm, wide)
                wide -> WideStudio(st, sel, bar?.text, assignment, gearLabel, screenOnly, vm, debugPanel)
                else -> NarrowStudio(st, sel, bar?.text, assignment, gearLabel, screenOnly, vm, debugPanel)
            }
            if (locked) LockScreen(gearLabel, vm)
        }
    }
}

// ------------------------------------------------------------------ 배치

@Composable
private fun WideStudio(st: EngineState, sel: TrackKind, barText: String?, assignment: Map<ToggleSource, TrackKind>, gearLabel: String, screenOnly: Boolean, vm: StudioViewModel, debugPanel: Boolean) {
    Row(
        Modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = "studio_screen" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 왼쪽 열: 위쪽은 카메라 안전 구역(필수 조작 없음), 그 아래에 입력 표시줄·패드
        Column(Modifier.width(CameraZoneWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().height(CameraZoneHeight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("P-JAM", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(gearLabel, fontSize = 14.sp, color = TextDim, modifier = Modifier.semantics { contentDescription = "gear_text" })
                if (screenOnly) ScreenOnlyNotice()
                Spacer(Modifier.height(8.dp))
                PlayGuide(sel)
            }
            InputBar(barText)
            Column(Modifier.weight(1f)) { PadArea(sel, vm, fill = true) }
            if (debugPanel) DebugPanel(vm)
        }
        // 오른쪽 열: 상태·템포·버튼, 트랙 카드 4개 (카메라 창의 오른쪽 끝보다 오른쪽)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusText(st)
                Spacer(Modifier.weight(1f))
                TempoControls(st, vm)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TransportButtons(st, sel, vm, height = 64.dp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageButtons(vm, height = 52.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackKind.entries.forEach { k ->
                    TrackCard(Modifier.weight(1f), st.tracks.first { it.kind == k }, st, k == sel, vm, assignment, labelWidth = 190.dp)
                }
            }
        }
    }
}

/** 좁은 창(멀티 윈도우·세로 화면): 한 열, 세로 스크롤. 이 배치에서 카메라 창이 어떻게 뜨는지는 측정하지 못했다(FINDINGS). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NarrowStudio(st: EngineState, sel: TrackKind, barText: String?, assignment: Map<ToggleSource, TrackKind>, gearLabel: String, screenOnly: Boolean, vm: StudioViewModel, debugPanel: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).semantics { contentDescription = "studio_screen" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("P-JAM", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(gearLabel, fontSize = 12.sp, color = TextDim, modifier = Modifier.semantics { contentDescription = "gear_text" })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            StatusText(st)
            TempoControls(st, vm)
            TransportButtons(st, sel, vm, height = 52.dp)
            PageButtons(vm, height = 52.dp)
        }
        TrackKind.entries.forEach { k ->
            TrackCard(Modifier.height(150.dp), st.tracks.first { it.kind == k }, st, k == sel, vm, assignment, labelWidth = 150.dp)
        }
        InputBar(barText)
        PadArea(sel, vm, fill = false)
        if (screenOnly) ScreenOnlyNotice()
        PlayGuide(sel)
        if (debugPanel) DebugPanel(vm)
    }
}

// ------------------------------------------------------------------ 조각

@Composable
private fun ScreenOnlyNotice() {
    Text("차량 신호를 쓸 수 없어요. 화면 패드와 버튼으로 연주해요.", fontSize = 18.sp, color = Color(0xFFFFB74D),
        modifier = Modifier.semantics { contentDescription = "screen_only_notice" })
}

@Composable
private fun PlayGuide(sel: TrackKind) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${Labels.track(sel)} 연주 방법", fontSize = 18.sp, color = accent(sel), fontWeight = FontWeight.Bold)
        Text(Labels.guide(sel), fontSize = 20.sp, color = TextDim)
        Text("방향지시등을 켜면 차량이 카메라 창을 화면 왼쪽 위에 띄워요. 조작 버튼은 그 아래·오른쪽에 있어요.", fontSize = 14.sp, color = Color(0xFF6F7C88))
    }
}

@Composable
private fun StatusText(st: EngineState) {
    val statusText = when {
        st.countInBeat > 0 -> "카운트인 ${st.countInBeat}/4"
        st.recordingKind != null && st.tracks.first { it.kind == st.recordingKind }.status == TrackStatus.RECORDING -> "● 녹음 중 ${Labels.track(st.recordingKind!!)}"
        st.recordingKind != null -> "녹음 대기 ${Labels.track(st.recordingKind!!)}"
        st.playing -> "재생 중"
        else -> "정지"
    }
    Text(
        statusText, fontSize = 22.sp, color = if (st.recordingKind != null) Color(0xFFFF5A5A) else Color.White,
        modifier = Modifier.semantics { contentDescription = "status_text" },
    )
}

@Composable
private fun TempoControls(st: EngineState, vm: StudioViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.bpmDelta(-5) }, modifier = Modifier.semantics { contentDescription = "btn_bpm_down" }) { Text("−", fontSize = 24.sp) }
        Text("${st.bpm} BPM", fontSize = 22.sp, color = Color.White, modifier = Modifier.semantics { contentDescription = "bpm_text" })
        OutlinedButton(onClick = { vm.bpmDelta(+5) }, modifier = Modifier.semantics { contentDescription = "btn_bpm_up" }) { Text("+", fontSize = 24.sp) }
    }
}

@Composable
private fun TransportButtons(st: EngineState, sel: TrackKind, vm: StudioViewModel, height: Dp) {
    Button(onClick = { vm.togglePlay() }, modifier = Modifier.height(height).semantics { contentDescription = "btn_play" }) {
        Text(if (st.playing) "■ 정지" else "▶ 재생", fontSize = 22.sp)
    }
    val recActive = st.recordingKind == sel
    Button(
        onClick = { vm.toggleRecord() },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
        modifier = Modifier.height(height).semantics { contentDescription = "btn_record" },
    ) { Text(if (recActive) "■ 녹음 종료" else "● 녹음 ${Labels.track(sel)}", fontSize = 22.sp) }
    OutlinedButton(onClick = { vm.clearAll() }, modifier = Modifier.height(height).semantics { contentDescription = "btn_clear_all" }) { Text("전체 지우기", fontSize = 18.sp) }
    OutlinedButton(onClick = { vm.openEquipment(true) }, modifier = Modifier.height(height).semantics { contentDescription = "btn_equipment" }) { Text("내 차 장비", fontSize = 18.sp) }
}

/** 저장·보관함·설정 (T3-2, T3-3). */
@Composable
private fun PageButtons(vm: StudioViewModel, height: Dp) {
    val pack = vm.settings.collectAsStateWithLifecycle().value.soundPackId
    OutlinedButton(onClick = { vm.cyclePack() }, modifier = Modifier.height(height).semantics { contentDescription = "btn_pack_$pack" }) {
        Text("사운드팩: ${app.pjam.studio.sound.SoundPacks.label(pack)}", fontSize = 18.sp)
    }
    OutlinedButton(onClick = { vm.saveCurrent() }, modifier = Modifier.height(height).semantics { contentDescription = "btn_save" }) { Text("곡 저장", fontSize = 18.sp) }
    OutlinedButton(onClick = { vm.openPage(Page.LIBRARY) }, modifier = Modifier.height(height).semantics { contentDescription = "btn_library" }) { Text("보관함", fontSize = 18.sp) }
    OutlinedButton(onClick = { vm.openPage(Page.SETTINGS) }, modifier = Modifier.height(height).semantics { contentDescription = "btn_settings" }) { Text("설정", fontSize = 18.sp) }
}

@Composable
private fun TrackCard(modifier: Modifier, t: TrackState, st: EngineState, selected: Boolean, vm: StudioViewModel, assignment: Map<ToggleSource, TrackKind>, labelWidth: Dp) {
    val color = accent(t.kind)
    Row(
        modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) color else Color(0xFF2B343D)), RoundedCornerShape(14.dp))
            .clickable { vm.select(t.kind) }
            .semantics { contentDescription = "track_card_${t.kind}${if (selected) "_selected" else ""}" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(labelWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Labels.track(t.kind), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color, softWrap = false)
            Text("스위치: " + Labels.hvacFor(t.kind, assignment), fontSize = 13.sp, color = TextDim, softWrap = false, modifier = Modifier.semantics { contentDescription = "hvac_label_${t.kind}" })
            Text(Labels.status(t.status), fontSize = 16.sp, color = Color.White, modifier = Modifier.semantics { contentDescription = "status_${t.kind}_${t.status}" })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = !t.muted, onCheckedChange = { vm.toggleMute(t.kind) }, modifier = Modifier.semantics { contentDescription = "switch_${t.kind}_${if (t.muted) "off" else "on"}" })
                OutlinedButton(onClick = { vm.clear(t.kind) }, modifier = Modifier.semantics { contentDescription = "btn_clear_${t.kind}" }) { Text("지우기") }
            }
        }
        StepGrid(Modifier.weight(1f).fillMaxHeight(), t, color, playhead = if (st.playing) st.step else -1)
    }
}

@Composable
private fun StepGrid(modifier: Modifier, t: TrackState, color: Color, playhead: Int) {
    val on = remember(t.notes) { t.notes.map { it.step }.toSet() }
    Row(modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (bar in 0 until STEPS_PER_LOOP / STEPS_PER_BAR) {
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until STEPS_PER_BAR) {
                    val step = bar * STEPS_PER_BAR + i
                    val isOn = step in on
                    val isHead = step == playhead
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isOn) color else if (i % 4 == 0) CellBeat else CellOff, RoundedCornerShape(3.dp))
                            .then(if (isHead) Modifier.border(3.dp, Color.White, RoundedCornerShape(3.dp)) else Modifier)
                            .semantics { contentDescription = "cell_${t.kind}_${step}_${if (isOn) "on" else "off"}" },
                    )
                }
            }
        }
    }
}

@Composable
private fun InputBar(text: String?) {
    Box(
        Modifier.fillMaxWidth().height(60.dp).background(Color(0xFF0B3A4A), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp)
            .semantics { contentDescription = "input_bar" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text ?: "차량 조작이나 화면 패드를 누르면 여기에 표시돼요", fontSize = 22.sp, color = if (text == null) TextDim else Color.White)
    }
}

/** 선택한 트랙의 화면 패드 + (BASS·MELODY) 핸들 대체 3버튼. fill이면 남은 높이를 패드 줄이 나눠 가진다. */
@Composable
private fun PadArea(kind: TrackKind, vm: StudioViewModel, fill: Boolean) {
    val color = accent(kind)
    val pack = vm.settings.collectAsStateWithLifecycle().value.soundPackId
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (kind == TrackKind.BASS || kind == TrackKind.MELODY) {
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("핸들 대체", fontSize = 14.sp, color = TextDim, modifier = Modifier.width(72.dp))
                SteeringZone.entries.forEach { z ->
                    OutlinedButton(
                        onClick = { vm.onScreenZone(z) },
                        modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "zone_btn_$z" },
                    ) { Text(Labels.zone(kind, z), fontSize = 18.sp) }
                }
            }
        }
        PlayMapper.pads(kind).chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(if (fill) 150.dp else 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { id -> Pad(Modifier.weight(1f).fillMaxHeight(), id, color, vm, pack) }
                // 마지막 줄이 4개보다 적어도 폭이 같게(패드가 한 줄 4칸 기준). 3개짜리(BASS)는 3등분
                if (row.size < 4 && kind != TrackKind.BASS) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 누르는 순간(pointer-down)에 시각을 찍는다. clickable은 뗄 때 반응하므로 쓰지 않는다. */
@Composable
private fun Pad(modifier: Modifier, sampleId: String, color: Color, vm: StudioViewModel, pack: String) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(if (pressed) color else color.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .semantics { contentDescription = "pad_$sampleId"; role = Role.Button }
            .pointerInput(sampleId) {
                detectTapGestures(onPress = {
                    pressed = true
                    vm.onPad(sampleId, vm.nowNanos())
                    tryAwaitRelease()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(Labels.sample(sampleId, pack), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

@Composable
private fun DebugPanel(vm: StudioViewModel) {
    val f = vm.fake
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF241A1A), RoundedCornerShape(10.dp)).padding(8.dp).horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "debug_panel" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DEBUG 가짜 입력", color = Color(0xFFFFB4AB), fontSize = 14.sp)
        DbgButton("dbg_brake", "브레이크") { f.trigger(TriggerSource.BRAKE) }
        DbgButton("dbg_turn_left", "방향지시등 좌") { f.trigger(TriggerSource.TURN_LEFT) }
        DbgButton("dbg_turn_right", "방향지시등 우") { f.trigger(TriggerSource.TURN_RIGHT) }
        DbgButton("dbg_zone_left", "핸들 좌") { f.zone(SteeringZone.LEFT) }
        DbgButton("dbg_zone_center", "핸들 중") { f.zone(SteeringZone.CENTER) }
        DbgButton("dbg_zone_right", "핸들 우") { f.zone(SteeringZone.RIGHT) }
        DbgButton("dbg_fan_min", "풍량 최소") { f.level(app.pjam.studio.input.LevelSource.FAN_SPEED, 0f) }
        DbgButton("dbg_fan_max", "풍량 최대") { f.level(app.pjam.studio.input.LevelSource.FAN_SPEED, 1f) }
        DbgButton("dbg_ac", "A/C") { f.toggle(ToggleSource.AC) }
        DbgButton("dbg_auto", "AUTO") { f.toggle(ToggleSource.AUTO) }
        DbgButton("dbg_recirc", "내기순환") { f.toggle(ToggleSource.RECIRCULATION) }
        DbgButton("dbg_defrost", "성에 제거") { f.toggle(ToggleSource.DEFROSTER) }
        val forced by vm.forcedLock.collectAsStateWithLifecycle()
        DbgButton("dbg_force_lock", if (forced) "잠금 해제" else "잠금 강제") { vm.setForcedLock(!forced) }
    }
}

/** 브레이크 같은 Trigger도 누르는 순간 반응하도록 pointer-down에서 부른다. */
@Composable
private fun DbgButton(tag: String, label: String, onDown: () -> Unit) {
    Box(
        Modifier
            .height(48.dp)
            .background(Color(0xFF4A2C2C), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = tag; role = Role.Button }
            .pointerInput(tag) { detectTapGestures(onPress = { onDown(); tryAwaitRelease() }) },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 16.sp, color = Color.White) }
}

// ------------------------------------------------------------------ 내 차 장비 (T2-7)

@Composable
private fun EquipmentScreen(vm: StudioViewModel, wide: Boolean, gearLabel: String) {
    val support by vm.vehicleSupport.collectAsStateWithLifecycle()
    val assignment by vm.toggleAssignment.collectAsStateWithLifecycle()
    val power by vm.hvacPower.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val rows = EquipmentModel.rows(support, assignment, power, vehicleAvailable = vm.hasVehicleSource)

    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { r -> EquipRowView(r, activity[r.kind] ?: 0, Modifier.fillMaxWidth()) }
        }
    }
    val back: @Composable () -> Unit = {
        Button(onClick = { vm.openEquipment(false) }, modifier = Modifier.height(64.dp).semantics { contentDescription = "btn_equipment_back" }) {
            Text("← 스튜디오로", fontSize = 20.sp)
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = "equipment_screen" }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(CameraZoneWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().height(CameraZoneHeight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("내 차 장비", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("차량 조작을 하면 해당 줄이 반짝여요. 연결이 안 된 항목은 화면 조작으로 대신해요.", fontSize = 16.sp, color = TextDim)
                }
                back()
                GearInfo(gearLabel)
            }
            list(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).semantics { contentDescription = "equipment_screen" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("내 차 장비", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            back()
            GearInfo(gearLabel)
            list(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GearInfo(gearLabel: String) {
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("기어 판정 방식", fontSize = 14.sp, color = TextDim)
        Text(gearLabel, fontSize = 20.sp, color = Color.White, modifier = Modifier.semantics { contentDescription = "equip_gear_method" })
    }
}

@Composable
private fun EquipRowView(r: EquipRow, stamp: Int, modifier: Modifier) {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(stamp) { if (stamp > 0) { lit = true; delay(700); lit = false } }
    val stateColor = when (r.state) {
        EquipState.CONNECTED -> Color(0xFF5BD68B)
        EquipState.CHECKING -> TextDim
        EquipState.UNSUPPORTED -> Color(0xFFFFB74D)
        EquipState.FAILED -> Color(0xFFFF6B6B)
        EquipState.UNUSED -> TextDim
    }
    Row(
        modifier
            .background(if (lit) Color(0xFF0B5A6E) else CardBg, RoundedCornerShape(12.dp))
            .border(BorderStroke(if (lit) 3.dp else 1.dp, if (lit) Color(0xFF4FD1F0) else Color(0xFF2B343D)), RoundedCornerShape(12.dp))
            .semantics { contentDescription = "equip_row_${r.kind}${if (lit) "_lit" else ""}" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(210.dp)) {
            Text(r.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(r.stateText, fontSize = 14.sp, color = stateColor, modifier = Modifier.semantics { contentDescription = "equip_state_${r.kind}" })
        }
        Column(Modifier.weight(1f)) {
            Text(r.role, fontSize = 15.sp, color = Color.White)
            if (r.note != null) Text(r.note, fontSize = 13.sp, color = Color(0xFFFFB74D))
        }
    }
}

private fun gearText(gear: app.pjam.studio.lock.GearKind, method: app.pjam.studio.lock.GearMethod): String {
    val g = when (gear) {
        app.pjam.studio.lock.GearKind.PARK -> "P단"
        app.pjam.studio.lock.GearKind.NOT_PARK -> "P단 아님"
        app.pjam.studio.lock.GearKind.UNKNOWN -> "기어 확인 불가"
    }
    val m = when (method) {
        app.pjam.studio.lock.GearMethod.SDK_GEAR -> "차량 SDK"
        app.pjam.studio.lock.GearMethod.CAR_PROPERTY -> "차량 속성"
        app.pjam.studio.lock.GearMethod.UX_RESTRICTIONS -> "주행 제한만 확인"
        app.pjam.studio.lock.GearMethod.ASSUMED_PARK -> "P로 간주"
        app.pjam.studio.lock.GearMethod.NONE_YET -> "확인 중"
    }
    return "$g ($m)"
}

/** 주차(P단)가 아니면 덮는 잠금 화면. 소리는 이미 멈췄다. P로 돌아오면 만들어 둔 루프는 그대로이고 재생만 정지 상태다. */
@Composable
private fun LockScreen(gearLabel: String, vm: StudioViewModel) {
    Box(
        Modifier.fillMaxSize().background(Color(0xF2101418)).semantics { contentDescription = "lock_screen" }
            .pointerInput(Unit) { detectTapGestures { } }, // 아래 화면으로 터치가 새지 않게
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("주차 중에만 연주할 수 있어요", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("P단으로 돌아오면 만들던 루프가 그대로 있어요. 재생 중이었다면 처음부터 이어서 재생돼요.", fontSize = 20.sp, color = TextDim)
            Text(gearLabel, fontSize = 18.sp, color = TextDim)
            val forced by vm.forcedLock.collectAsStateWithLifecycle()
            if (forced) {
                OutlinedButton(onClick = { vm.setForcedLock(false) }, modifier = Modifier.semantics { contentDescription = "btn_unlock_debug" }) { Text("디버그: 잠금 해제") }
            }
        }
    }
}
