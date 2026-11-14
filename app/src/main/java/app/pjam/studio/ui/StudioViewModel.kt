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
import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

data class InputBarState(val text: String, val seq: Int)

class StudioViewModel(private val c: AppContainer) : ViewModel() {
    private val mapper = PlayMapper()

    val engineState: StateFlow<EngineState> = c.engine.state

    private val _selected = MutableStateFlow(TrackKind.DRUM)
    val selected: StateFlow<TrackKind> = _selected.asStateFlow()

    private val _inputBar = MutableStateFlow<InputBarState?>(null)
    val inputBar: StateFlow<InputBarState?> = _inputBar.asStateFlow()
    private var seq = 0

    init {
        viewModelScope.launch {
            c.inputSources.map<_, SharedFlow<InputEvent>> { it.events }.merge().collect { onVehicleInput(it) }
        }
    }

    private fun show(text: String) {
        android.util.Log.i("PJAM_INPUT", text)
        _inputBar.value = InputBarState(text, ++seq)
    }

    fun select(k: TrackKind) { _selected.value = k }

    /** 화면 패드: 누르는 순간(pointer-down)의 시각을 stamp해서 들어온다. */
    fun onPad(sampleId: String, timestampNanos: Long) {
        val id = mapper.onPad(sampleId)
        c.focus.acquire()
        c.engine.send(EngineCommand.Play(id, timestampNanos))
        show("화면 패드 → ${Labels.sample(id)}")
    }

    fun onVehicleInput(e: InputEvent) {
        val kind = _selected.value
        when (e) {
            is InputEvent.Trigger -> {
                val id = mapper.onTrigger(kind, e.source)
                c.focus.acquire()
                c.engine.send(EngineCommand.Play(id, e.timestampNanos))
                val src = when (e.source) { TriggerSource.BRAKE -> "브레이크"; TriggerSource.TURN_LEFT -> "방향지시등 좌"; TriggerSource.TURN_RIGHT -> "방향지시등 우" }
                show("$src → ${Labels.sample(id)}")
            }
            is InputEvent.Zone -> {
                mapper.onZone(kind, e.zone)
                val z = when (e.zone) { SteeringZone.LEFT -> "좌"; SteeringZone.CENTER -> "중앙"; SteeringZone.RIGHT -> "우" }
                show("핸들 $z")
            }
            is InputEvent.Toggle -> {
                val target = when (e.source) {
                    ToggleSource.AC -> TrackKind.DRUM; ToggleSource.AUTO -> TrackKind.BASS
                    ToggleSource.RECIRCULATION -> TrackKind.MELODY; ToggleSource.DEFROSTER -> TrackKind.FX
                }
                toggleMute(target)
                show("${Labels.hvac(target)} → ${Labels.track(target)} 켜기/끄기")
            }
            is InputEvent.Level -> show("${e.source} ${"%.2f".format(e.normalized)}")
            is InputEvent.Cycle -> show("${e.source}")
        }
    }

    fun toggleMute(k: TrackKind) {
        val cur = engineState.value.tracks.first { it.kind == k }.muted
        c.engine.send(EngineCommand.SetMuted(k, !cur))
    }

    fun togglePlay() { c.focus.acquire(); c.engine.send(EngineCommand.TogglePlay) }
    fun toggleRecord() { c.focus.acquire(); c.engine.send(EngineCommand.ToggleRecord(_selected.value)) }
    fun clear(k: TrackKind) = c.engine.send(EngineCommand.ClearTrack(k))
    fun clearAll() = c.engine.send(EngineCommand.ClearAll)
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
