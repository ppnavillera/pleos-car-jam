package app.pjam.studio.input.pleos

import android.content.Context

/** 릴리스에서는 아무것도 하지 않는다. 디버그 빌드의 SignalProbe(T0-3/T0-6 검증용 신호 로그)와 짝이다. */
class SignalProbe(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun start() = Unit
}
