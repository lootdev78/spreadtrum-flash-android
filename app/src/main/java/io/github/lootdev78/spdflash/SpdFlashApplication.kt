package io.github.lootdev78.spdflash

import android.app.Application

class SpdFlashApplication : Application() {
    lateinit var usbCoordinator: UsbCoordinator
        private set
    lateinit var queueStore: QueueStore
        private set
    lateinit var safOutputRouter: SafOutputRouter
        private set

    override fun onCreate() {
        super.onCreate()
        usbCoordinator = UsbCoordinator(this)
        queueStore = QueueStore(this)
        safOutputRouter = SafOutputRouter(this)
    }
}
