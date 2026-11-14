package app.pjam.studio.engine

import app.pjam.studio.input.FakeVehicleInputSource
import app.pjam.studio.input.InputEvent
import app.pjam.studio.input.SteeringZone
import app.pjam.studio.input.ToggleSource
import app.pjam.studio.input.TriggerSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeInputTest {
    @Test fun panelButtonsEmitEventsWithClockTimestamp() = runTest(UnconfinedTestDispatcher()) {
        var t = 1000L
        val src = FakeVehicleInputSource { t }
        val got = ArrayList<InputEvent>()
        val job = launch { src.events.toList(got) }
        src.trigger(TriggerSource.BRAKE); t = 2000
        src.zone(SteeringZone.LEFT); t = 3000
        src.toggle(ToggleSource.AC)
        job.cancel()
        assertEquals(
            listOf(
                InputEvent.Trigger(TriggerSource.BRAKE, 1000),
                InputEvent.Zone(SteeringZone.LEFT, 2000),
                InputEvent.Toggle(ToggleSource.AC, 3000),
            ),
            got,
        )
    }
}
