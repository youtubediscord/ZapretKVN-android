package io.github.zapretkvn.android

import android.app.Application

class ZapretApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext)
    }
}
