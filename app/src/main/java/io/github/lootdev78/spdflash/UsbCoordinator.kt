package io.github.lootdev78.spdflash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class UsbCoordinator(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val permissionWaiters = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    private val _devices = MutableStateFlow<List<UsbDeviceItem>>(emptyList())
    val devices: StateFlow<List<UsbDeviceItem>> = _devices.asStateFlow()

    private val _attached = MutableSharedFlow<UsbDevice>(extraBufferCapacity = 16)
    val attached: SharedFlow<UsbDevice> = _attached.asSharedFlow()

    private val _detached = MutableSharedFlow<UsbDevice>(extraBufferCapacity = 16)
    val detached: SharedFlow<UsbDevice> = _detached.asSharedFlow()

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDeviceExtra() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refresh()
                    _attached.tryEmit(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refresh()
                    permissionWaiters.remove(device.deviceId)?.complete(false)
                    _detached.tryEmit(device)
                }
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDeviceExtra() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            permissionWaiters.remove(device.deviceId)?.complete(granted)
            refresh()
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            systemReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        _devices.value = manager.deviceList.values
            .asSequence()
            .filter { it.vendorId == SPREADTRUM_VENDOR_ID }
            .sortedWith(compareByDescending<UsbDevice> { it.productId == DOWNLOAD_PRODUCT_ID }.thenBy { it.productId })
            .map(::UsbDeviceItem)
            .toList()
    }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    fun attachedSpreadtrumDevices(): List<UsbDevice> {
        refresh()
        return _devices.value.map(UsbDeviceItem::device)
    }

    suspend fun open(device: UsbDevice, permissionTimeoutMs: Long = 30_000): UsbDeviceConnection {
        require(device.vendorId == SPREADTRUM_VENDOR_ID) { "Not a Spreadtrum/Unisoc device" }
        check(manager.deviceList.values.any { it.deviceId == device.deviceId }) { "USB device is no longer attached" }
        if (!manager.hasPermission(device)) {
            val deferred = CompletableDeferred<Boolean>()
            permissionWaiters.put(device.deviceId, deferred)?.cancel()
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            try {
                manager.requestPermission(device, permissionIntent)
                val granted = withTimeout(permissionTimeoutMs) { deferred.await() }
                check(granted) { "USB permission was not granted" }
            } finally {
                permissionWaiters.remove(device.deviceId, deferred)
            }
        }
        return manager.openDevice(device) ?: error("UsbManager.openDevice() failed")
    }

    /**
     * Waits for exactly one plausible re-enumerated target. Selecting an arbitrary device is
     * intentionally forbidden: flashing the wrong one is worse than requiring manual selection.
     */
    suspend fun awaitSpreadtrumDevice(
        excludeDeviceId: Int? = null,
        timeoutMs: Long = 30_000,
        preferDownloadPort: Boolean = true,
    ): UsbDevice = withTimeout(timeoutMs) {
        while (true) {
            refresh()
            val candidates = _devices.value.map(UsbDeviceItem::device).filter { device ->
                device.deviceId != excludeDeviceId || device.productId == DOWNLOAD_PRODUCT_ID
            }
            chooseUnique(candidates, preferDownloadPort)?.let { return@withTimeout it }
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE")
        error("USB target device not found")
    }

    private fun chooseUnique(candidates: List<UsbDevice>, preferDownloadPort: Boolean): UsbDevice? {
        if (candidates.isEmpty()) return null
        val preferred = if (preferDownloadPort) candidates.filter { it.productId == DOWNLOAD_PRODUCT_ID } else emptyList()
        if (preferred.size == 1) return preferred.single()
        check(preferred.size <= 1) {
            "Multiple Spreadtrum download devices detected. Disconnect unrelated devices and select the target again."
        }
        if (candidates.size == 1) return candidates.single()
        error("Multiple Spreadtrum/Unisoc devices detected. Automatic reconnect was stopped for safety.")
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        const val SPREADTRUM_VENDOR_ID = 0x1782
        const val DOWNLOAD_PRODUCT_ID = 0x4d00
        private const val ACTION_USB_PERMISSION = "io.github.lootdev78.spdflash.USB_PERMISSION"
    }
}
