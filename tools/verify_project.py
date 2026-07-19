#!/usr/bin/env python3
"""Strict project checks that run without an Android SDK or network."""
from __future__ import annotations

from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java"
CPP = ROOT / "app/src/main/cpp"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

errors: list[str] = []
notes: list[str] = []


def text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        errors.append(f"Missing file: {path.relative_to(ROOT)}")
        return ""


# XML well-formedness and resource IDs.
xml_files = list(RES.rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]
for path in xml_files:
    try:
        ET.parse(path)
    except Exception as exc:  # noqa: BLE001
        errors.append(f"XML {path.relative_to(ROOT)}: {exc}")

ids: set[str] = set()
for path in RES.rglob("*.xml"):
    ids.update(re.findall(r"@\+id/([A-Za-z0-9_]+)", text(path)))

id_refs: set[str] = set()
for path in JAVA.rglob("*.kt"):
    id_refs.update(re.findall(r"R\.id\.([A-Za-z0-9_]+)", text(path)))
for missing in sorted(id_refs - ids):
    errors.append(f"Missing resource ID: {missing}")

# File resources and value resources referenced from XML/Kotlin/manifest.
file_resources: dict[str, set[str]] = {}
for folder in RES.iterdir():
    if not folder.is_dir() or folder.name.startswith("values"):
        continue
    resource_type = folder.name.split("-")[0]
    file_resources.setdefault(resource_type, set()).update(p.stem for p in folder.glob("*.*"))

value_resources: dict[str, set[str]] = {}
for values in RES.glob("values*"):
    for path in values.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except Exception:  # already reported
            continue
        for child in root:
            name = child.attrib.get("name")
            if name:
                kind = "style" if child.tag == "style" else child.tag
                value_resources.setdefault(kind, set()).add(name)

all_project_text = "\n".join(text(p) for p in [*RES.rglob("*.xml"), *JAVA.rglob("*.kt"), ROOT / "app/src/main/AndroidManifest.xml"])
for kind, name in re.findall(r"@([a-zA-Z_]+)/([A-Za-z0-9_.]+)", all_project_text):
    if kind in {"android", "id", "+id"}:
        continue
    if kind == "style" and name.startswith(("Widget.Material", "Theme.Material")):
        continue  # provided by the Material Components dependency
    available = file_resources.get(kind, set()) | value_resources.get(kind, set())
    if name not in available:
        errors.append(f"Missing @{kind} resource: {name}")

# Manifest invariants for USB/foreground operation.
manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
try:
    manifest = ET.parse(manifest_path).getroot()
    application = manifest.find("application")
    if application is None or application.attrib.get(ANDROID_NS + "name") != ".SpdFlashApplication":
        errors.append("Manifest does not use SpdFlashApplication")
    service = application.find("service") if application is not None else None
    if service is None or set((service.attrib.get(ANDROID_NS + "foregroundServiceType") or "").split("|")) != {"dataSync", "connectedDevice"}:
        errors.append("Foreground service must declare dataSync and connectedDevice")
except Exception:
    pass

filter_path = RES / "xml/device_filter.xml"
try:
    filter_root = ET.parse(filter_path).getroot()
    devices = filter_root.findall("usb-device")
    if not devices or any(d.attrib.get("vendor-id") != "6018" for d in devices):
        errors.append("USB filter must allow Spreadtrum/Unisoc VID 0x1782 (6018)")
    if any("product-id" in d.attrib for d in devices):
        errors.append("USB filter must not restrict re-enumeration to PID 0x4d00")
except Exception:
    pass

# Build toolchain compatibility and deterministic wrapper bootstrap.
root_build = text(ROOT / "build.gradle.kts")
app_build = text(ROOT / "app/build.gradle.kts")
wrapper_props = text(ROOT / "gradle/wrapper/gradle-wrapper.properties")
wrapper_sh = text(ROOT / "gradlew")
expected_markers = {
    "AGP 8.12.2": (root_build, 'com.android.application") version "8.12.2"'),
    "Kotlin 2.1.21": (root_build, 'org.jetbrains.kotlin.android") version "2.1.21"'),
    "compileSdk 36": (app_build, "compileSdk = 36"),
    "targetSdk 36": (app_build, "targetSdk = 36"),
    "JDK 17": (app_build, "JavaVersion.VERSION_17"),
    "NDK 28.2": (app_build, 'ndkVersion = "28.2.13676358"'),
    "Gradle 8.13 distribution": (wrapper_props, "gradle-8.13-bin.zip"),
    "Gradle 8.13 wrapper source": (wrapper_sh, "v8.13.0/gradle/wrapper/gradle-wrapper.jar"),
    "Gradle wrapper SHA-256": (wrapper_sh, "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"),
}
for label, (haystack, marker) in expected_markers.items():
    if marker not in haystack:
        errors.append(f"Build marker missing: {label}")
if "8.11.1" in wrapper_sh + text(ROOT / "gradlew.bat"):
    errors.append("Outdated Gradle 8.11.1 reference found")

# Reproducible one-file upstream updater and lock contract.
updater = text(ROOT / "gradle/upstream-update.gradle.kts")
lock_text = text(ROOT / "upstream.lock.json")
for marker in (
    "updateUpstreamAndBuild",
    "checkUpstreamUpdates",
    "releases/latest",
    "sourceTreeSha256",
    "upstream.lock.json",
    "codeload.github.com",
):
    if marker not in updater:
        errors.append(f"Upstream updater marker missing: {marker}")
for marker in (
    "d24c21a0c93a545c0130668caa1826d59ceebe48",
    "15a7ebb4d426c5ce196684347d2b7cafad862626",
    "sourceTreeSha256",
):
    if marker not in lock_text:
        errors.append(f"Upstream lock marker missing: {marker}")
if 'apply(from = rootProject.file("gradle/upstream-update.gradle.kts"))' not in app_build:
    errors.append("App build does not apply the one-file updater")

# Native bridge contracts and high-risk regressions.
native = text(CPP / "native_bridge.c")
cmake = text(CPP / "CMakeLists.txt")
bridge = text(JAVA / "io/github/lootdev78/spdflash/NativeBridge.kt")
for marker in (
    "libusb_wrap_sys_device",
    "spd_android_bulk_transfer",
    "g_cancel_requested",
    "onNativeOpenOutput",
    "onNativeOpenInput",
    "force_cleanup",
):
    if marker not in native + cmake + bridge:
        errors.append(f"Native integration marker missing: {marker}")
if native.count("CallIntMethod(env, g_callback, g_open_output_method") != 1:
    errors.append("SAF output callback must be called exactly once per fopen")
if 'strncmp(path, "content://", 10)' not in native:
    errors.append("Direct SAF input streaming is missing")

# Command coverage and safety architecture.
catalog = text(JAVA / "io/github/lootdev78/spdflash/CommandCatalog.kt")
command_count = catalog.count("CommandSpec(")
if command_count < 50:
    errors.append(f"Command coverage is too small: {command_count}")
for marker in ("PipelinePreflight.check", "RiskLevel.DESTRUCTIVE", "interruptedRun", "hasUsableTree"):
    if marker not in all_project_text:
        errors.append(f"Safety/recovery marker missing: {marker}")

# Optional pure Kotlin compilation catches syntax/type regressions without Android SDK.
kotlinc = subprocess.run(["sh", "-c", "command -v kotlinc"], capture_output=True, text=True).stdout.strip()
if kotlinc:
    with tempfile.TemporaryDirectory(prefix="spd-kotlin-") as temp:
        temp_path = Path(temp)
        stub = temp_path / "android/hardware/usb/UsbDevice.kt"
        stub.parent.mkdir(parents=True)
        stub.write_text(
            "package android.hardware.usb\n"
            "class UsbDevice(val vendorId:Int=0,val productId:Int=0,val productName:String?=null,"
            "val deviceName:String=\"\",val deviceId:Int=0)\n",
            encoding="utf-8",
        )
        logic_files = [
            "FlashModels.kt", "SizeParser.kt", "CommandCatalog.kt", "CommandValidator.kt",
            "ProgressParser.kt", "CommandLineParser.kt", "PipelinePreflight.kt", "NativeResult.kt",
        ]
        command = [kotlinc, str(stub)] + [str(JAVA / "io/github/lootdev78/spdflash" / name) for name in logic_files] + ["-d", str(temp_path / "logic.jar")]
        completed = subprocess.run(command, capture_output=True, text=True)
        if completed.returncode != 0:
            errors.append("Pure Kotlin compilation failed:\n" + completed.stderr.strip())
        else:
            notes.append("pure Kotlin logic compiled")
else:
    notes.append("kotlinc is unavailable; pure Kotlin compilation check skipped")

# Parse every Kotlin source with the compiler PSI to catch syntax errors in Android-dependent files.
syntax_script = ROOT / "tools/check_kotlin_syntax.kts"
if kotlinc and syntax_script.exists():
    syntax = subprocess.run([kotlinc, "-script", str(syntax_script), "-classpath", str(Path(kotlinc).resolve().parents[1] / "lib/kotlin-compiler.jar"), "--", str(JAVA), str(ROOT / "app/src/test/java")], capture_output=True, text=True)
    if syntax.returncode != 0:
        errors.append("Kotlin syntax check failed:\n" + (syntax.stderr or syntax.stdout).strip())
    else:
        notes.append("all Kotlin syntax checked")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print(
    f"OK: {len(xml_files)} XML files, {len(id_refs)} ID references, "
    f"{command_count} GUI commands; " + "; ".join(notes)
)
