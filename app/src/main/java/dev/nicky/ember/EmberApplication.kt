package dev.nicky.ember

import android.app.Application
import dev.nicky.ember.data.EmberRepository

class EmberApplication : Application() {
    val repository: EmberRepository by lazy { EmberRepository(this) }

    override fun onCreate() {
        super.onCreate()
        EmberConnectionService.createChannels(this)
    }
}
