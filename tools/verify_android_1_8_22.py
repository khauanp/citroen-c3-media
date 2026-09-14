#!/usr/bin/env python3
"""Verify the media-only 1.8.22 source and K00E runtime before packaging."""

from __future__ import annotations

import hashlib
import base64
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LITE = ROOT / "app/src/lite"

EXPECTED_NATIVE = {
    "libairplay_native.so": "327b381a2719aaa176f70a69d231e3c4671357c4cb0c87be74b99eb183c3e5b5",
    "libc++_shared.so": "649cf75deda40f5985f316d1cc63cba59466db453e610a14e983e36be0caaa94",
    "liboboe.so": "5541263e80ba3a4471a1372d08d1dd72fc8378d6a90deb598bf07525656c23eb",
}


def require(source: str, *markers: str) -> None:
    missing = [marker for marker in markers if marker not in source]
    if missing:
        raise RuntimeError(f"missing required markers: {missing}")


def main() -> int:
    for name, expected in EXPECTED_NATIVE.items():
        parts = sorted((LITE / "native-prebuilt/x86").glob(f"{name}.b64.*"))
        if not parts:
            raise RuntimeError(f"missing encoded native binary: {name}")
        binary = base64.b64decode(b"".join(part.read_bytes() for part in parts))
        actual = hashlib.sha256(binary).hexdigest()
        if actual != expected:
            raise RuntimeError(f"unexpected {name}: {actual}")

    kotlin = "\n".join(path.read_text(encoding="utf-8") for path in (LITE / "kotlin").rglob("*.kt"))
    for forbidden in (
        "RouteReceiverServer",
        "RouteWebViewModule",
        "DacpController(",
        "RadioMediaSession(",
        "RadioButtonReceiver(",
    ):
        if forbidden in kotlin:
            raise RuntimeError(f"removed component is active: {forbidden}")

    dashboard = (LITE / "kotlin/io/github/jqssun/airplay/ui/DashboardView.kt").read_text()
    require(
        dashboard,
        "drawMirrorOverlay(canvas)",
        "drawRotatingArtwork",
        "applyAutomaticTheme()",
        "DayNightPolicy.isDaytimeNow()",
        "actions?.onCloseApp()",
        "formatTime(position)",
        "formatTime(duration)",
    )
    if "drawMirrorChrome" in dashboard:
        raise RuntimeError("old modular mirroring chrome is still active")

    activity = (LITE / "kotlin/io/github/jqssun/airplay/MainActivity.kt").read_text()
    require(
        activity,
        "FrameLayout.LayoutParams.MATCH_PARENT",
        "DayNightPolicy.activeBrightnessNow()",
        "moveTaskToBack(true)",
        'debugDemoMode == "audio-probe"',
        'CrashDiagnostics.event("USER_ACTION"',
    )
    if "updateSurfaceLayout(modular" in activity or "778f * sx" in activity:
        raise RuntimeError("old reduced mirror surface remains")

    audio = (LITE / "kotlin/io/github/jqssun/airplay/renderer/AudioRenderer.kt").read_text()
    require(audio, "NETWORK_CUSHION_MS = 2_000", "true,\n                false,")

    service = (LITE / "kotlin/io/github/jqssun/airplay/service/AirPlayService.kt").read_text()
    require(service, "fun onAudioActivity()", 'CrashDiagnostics.event("AIRPLAY"')

    diagnostics = (LITE / "kotlin/io/github/jqssun/airplay/CrashDiagnostics.kt").read_text()
    require(
        diagnostics,
        "UNEXPECTED_PROCESS_TERMINATION",
        "UNCAUGHT_EXCEPTION",
        "diagnostics-current.log",
        "last_uptime_ms",
        "prompt_pending",
        "writeReportLocked",
    )

    lifecycle = (ROOT / "tools/test_api21_lifecycle.sh").read_text()
    require(lifecycle, "C3_AUDIO_PROBE_PASS", "files/last-crash.txt")
    if "files/last_crash.txt" in lifecycle:
        raise RuntimeError("obsolete crash-file spelling remains")

    gradle = (ROOT / "app/build.gradle.kts").read_text()
    require(
        gradle,
        'versionName = "1.8.22"',
        "prepareK00eNativeStack",
        "verifyK00eNativeStack",
        "Base64.getDecoder().decode(encoded)",
    )
    print("PASS: 1.8.22 full-screen UI, automatic theme and verified K00E audio stack")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
