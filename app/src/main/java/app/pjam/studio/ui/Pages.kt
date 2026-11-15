package app.pjam.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pjam.studio.data.InputSummaryText
import app.pjam.studio.data.Settings

/**
 * 보관함·설정 같은 보조 화면의 공통 틀. 넓은 화면은 왼쪽 열 위쪽(카메라 안전 구역)에 제목·돌아가기만 두고
 * 내용은 오른쪽 열에 둔다. 좁은 화면은 한 열 세로 스크롤.
 */
@Composable
private fun PageScaffold(tag: String, title: String, hint: String, wide: Boolean, vm: StudioViewModel, content: @Composable () -> Unit) {
    val back: @Composable () -> Unit = {
        Button(onClick = { vm.openPage(Page.STUDIO) }, modifier = Modifier.height(64.dp).semantics { contentDescription = "btn_page_back" }) {
            Text("← 스튜디오로", fontSize = 20.sp)
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = tag }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(CameraZoneWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().height(CameraZoneHeight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(hint, fontSize = 16.sp, color = TextDim)
                }
                back()
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).semantics { contentDescription = tag }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(hint, fontSize = 14.sp, color = TextDim)
            back()
            content()
        }
    }
}

// ------------------------------------------------------------------ 보관함 (T3-2)

@Composable
fun LibraryScreen(vm: StudioViewModel, wide: Boolean) {
    val entries by vm.library.collectAsStateWithLifecycle()
    PageScaffold("library_screen", "보관함", "저장한 곡을 불러와 바로 재생해요. 예시 곡은 4트랙이 채워져 있어서 시간이 모자랄 때 쓸 수 있어요.", wide, vm) {
        if (entries.isEmpty()) Text("저장한 곡이 아직 없어요. 스튜디오에서 \"곡 저장\"을 눌러 보세요.", fontSize = 20.sp, color = TextDim)
        entries.forEach { e ->
            val s = e.song
            Row(
                Modifier.fillMaxWidth()
                    .background(CardBg, RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, Color(0xFF2B343D)), RoundedCornerShape(14.dp))
                    .padding(14.dp)
                    .semantics { contentDescription = "song_card_${s.id}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name + if (e.bundled) "  · 예시" else "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("${s.bpm} BPM · 노트 ${s.tracks.sumOf { it.notes.size }}개 · ${packLabel(s.soundPackId)} · " + s.tracks.joinToString(" ") { t -> Labels.track(t.kind) + (if (t.notes.isEmpty()) "–" else "●") }, fontSize = 15.sp, color = TextDim)
                    Text(InputSummaryText.of(s.inputSummary), fontSize = 15.sp, color = Color(0xFF9CC9E0))
                }
                Button(onClick = { vm.loadSong(s) }, modifier = Modifier.height(56.dp).semantics { contentDescription = "btn_load_${s.id}" }) { Text("불러오기", fontSize = 18.sp) }
                if (!e.bundled) OutlinedButton(onClick = { vm.deleteSong(s.id) }, modifier = Modifier.height(56.dp).semantics { contentDescription = "btn_delete_${s.id}" }) { Text("삭제", fontSize = 18.sp) }
            }
        }
    }
}

internal fun packLabel(id: String) = when (id) { "car" -> "차량 사운드팩"; else -> "기본 사운드팩" }

// ------------------------------------------------------------------ 설정 (T3-3)

@Composable
fun SettingsScreen(vm: StudioViewModel, wide: Boolean) {
    val st by vm.settings.collectAsStateWithLifecycle()
    PageScaffold("settings_screen", "설정", "바꾸면 바로 저장돼요.", wide, vm) {
        SettingCard("조용한 연주 모드", "켜면 방향지시등과 와이퍼 입력을 무시해요. 주변에 차량과 사람이 많을 때 쓰세요. 브레이크·핸들·공조·화면 패드는 그대로예요.") {
            Switch(checked = st.quietMode, onCheckedChange = { vm.setQuietMode(it) }, modifier = Modifier.semantics { contentDescription = "switch_quiet_${if (st.quietMode) "on" else "off"}" })
        }
        SettingCard("입력 지연 보정", "입력 시각에서 이만큼 빼고 박자에 맞춰요. 소리가 항상 늦게 찍히면 올려 보세요. 기본 0ms.") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.latencyDelta(-Settings.LATENCY_STEP_MS) }, modifier = Modifier.semantics { contentDescription = "btn_latency_down" }) { Text("−", fontSize = 24.sp) }
                Text("${st.latencyMs} ms", fontSize = 22.sp, color = Color.White, modifier = Modifier.width(110.dp).semantics { contentDescription = "latency_text" })
                OutlinedButton(onClick = { vm.latencyDelta(+Settings.LATENCY_STEP_MS) }, modifier = Modifier.semantics { contentDescription = "btn_latency_up" }) { Text("+", fontSize = 24.sp) }
            }
        }
    }
}

@Composable
internal fun SettingCard(title: String, body: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(body, fontSize = 15.sp, color = TextDim)
        }
        control()
    }
}
