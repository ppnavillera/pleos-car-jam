package app.pjam.studio

import android.os.Bundle
import android.util.Log
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

    // 생명주기 신호는 모두 로그로 남긴다(PJAM_LIFE).
    // 소리를 멈추는 기준은 onStop(화면에서 완전히 안 보임)이다. onPause·창 포커스 손실은 기준으로 쓰지 않는다:
    // 방향지시등을 켜면 플랫폼이 사각지대 카메라 창(CarEvsBlindSpotCameraPreviewActivity)을 앱 위에 띄워 onPause와 포커스 손실이 오지만
    // 앱은 여전히 보이고(onStop 없음) 방향지시등이 연주 입력이라 계속 받아야 한다 (FINDINGS V3).
    // 다른 앱으로 전환하면 onPause 5ms 뒤에 onStop이 온다. 홈 키는 이 에뮬레이터에서 앱을 가리지 않아 신호가 없다.
    override fun onStart() {
        Log.i(LIFE, "onStart")
        super.onStart()
        container.guard.onForeground(true, "onStart")
    }

    override fun onResume() { Log.i(LIFE, "onResume"); super.onResume() }

    override fun onPause() { Log.i(LIFE, "onPause"); super.onPause() }

    override fun onStop() {
        Log.i(LIFE, "onStop")
        container.guard.onForeground(false, "onStop") // 엔진 Stop + 포커스 반납 (재생은 돌아와도 자동으로 켜지 않음)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        Log.i(LIFE, "onWindowFocusChanged hasFocus=$hasFocus")
        super.onWindowFocusChanged(hasFocus)
    }

    private companion object { const val LIFE = "PJAM_LIFE" }
}
