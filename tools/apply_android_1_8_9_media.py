#!/usr/bin/env python3
"""Apply the 1.8.9 media-only maintenance after reproducing exact 1.8.8."""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_8_stability import main as apply_1_8_8


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")
AUDIO = Path("smali/io/github/jqssun/airplay/renderer/AudioRenderer.smali")
MANIFEST = Path("AndroidManifest.xml")
HOTSPOT = Path("smali/io/github/jqssun/airplay/connectivity/HotspotController.smali")


def replace_method(value: str, signature: str, body: str) -> str:
    start = value.find(signature)
    if start < 0:
        raise RuntimeError(f"method not found: {signature}")
    end = value.find("\n.end method", start)
    if end < 0:
        raise RuntimeError(f"method end not found: {signature}")
    end += len("\n.end method")
    return value[:start] + body + value[end:]


def copy_media_helpers(root: Path, helper: Path) -> None:
    target = root / "smali/io/github/jqssun/airplay/audio"
    patterns = (
        "DacpController*.smali",
        "RadioMediaSession*.smali",
        "RadioButtonReceiver*.smali",
    )
    for pattern in patterns:
        for old in target.glob(pattern):
            old.unlink()
    copied: set[str] = set()
    for smali_root in sorted(helper.glob("smali*")):
        source = smali_root / "io/github/jqssun/airplay/audio"
        if not source.is_dir():
            continue
        for pattern in patterns:
            for item in source.glob(pattern):
                shutil.copyfile(item, target / item.name)
                copied.add(item.name)
    for required in ("DacpController.smali", "RadioMediaSession.smali", "RadioButtonReceiver.smali"):
        if required not in copied:
            raise RuntimeError(f"compiled media helper not found: {required}")


def patch_audio(root: Path) -> None:
    path = root / AUDIO
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, "    const/16 v6, 0xdc", "    const/16 v6, 0x258", "600 ms AirPlay cushion")
    value = replace_once(value, "    const/4 v8, 0x0", "    const/16 v8, 0x2000", "8192 frame A2DP output buffer")
    path.write_text(value, encoding="utf-8")


def patch_service_safety(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    signature = ".method private static final queueCoverArt$lambda$31$lambda$30$lambda$29(Lio/github/jqssun/airplay/service/AirPlayService;Landroid/graphics/Bitmap;)V"
    safe_body = signature + """
    .locals 0

    # Bitmap belongs to immutable UI snapshots. Android 5 Canvas can still be
    # rendering an older snapshot after a track change, so explicit recycling
    # here can kill the process with \"Canvas: trying to use a recycled bitmap\".
    # Let the VM reclaim it after all snapshots release their reference.
    return-void
.end method"""
    value = replace_method(value, signature, safe_body)
    path.write_text(value, encoding="utf-8")


def patch_manifest(root: Path) -> None:
    path = root / MANIFEST
    value = path.read_text(encoding="utf-8")
    anchor = '        <receiver android:enabled="true" android:exported="true" android:name="io.github.jqssun.airplay.BootReceiver">'
    receiver = '''        <receiver android:enabled="true" android:exported="true" android:name="io.github.jqssun.airplay.audio.RadioButtonReceiver">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON"/>
            </intent-filter>
        </receiver>
'''
    value = replace_once(value, anchor, receiver + anchor, "legacy radio media-button receiver")
    path.write_text(value, encoding="utf-8")


def patch_hotspot_name(root: Path) -> None:
    path = root / HOTSPOT
    value = path.read_text(encoding="utf-8")
    value = replace_once(
        value,
        '    iget-object v4, v2, Landroid/net/wifi/WifiConfiguration;->SSID:Ljava/lang/String;\n\n'
        '    iput-object v4, v2, Landroid/net/wifi/WifiConfiguration;->SSID:Ljava/lang/String;',
        '    const-string v4, "Citroen C3"\n\n'
        '    iput-object v4, v2, Landroid/net/wifi/WifiConfiguration;->SSID:Ljava/lang/String;',
        "non-null hotspot SSID",
    )
    value = value.replace('"Citroen-C3"', '"Citroen C3"')
    if '"Citroen-C3"' in value:
        raise RuntimeError("old hotspot name remains in HotspotController")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_9_media.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_8_stability.py", str(root), str(helper)]
        apply_1_8_8()
    finally:
        sys.argv = old_argv

    copy_media_helpers(root, helper)
    patch_audio(root)
    patch_service_safety(root)
    patch_manifest(root)
    patch_hotspot_name(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10808", "  versionCode: 10809", "versionCode")
    config = replace_once(config, "  versionName: 1.8.8", "  versionName: 1.8.9", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
