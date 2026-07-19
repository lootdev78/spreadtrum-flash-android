import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/*
 * One-file upstream vendor tool.
 *
 * Normal builds use the exact versions recorded in upstream.lock.json and never
 * contact the network. Run :app:updateUpstream (or updateUpstreamAndBuild) only
 * when you intentionally want to refresh the native sources.
 */

val spreadtrumRepository = "TomKing062/spreadtrum_flash"
val libusbRepository = "libusb/libusb"
val nativeVendorDir = layout.projectDirectory.dir("src/main/cpp/vendor").asFile
val generatedDir = layout.projectDirectory.dir("src/main/cpp/generated").asFile
val upstreamLockFile = rootProject.layout.projectDirectory.file("upstream.lock.json").asFile

fun ByteArray.hexDigest(algorithm: String): String =
    MessageDigest.getInstance(algorithm).digest(this).joinToString("") { "%02x".format(it) }

fun sourceTreeSha256(directory: File): String {
    check(directory.isDirectory) { "Source directory is missing: $directory" }
    val digest = MessageDigest.getInstance("SHA-256")
    directory.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
        .forEach { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            digest.update(path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.update(0)
        }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun githubGet(url: String): ByteArray {
    var current = URI(url).toURL()
    repeat(8) {
        val connection = current.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 30_000
        connection.readTimeout = 180_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "SpreadtrumFlashAndroid-Gradle-Updater")
        (System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN"))
            ?.takeIf { it.isNotBlank() }
            ?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: error("GitHub redirect has no target: $url")
            current = URI(current.toURI().resolve(location).toString()).toURL()
            connection.disconnect()
            return@repeat
        }
        if (code !in 200..299) {
            val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            error("GitHub download failed ($code): $url\n$details")
        }
        return connection.inputStream.use { it.readBytes() }.also { connection.disconnect() }
    }
    error("Too many redirects: $url")
}

@Suppress("UNCHECKED_CAST")
fun githubJson(url: String): Map<String, Any?> =
    JsonSlurper().parseText(githubGet(url).toString(StandardCharsets.UTF_8)) as Map<String, Any?>

fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

fun resolveCommit(repository: String, ref: String): String {
    val response = githubJson("https://api.github.com/repos/$repository/commits/${encoded(ref)}")
    return response["sha"]?.toString()?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
        ?: error("GitHub did not return a valid commit for $repository@$ref")
}

fun latestReleaseTag(repository: String): String {
    val response = githubJson("https://api.github.com/repos/$repository/releases/latest")
    return response["tag_name"]?.toString()?.takeIf { it.isNotBlank() }
        ?: error("GitHub did not return a current release tag for $repository")
}

fun writeJson(file: File, value: Any) {
    file.parentFile.mkdirs()
    file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
}

@Suppress("UNCHECKED_CAST")
fun readLock(): Map<String, Any?> {
    check(upstreamLockFile.isFile) {
        "upstream.lock.json is missing. Run './gradlew :app:updateUpstream'."
    }
    return JsonSlurper().parse(upstreamLockFile) as Map<String, Any?>
}

@Suppress("UNCHECKED_CAST")
fun lockSection(lock: Map<String, Any?>, name: String): Map<String, Any?> =
    lock[name] as? Map<String, Any?> ?: error("Lockfile section is missing: $name")

fun extractArchive(archive: File, destination: File) {
    delete(destination)
    destination.mkdirs()
    val unpack = layout.buildDirectory.dir("upstream/unpack-${archive.nameWithoutExtension}").get().asFile
    delete(unpack)
    copy { from(zipTree(archive)); into(unpack) }
    val roots = unpack.listFiles()?.filter { it.isDirectory }.orEmpty()
    check(roots.size == 1) { "Unexpected GitHub archive structure in ${archive.name}" }
    sync { from(roots.single()); into(destination) }
}

fun String.replaceRequired(old: String, new: String, label: String): String {
    check(contains(old)) { "Android patch point is missing: $label. Upstream was probably changed incompatibly." }
    return replace(old, new)
}

val updateUpstream by tasks.registering {
    group = "upstream"
    description = "Downloads the latest spreadtrum_flash commit and latest stable libusb release, then updates upstream.lock.json."

    doLast {
        val spreadtrumRequested = providers.gradleProperty("spreadtrumRef").orNull
            ?.takeIf { it.isNotBlank() } ?: "main"
        val libusbRequestedProperty = providers.gradleProperty("libusbRef").orNull
            ?.takeIf { it.isNotBlank() }
        val libusbRequested = libusbRequestedProperty ?: latestReleaseTag(libusbRepository)

        logger.lifecycle("Resolve $spreadtrumRepository@$spreadtrumRequested …")
        val spreadtrumCommit = resolveCommit(spreadtrumRepository, spreadtrumRequested)
        logger.lifecycle("Resolve $libusbRepository@$libusbRequested …")
        val libusbCommit = resolveCommit(libusbRepository, libusbRequested)

        val downloadDir = layout.buildDirectory.dir("upstream/downloads").get().asFile.apply { mkdirs() }
        val spreadtrumArchive = downloadDir.resolve("spreadtrum-$spreadtrumCommit.zip")
        val libusbArchive = downloadDir.resolve("libusb-$libusbCommit.zip")

        if (!spreadtrumArchive.isFile) {
            spreadtrumArchive.writeBytes(
                githubGet("https://codeload.github.com/$spreadtrumRepository/zip/$spreadtrumCommit"),
            )
        }
        if (!libusbArchive.isFile) {
            libusbArchive.writeBytes(
                githubGet("https://codeload.github.com/$libusbRepository/zip/$libusbCommit"),
            )
        }

        val stagingRoot = layout.buildDirectory.dir("upstream/staging").get().asFile
        delete(stagingRoot)
        val stagedSpreadtrum = stagingRoot.resolve("spreadtrum_flash")
        val stagedLibusb = stagingRoot.resolve("libusb")
        extractArchive(spreadtrumArchive, stagedSpreadtrum)
        extractArchive(libusbArchive, stagedLibusb)

        listOf("common.c", "common.h", "spd_cmd.h", "spd_dump.c").forEach { required ->
            check(stagedSpreadtrum.resolve(required).isFile) {
                "$spreadtrumRepository@$spreadtrumCommit no longer contains $required. Update cancelled."
            }
        }
        listOf("COPYING", "android/config.h", "libusb/core.c", "libusb/libusb.h").forEach { required ->
            check(stagedLibusb.resolve(required).isFile) {
                "$libusbRepository@$libusbCommit no longer contains $required. Update cancelled."
            }
        }

        writeJson(
            stagedSpreadtrum.resolve(".upstream-ref.json"),
            linkedMapOf(
                "repository" to spreadtrumRepository,
                "requestedRef" to spreadtrumRequested,
                "resolvedCommit" to spreadtrumCommit,
            ),
        )
        writeJson(
            stagedLibusb.resolve(".upstream-ref.json"),
            linkedMapOf(
                "repository" to libusbRepository,
                "requestedRef" to libusbRequested,
                "resolvedCommit" to libusbCommit,
            ),
        )
        stagedLibusb.resolve(".version").writeText("$libusbRequested\n")

        val spreadtrumTree = sourceTreeSha256(stagedSpreadtrum)
        val libusbTree = sourceTreeSha256(stagedLibusb)
        val lock = linkedMapOf(
            "schema" to 1,
            "updatedAtUtc" to Instant.now().toString(),
            "spreadtrumFlash" to linkedMapOf(
                "repository" to spreadtrumRepository,
                "requestedRef" to spreadtrumRequested,
                "commit" to spreadtrumCommit,
                "archiveSha256" to spreadtrumArchive.readBytes().hexDigest("SHA-256"),
                "sourceTreeSha256" to spreadtrumTree,
            ),
            "libusb" to linkedMapOf(
                "repository" to libusbRepository,
                "requestedRef" to libusbRequested,
                "commit" to libusbCommit,
                "archiveSha256" to libusbArchive.readBytes().hexDigest("SHA-256"),
                "sourceTreeSha256" to libusbTree,
            ),
        )

        // Replace both trees only after both archives and required files passed validation.
        val targetSpreadtrum = nativeVendorDir.resolve("spreadtrum_flash")
        val targetLibusb = nativeVendorDir.resolve("libusb")
        delete(targetSpreadtrum)
        delete(targetLibusb)
        sync { from(stagedSpreadtrum); into(targetSpreadtrum) }
        sync { from(stagedLibusb); into(targetLibusb) }
        writeJson(upstreamLockFile, lock)

        logger.lifecycle("Updated spreadtrum_flash to $spreadtrumCommit")
        logger.lifecycle("Updated libusb to $libusbRequested ($libusbCommit)")
        logger.lifecycle("Lockfile: ${upstreamLockFile.relativeTo(rootProject.projectDir)}")
    }
}

val checkUpstreamUpdates by tasks.registering {
    group = "upstream"
    description = "Checks GitHub for newer upstream refs without modifying the project."

    doLast {
        val lock = readLock()
        val spreadtrum = lockSection(lock, "spreadtrumFlash")
        val libusb = lockSection(lock, "libusb")
        val latestSpreadtrum = resolveCommit(spreadtrumRepository, "main")
        val latestLibusbTag = latestReleaseTag(libusbRepository)
        val latestLibusbCommit = resolveCommit(libusbRepository, latestLibusbTag)
        val lockedSpreadtrum = spreadtrum["commit"].toString()
        val lockedLibusb = libusb["commit"].toString()

        logger.lifecycle(
            "spreadtrum_flash: locked=$lockedSpreadtrum latest=$latestSpreadtrum " +
                if (lockedSpreadtrum == latestSpreadtrum) "(current)" else "(update available)",
        )
        logger.lifecycle(
            "libusb: locked=${libusb["requestedRef"]} ($lockedLibusb) " +
                "latest=$latestLibusbTag ($latestLibusbCommit) " +
                if (lockedLibusb == latestLibusbCommit) "(current)" else "(update available)",
        )
    }
}

val vendorNativeSources by tasks.registering {
    group = "build setup"
    description = "Validates locked native sources and generates the Android-compatible spd_dump translation unit."
    inputs.file(upstreamLockFile)
    inputs.dir(nativeVendorDir.resolve("spreadtrum_flash"))
    inputs.dir(nativeVendorDir.resolve("libusb"))
    outputs.dir(generatedDir)
    mustRunAfter(updateUpstream)

    doLast {
        val lock = readLock()
        val spreadtrum = lockSection(lock, "spreadtrumFlash")
        val libusb = lockSection(lock, "libusb")
        val upstreamDir = nativeVendorDir.resolve("spreadtrum_flash")
        val libusbDir = nativeVendorDir.resolve("libusb")

        val actualSpreadtrumTree = sourceTreeSha256(upstreamDir)
        val actualLibusbTree = sourceTreeSha256(libusbDir)
        check(actualSpreadtrumTree == spreadtrum["sourceTreeSha256"].toString()) {
            "spreadtrum_flash was modified outside the updater task. " +
                "Expected ${spreadtrum["sourceTreeSha256"]}, found $actualSpreadtrumTree"
        }
        check(actualLibusbTree == libusb["sourceTreeSha256"].toString()) {
            "libusb was modified outside the updater task. " +
                "Expected ${libusb["sourceTreeSha256"]}, found $actualLibusbTree"
        }

        generatedDir.mkdirs()
        var patchedDump = upstreamDir.resolve("spd_dump.c").readText()
        if (!patchedDump.contains("spd_android_set_command")) {
            patchedDump = patchedDump.replaceRequired(
                "\t\tif (!strcmp(str2[1], \"sendloop\")) {",
                "\t\tspd_android_set_command(str2[1]);\n\t\tif (!strcmp(str2[1], \"sendloop\")) {",
                "command progress hook",
            )
        }
        if (!patchedDump.contains("!strcmp(str2[1], \"quit\")")) {
            patchedDump = patchedDump.replaceRequired(
                "\t\telse if (!strcmp(str2[1], \"reset\")) {",
                "\t\telse if (!strcmp(str2[1], \"quit\") || !strcmp(str2[1], \"exit\")) {\n" +
                    "\t\t\tbreak;\n\n\t\t}\n" +
                    "\t\telse if (!strcmp(str2[1], \"reset\")) {",
                "Android quit command",
            )
        }
        generatedDir.resolve("spd_dump_android.c").writeText(patchedDump)
        generatedDir.resolve("GITVER.h").writeText(
            "#pragma once\n" +
                "#define GIT_VER \"android-port-v2\"\n" +
                "#define GIT_SHA1 \"${spreadtrum["commit"]}\"\n",
        )
    }
}

tasks.named("preBuild").configure { dependsOn(vendorNativeSources) }
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("externalNativeBuild")) {
        dependsOn(vendorNativeSources)
    }
}

val updateUpstreamAndBuild by tasks.registering {
    group = "upstream"
    description = "Updates both native upstreams, locks them, validates the Android patches, and builds the debug APK."
    dependsOn(updateUpstream, "assembleDebug")
}

tasks.matching { it.name == "assembleDebug" }.configureEach { mustRunAfter(updateUpstream) }
