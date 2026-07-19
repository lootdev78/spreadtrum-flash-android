package io.github.lootdev78.spdflash

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

object UsbKickTransport {
    data class Outcome(
        val needsReenumeration: Boolean,
        val response: ByteArray = byteArrayOf(),
        val message: String,
    )

    fun execute(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        kickMode: Int,
        customMode: Int,
        routeStep: Int,
        timeoutMs: Int,
        log: (String) -> Unit,
    ): Outcome {
        require(kickMode in 1..2) { "Kick mode must be 1 or 2" }
        require(customMode in 0..127) { "Kick target mode must be between 0 and 127" }
        require(routeStep >= 0) { "Invalid kick route step" }
        val transport = findTransport(device) ?: error("No bulk IN/OUT interface found")
        check(connection.claimInterface(transport.usbInterface, true)) { "Could not claim the USB interface" }
        try {
            val control = connection.controlTransfer(0x21, 34, 0x601, 0, null, 0, timeoutMs)
            check(control >= 0) { "USB control initialization failed: $control" }
            log("Kick: control initialization successful")

            if (kickMode == 2 && customMode == 0) {
                val hello = ByteArray(10) { 0x7e }
                write(connection, transport.out, hello, timeoutMs)
                val response = read(connection, transport.input, timeoutMs)
                log("Kick: CHECK_BAUD ${response.toHexPreview()}")
                if (isDownloadResponse(response)) {
                    return Outcome(false, response, "Device is already responding with the download protocol")
                }
            }

            val modeByte = when {
                // --kick follows boot_diag -> cali_diag -> dl_diag. If the first transition
                // only re-enumerates into cali_diag, the next pass explicitly requests dl_diag.
                kickMode == 1 && routeStep == 0 -> 0x81
                kickMode == 1 -> 0x82
                customMode == 0 -> 0x82
                else -> customMode + 0x80
            }
            log("Kick: route step ${routeStep + 1}, target byte 0x%02x".format(modeByte))
            val payload = byteArrayOf(0x7e, 0, 0, 0, 0, 8, 0, 0xfe.toByte(), modeByte.toByte(), 0x7e)
            write(connection, transport.out, payload, timeoutMs)
            var response = read(connection, transport.input, timeoutMs)
            log("Kick: Modusantwort ${response.toHexPreview()}")

            if (response.isNotEmpty() && !isDownloadResponse(response) && response.getOrNull(2) != 0x7e.toByte()) {
                val autoDownloader = byteArrayOf(
                    0x7e, 0, 0, 0, 0, 0x20, 0, 0x68, 0, 0x41, 0x54, 0x2b, 0x53, 0x50,
                    0x52, 0x45, 0x46, 0x3d, 0x22, 0x41, 0x55, 0x54, 0x4f, 0x44, 0x4c, 0x4f,
                    0x41, 0x44, 0x45, 0x52, 0x22, 0x0d, 0x0a, 0x7e,
                )
                Thread.sleep(500)
                write(connection, transport.out, autoDownloader, timeoutMs)
                response = read(connection, transport.input, timeoutMs)
                log("Kick: AUTODLOADER-Antwort ${response.toHexPreview()}")
            }
            return Outcome(
                needsReenumeration = true,
                response = response,
                message = "Mode switch sent; waiting for USB re-enumeration",
            )
        } finally {
            connection.releaseInterface(transport.usbInterface)
        }
    }

    private data class Transport(val usbInterface: UsbInterface, val input: UsbEndpoint, val out: UsbEndpoint)

    private fun findTransport(device: UsbDevice): Transport? {
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN && input == null) input = endpoint
                if (endpoint.direction == UsbConstants.USB_DIR_OUT && output == null) output = endpoint
            }
            if (input != null && output != null) return Transport(usbInterface, input, output)
        }
        return null
    }

    private fun write(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray, timeoutMs: Int) {
        val written = connection.bulkTransfer(endpoint, bytes, bytes.size, timeoutMs)
        check(written == bytes.size) { "USB write error: $written/${bytes.size} bytes" }
    }

    private fun read(connection: UsbDeviceConnection, endpoint: UsbEndpoint, timeoutMs: Int): ByteArray {
        val buffer = ByteArray(32 * 1024)
        val read = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        return if (read > 0) buffer.copyOf(read) else byteArrayOf()
    }

    private fun isDownloadResponse(response: ByteArray): Boolean {
        val type = response.getOrNull(2)?.toInt()?.and(0xff) ?: return false
        return type in setOf(0x80, 0x81, 0x8b, 0xfe)
    }

    private fun ByteArray.toHexPreview(): String =
        take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xff) } + if (size > 32) " …" else ""
}
