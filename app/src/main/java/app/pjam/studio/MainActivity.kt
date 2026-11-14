package app.pjam.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import app.pjam.studio.ui.StudioScreen
import app.pjam.studio.ui.StudioViewModel

class MainActivity : ComponentActivity() {
    private val container get() = (application as PJamApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, StudioViewModel.Factory(container))[StudioViewModel::class.java]
        setContent { StudioScreen(vm, debugPanel = BuildConfig.DEBUG) }
    }

    override fun onStop() {
        // 백그라운드로 가면 재생을 멈추고 포커스를 반납한다 (기획서 6.7)
        container.engine.send(app.pjam.studio.engine.EngineCommand.Stop)
        container.focus.release()
        super.onStop()
    }
}
