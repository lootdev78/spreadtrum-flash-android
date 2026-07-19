plugins {
    id("com.android.application") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}

// Convenience aliases: run these from the repository root without the :app: prefix.
tasks.register("updateUpstream") {
    group = "upstream"
    description = "Updates spreadtrum_flash and libusb to their latest locked versions."
    dependsOn(":app:updateUpstream")
}

tasks.register("checkUpstreamUpdates") {
    group = "upstream"
    description = "Checks whether newer spreadtrum_flash or libusb versions exist."
    dependsOn(":app:checkUpstreamUpdates")
}

tasks.register("updateUpstreamAndBuild") {
    group = "upstream"
    description = "Updates native upstreams and builds the debug APK in one command."
    dependsOn(":app:updateUpstreamAndBuild")
}
