package app.pjam.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource

private val Bg = Color(0xFF101418)
private val CardBg = Color(0xFF1A2128)
private val CellOff = Color(0xFF232C35)
private val CellBeat = Color(0xFF2E3944)
private val TextDim = Color(0xFF9AA7B3)

private fun accent(k: TrackKind) = when (k) {
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

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Bg).semantics { contentDescription = "pjam_root" }) {
            Column(
                Modifier.fillMaxSize().padding(20.dp).semantics { contentDescription = "studio_screen" },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(st, sel, vm, gearText(gear, method) + if (inputMode == app.pjam.studio.input.InputMode.SCREEN_ONLY) " · 차량 신호 없음, 화면 조작" else "")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrackKind.entries.forEach { k ->
                        val t = st.tracks.first { it.kind == k }
                        TrackCard(Modifier.weight(1f), t, st, selected = k == sel, vm = vm)
                    }
                }
                InputBar(bar?.text)
                PadRow(sel, vm)
                if (debugPanel) DebugPanel(vm)
            }
            if (locked) LockScreen(gearText(gear, method), vm)
        }
    }
}

@Composable
private fun TopBar(st: EngineState, sel: TrackKind, vm: StudioViewModel, gearLabel: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("P-JAM", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(gearLabel, fontSize = 14.sp, color = TextDim, modifier = Modifier.semantics { contentDescription = "gear_text" })
        Spacer(Modifier.weight(1f))
        val statusText = when {
            st.countInBeat > 0 -> "카운트인 ${st.countInBeat}/4"
            st.recordingKind != null && st.tracks.first { it.kind == st.recordingKind }.status == TrackStatus.RECORDING -> "● 녹음 중 ${Labels.track(st.recordingKind!!)}"
            st.recordingKind != null -> "녹음 대기 ${Labels.track(st.recordingKind!!)}"
            st.playing -> "재생 중"
            else -> "정지"
        }
        Text(statusText, fontSize = 22.sp, color = if (st.recordingKind != null) Color(0xFFFF5A5A) else Color.White,
            modifier = Modifier.semantics { contentDescription = "status_text" })
        Spacer(Modifier.width(16.dp))
        OutlinedButton(onClick = { vm.bpmDelta(-5) }, modifier = Modifier.semantics { contentDescription = "btn_bpm_down" }) { Text("−", fontSize = 24.sp) }
        Text("${st.bpm} BPM", fontSize = 22.sp, color = Color.White, modifier = Modifier.semantics { contentDescription = "bpm_text" })
        OutlinedButton(onClick = { vm.bpmDelta(+5) }, modifier = Modifier.semantics { contentDescription = "btn_bpm_up" }) { Text("+", fontSize = 24.sp) }
        Button(
            onClick = { vm.togglePlay() },
            modifier = Modifier.height(64.dp).semantics { contentDescription = "btn_play" },
        ) { Text(if (st.playing) "■ 정지" else "▶ 재생", fontSize = 22.sp) }
        val recActive = st.recordingKind == sel
        Button(
            onClick = { vm.toggleRecord() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            modifier = Modifier.height(64.dp).semantics { contentDescription = "btn_record" },
        ) { Text(if (recActive) "■ 녹음 종료" else "● 녹음 ${Labels.track(sel)}", fontSize = 22.sp) }
        OutlinedButton(onClick = { vm.clearAll() }, modifier = Modifier.height(64.dp).semantics { contentDescription = "btn_clear_all" }) { Text("전체 지우기", fontSize = 18.sp) }
    }
}

@Composable
private fun TrackCard(modifier: Modifier, t: TrackState, st: EngineState, selected: Boolean, vm: StudioViewModel) {
    val color = accent(t.kind)
    Row(
        modifier
            .fillMaxWidth()
            .background(CardBg, RoundedCornerShape(14.dp))
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) color else Color(0xFF2B343D)), RoundedCornerShape(14.dp))
            .clickable { vm.select(t.kind) }
            .semantics { contentDescription = "track_card_${t.kind}${if (selected) "_selected" else ""}" }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Labels.track(t.kind), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
                Text(Labels.hvac(t.kind), fontSize = 13.sp, color = TextDim)
            }
            Text(Labels.status(t.status), fontSize = 16.sp, color = Color.White,
                modifier = Modifier.semantics { contentDescription = "status_${t.kind}_${t.status}" })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = !t.muted,
                    onCheckedChange = { vm.toggleMute(t.kind) },
                    modifier = Modifier.semantics { contentDescription = "switch_${t.kind}" },
                )
                OutlinedButton(onClick = { vm.clear(t.kind) }, modifier = Modifier.semantics { contentDescription = "btn_clear_${t.kind}" }) { Text("지우기") }
            }
        }
        StepGrid(Modifier.weight(1f).fillMaxHeight(), t, color, playhead = if (st.playing) st.step else -1)
    }
}

@Composable
private fun StepGrid(modifier: Modifier, t: TrackState, color: Color, playhead: Int) {
    val on = remember(t.notes) { t.notes.map { it.step }.toSet() }
    Row(modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (bar in 0 until STEPS_PER_LOOP / STEPS_PER_BAR) {
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 0 until STEPS_PER_BAR) {
                    val step = bar * STEPS_PER_BAR + i
                    val isOn = step in on
                    val isHead = step == playhead
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isOn) color else if (i % 4 == 0) CellBeat else CellOff, RoundedCornerShape(4.dp))
                            .then(if (isHead) Modifier.border(3.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
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
        Text(text ?: "차량 조작이나 화면 패드를 누르면 여기에 표시돼요", fontSize = 24.sp, color = if (text == null) TextDim else Color.White)
    }
}

@Composable
private fun PadRow(kind: TrackKind, vm: StudioViewModel) {
    val color = accent(kind)
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayMapper.pads(kind).forEach { id ->
            Pad(Modifier.weight(1f).fillMaxHeight(), id, color, vm)
        }
    }
}

/** 누르는 순간(pointer-down)에 시각을 찍는다. clickable은 뗄 때 반응하므로 쓰지 않는다. */
@Composable
private fun Pad(modifier: Modifier, sampleId: String, color: Color, vm: StudioViewModel) {
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
        Text(Labels.sample(sampleId), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.Black)
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
            Text("P단으로 돌아오면 만들던 루프가 그대로 있어요. 재생은 직접 다시 눌러 주세요.", fontSize = 20.sp, color = TextDim)
            Text(gearLabel, fontSize = 18.sp, color = TextDim)
            val forced by vm.forcedLock.collectAsStateWithLifecycle()
            if (forced) {
                OutlinedButton(onClick = { vm.setForcedLock(false) }, modifier = Modifier.semantics { contentDescription = "btn_unlock_debug" }) { Text("디버그: 잠금 해제") }
            }
        }
    }
}
