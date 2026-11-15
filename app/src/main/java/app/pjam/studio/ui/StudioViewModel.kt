package app.pjam.studio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pjam.studio.AppContainer
import app.pjam.studio.engine.EngineCommand
import app.pjam.studio.engine.EngineState
import app.pjam.studio.engine.PlayMapper
import app.pjam.studio.engine.TrackKind
import app.pjam.studio.engine.TrackStatus
import app.pjam.studio.input.HvacAssignment
import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.InputKind
import app.pjam.studio.input.SupportState
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InputBarState(val text: String, val seq: Int)

enum class Page { STUDIO, EQUIPMENT, LIBRARY, SETTINGS }

/** 보관함 한 장. bundled = APK에 들어 있는 읽기 전용 예시 곡(지울 수 없다). */
data class LibraryEntry(val song: app.pjam.studio.engine.Song, val bundled: Boolean)

class StudioViewModel(private val c: AppContainer) : ViewModel() {
    private val mapper = PlayMapper()

    val engineState: StateFlow<EngineState> = c.engine.state

    private val _selected = MutableStateFlow(TrackKind.DRUM)
    val selected: StateFlow<TrackKind> = _selected.asStateFlow()

    private val _inputBar = MutableStateFlow<InputBarState?>(null)
    val inputBar: StateFlow<InputBarState?> = _inputBar.asStateFlow()
    private var seq = 0

    /** 공조 버튼 → 트랙 배정 (기획서 6.3). 차량 신호가 없으면 비어 있다(화면 스위치만). */
    val toggleAssignment: StateFlow<Map<ToggleSource, TrackKind>> = run {
        val s = c.inputManager.vehicleSource?.support
        if (s == null) MutableStateFlow(emptyMap<ToggleSource, TrackKind>())
        else s.map { HvacAssignment.assign(it) }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())
    }

    /** 실제 차량 소스의 항목별 지원 상태 ("내 차 장비"). 소스가 없으면 비어 있다. */
    val vehicleSupport: StateFlow<Map<InputKind, SupportState>> = c.inputManager.vehicleSource?.support ?: MutableStateFlow(emptyMap())
    val hasVehicleSource: Boolean get() = c.inputManager.vehicleSource != null
    val hvacPower: StateFlow<Boolean?> = c.inputManager.vehicleSource?.hvacPower ?: MutableStateFlow(null)

    /** 항목별 마지막 원시 콜백 도착 번호. 값이 바뀌면 "내 차 장비"의 그 줄이 반짝인다. */
    private val _activity = MutableStateFlow<Map<InputKind, Int>>(emptyMap())
    val activity: StateFlow<Map<InputKind, Int>> = _activity.asStateFlow()
    private var activitySeq = 0

    private val _page = MutableStateFlow(Page.STUDIO)
    val page: StateFlow<Page> = _page.asStateFlow()
    fun openEquipment(open: Boolean) = openPage(if (open) Page.EQUIPMENT else Page.STUDIO)
    fun openPage(p: Page) {
        _page.value = p
        if (p == Page.LIBRARY) refreshLibrary()
    }

    // ---- 설정 (T3-3) ----
    val settings: StateFlow<app.pjam.studio.data.Settings> = c.settings
    /** 화면 버튼으로 사운드팩을 돌려가며 바꾼다 (T3-5). 다음 소리부터 새 팩이다. */
    fun cyclePack() = c.updateSettings { it.copy(soundPackId = app.pjam.studio.sound.SoundPacks.next(it.soundPackId)) }
    fun setPack(id: String) { if (app.pjam.studio.sound.SoundPacks.isKnown(id)) c.updateSettings { it.copy(soundPackId = id) } }
    fun setQuietMode(on: Boolean) = c.updateSettings { it.copy(quietMode = on) }
    fun latencyDelta(deltaMs: Int) = c.updateSettings { it.copy(latencyMs = it.latencyMs + deltaMs) }

    // ---- 곡 저장·보관함 (T3-2) ----
    private val _library = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val library: StateFlow<List<LibraryEntry>> = _library.asStateFlow()

    /** 이번 세션에서 어떤 조작이 몇 번 소리를 냈는지 (곡 카드 요약용). 전체 지우기·불러오기 때 다시 센다. */
    private val tally = HashMap<String, Int>()
    private fun count(key: String) { tally[key] = (tally[key] ?: 0) + 1 }

    fun refreshLibrary() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val saved = c.songs.list().map { LibraryEntry(it, bundled = false) }
            _library.value = c.bundledSongs.map { LibraryEntry(it, bundled = true) } + saved
        }
    }

    /** 현재 루프를 자동 이름으로 저장한다. 내용이 하나도 없으면 저장하지 않는다. */
    fun saveCurrent() {
        val st = engineState.value
        if (st.tracks.all { it.notes.isEmpty() }) { show("저장할 내용이 없어요 — 먼저 녹음해 보세요"); return }
        val now = System.currentTimeMillis()
        val summary = tally.toMap()
        val tracks = st.tracks.map { app.pjam.studio.engine.Track(it.kind, it.notes, it.muted) }
        val bpm = st.bpm
        val pack = c.settings.value.soundPackId
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val name = app.pjam.studio.data.SongNamer.next(c.songs.list().map { it.name }, java.time.LocalDate.now())
            val song = app.pjam.studio.engine.Song(
                id = java.util.UUID.randomUUID().toString(), name = name, createdAt = now, bpm = bpm, transpose = 0,
                soundPackId = pack, tracks = tracks, inputSummary = summary,
            )
            try {
                c.songs.save(song)
                android.util.Log.i("PJAM_DATA", "saved ${song.id} name=${song.name}")
                show("저장했어요: ${song.name}")
            } catch (t: Throwable) {
                android.util.Log.w("PJAM_DATA", "save failed: $t")
                show("저장하지 못했어요")
            }
            refreshLibrary()
        }
    }

    /** 보관함의 곡을 불러와 바로 재생한다 (시연에서 시간이 밀릴 때 예시 곡을 쓰는 용도). */
    fun loadSong(song: app.pjam.studio.engine.Song) {
        if (!c.guard.allowInput()) return
        c.engine.send(EngineCommand.LoadSong(song))
        setPack(song.soundPackId) // 곡이 저장될 때의 사운드팩으로 들려준다
        tally.clear(); tally.putAll(song.inputSummary)
        c.focus.acquire()
        c.engine.send(EngineCommand.Start)
        android.util.Log.i("PJAM_DATA", "loaded ${song.id} name=${song.name} notes=${song.tracks.sumOf { it.notes.size }}")
        show("불러왔어요: ${song.name}")
        openPage(Page.STUDIO)
    }

    fun deleteSong(id: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { c.songs.delete(id); refreshLibrary() }
    }

    init {
        viewModelScope.launch {
            c.inputSources.map<_, SharedFlow<InputEvent>> { it.events }.merge().collect { onVehicleInput(it) }
        }
        viewModelScope.launch {
            c.inputSources.map<_, SharedFlow<InputKind>> { it.activity }.merge().collect { kind ->
                android.util.Log.i("PJAM_EQUIP", "flash $kind")
                _activity.value = _activity.value + (kind to ++activitySeq)
            }
        }
    }

    private fun show(text: String) {
        android.util.Log.i("PJAM_INPUT", text)
        _inputBar.value = InputBarState(text, ++seq)
    }

    /** 녹음 중에 다른 트랙을 고르면 현재 녹음을 끝내고(내용 유지) 선택만 바꾼다. */
    fun select(k: TrackKind) {
        if (k != _selected.value) c.engine.send(EngineCommand.FinishRecord)
        _selected.value = k
    }

    /** 화면 패드: 누르는 순간(pointer-down)의 시각을 stamp해서 들어온다. */
    fun onPad(sampleId: String, timestampNanos: Long) {
        if (!c.guard.allowInput()) return
        val id = mapper.onPad(sampleId)
        count("PAD")
        c.focus.acquire()
        c.engine.send(EngineCommand.Play(id, timestampNanos))
        show("화면 패드 → ${Labels.sample(id, c.settings.value.soundPackId)}")
    }

    /** 핸들 대체 화면 3버튼 (기획서 6.3): 소리는 내지 않고 BASS의 현재 음 / MELODY의 커서만 옮긴다. */
    fun onScreenZone(zone: SteeringZone) {
        if (!c.guard.allowInput()) return
        mapper.onZone(_selected.value, zone)
        show("화면 버튼 → ${Labels.zone(_selected.value, zone)}")
    }

    fun onVehicleInput(e: InputEvent) {
        // 플랫폼이 화면을 가려도 SDK 콜백은 계속 온다. 잠금·백그라운드 중에는 소리도 포커스도 만들지 않는다.
        if (!c.guard.allowInput()) { android.util.Log.i("PJAM_LOCK", "input dropped (inactive): $e"); return }
        if (!app.pjam.studio.input.InputFilter.accepts(e, c.settings.value.quietMode)) {
            android.util.Log.i("PJAM_INPUT", "조용한 연주 모드: 무시 $e")
            return
        }
        val kind = _selected.value
        when (e) {
            is InputEvent.Trigger -> {
                val id = mapper.onTrigger(kind, e.source)
                count(e.source.name)
                c.focus.acquire()
                c.engine.send(EngineCommand.Play(id, e.timestampNanos))
                val src = when (e.source) { TriggerSource.BRAKE -> "브레이크"; TriggerSource.TURN_LEFT -> "방향지시등 좌"; TriggerSource.TURN_RIGHT -> "방향지시등 우" }
                show("$src → ${Labels.sample(id, c.settings.value.soundPackId)}")
            }
            is InputEvent.Zone -> {
                mapper.onZone(kind, e.zone)
                val z = when (e.zone) { SteeringZone.LEFT -> "좌"; SteeringZone.CENTER -> "중앙"; SteeringZone.RIGHT -> "우" }
                show("핸들 $z")
            }
            is InputEvent.Toggle -> {
                val target = toggleAssignment.value[e.source]
                if (target == null) { android.util.Log.i("PJAM_INPUT", "toggle ${e.source} 배정 없음(화면 스위치만)"); return }
                toggleMute(target)
                count("HVAC")
                show("${Labels.toggle(e.source)} → ${Labels.track(target)} 켜기/끄기")
            }
            is InputEvent.Level -> when (e.source) {
                app.pjam.studio.input.LevelSource.FAN_SPEED -> {
                    val bpm = app.pjam.studio.engine.Tempo.bpmFor(e.normalized)
                    c.engine.send(EngineCommand.SetBpm(bpm))
                    show("풍량 → 템포 $bpm BPM")
                }
                else -> show("${e.source} ${"%.2f".format(e.normalized)}")
            }
            is InputEvent.Cycle -> show("${e.source}")
        }
    }

    fun toggleMute(k: TrackKind) {
        val cur = engineState.value.tracks.first { it.kind == k }.muted
        c.engine.send(EngineCommand.SetMuted(k, !cur))
    }

    fun togglePlay() { if (!c.guard.allowInput()) return; c.focus.acquire(); c.engine.send(EngineCommand.TogglePlay) }
    fun toggleRecord() { if (!c.guard.allowInput()) return; c.focus.acquire(); c.engine.send(EngineCommand.ToggleRecord(_selected.value)) }

    /** 차량 신호를 쓸 수 있는지. SCREEN_ONLY면 화면 조작만으로 동작한다 (T1-12). */
    val inputMode: StateFlow<app.pjam.studio.input.InputMode> = run {
        val support = c.inputManager.vehicleSource?.support ?: MutableStateFlow(emptyMap())
        kotlinx.coroutines.flow.combine(support, c.inputManager.startFailed) { s, failed -> app.pjam.studio.input.InputModes.resolve(s, failed) }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, app.pjam.studio.input.InputMode.CHECKING)
    }

    // 잠금 상태 (기어)
    val locked: StateFlow<Boolean> get() = c.guard.locked
    val gear get() = c.guard.gearState
    val gearMethod get() = c.guard.method
    val forcedLock get() = c.guard.forcedLock
    fun setForcedLock(on: Boolean) = c.guard.setForcedLock(on)
    fun clear(k: TrackKind) = c.engine.send(EngineCommand.ClearTrack(k))
    fun clearAll() { tally.clear(); c.engine.send(EngineCommand.ClearAll) }
    fun bpmDelta(d: Int) = c.engine.send(EngineCommand.SetBpm(engineState.value.bpm + d))
    fun nowNanos() = c.clock.nanos()

    // 디버그 패널
    val fake get() = c.fakeInput

    /** 개발용: 잠금 화면 대신 재생만 멈춘다 (실제 잠금은 M1) */
    fun stop() = c.engine.send(EngineCommand.Stop)

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StudioViewModel(c) as T
    }
}
