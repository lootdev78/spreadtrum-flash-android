package io.github.lootdev78.spdflash

interface NativeCallbacks {
    fun onNativeLog(line: String)
    fun onNativeProgress(currentBytes: Long, totalBytes: Long, bytesPerSecond: Long, label: String)
    fun onNativeOpenOutput(relativePath: String, append: Boolean): Int
    fun onNativeOpenInput(uri: String): Int
}

object NativeBridge {
    init {
        System.loadLibrary("spdflash_android")
    }

    external fun run(
        usbFileDescriptor: Int,
        arguments: Array<String>,
        workingDirectory: String,
        confirmDangerous: Boolean,
        expectedBytes: Long,
        directSafOutput: Boolean,
        callback: NativeCallbacks,
    ): Int

    external fun cancel()
    external fun version(): String
}
