package app.pjam.studio

import android.app.Application

class PJamApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container // 사운드 로드를 앱 시작과 함께 시작한다
    }
}
