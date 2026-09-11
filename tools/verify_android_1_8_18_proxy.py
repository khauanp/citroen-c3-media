#!/usr/bin/env python3
"""Verify the isolated Android 5 WebView route receiver over 1.8.16."""

from __future__ import annotations

import sys
from pathlib import Path

from verify_android_1_8_5_maintenance import method, require_equal_tree
from verify_android_1_8_8_stability import all_smali


ACTIVITY = Path("io/github/jqssun/airplay/MainActivity.smali")
SERVICE = Path("io/github/jqssun/airplay/service/AirPlayService.smali")
DASHBOARD = Path("io/github/jqssun/airplay/ui/DashboardView.smali")
ROUTE_SERVER = Path("io/github/jqssun/airplay/connectivity/RouteReceiverServer.smali")
ROUTE_MODULE = Path("io/github/jqssun/airplay/connectivity/RouteWebViewModule.smali")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: verify_android_1_8_18_proxy.py REFERENCE_1816 REBUILT_1818"
        )
    reference_root = Path(sys.argv[1]).resolve()
    rebuilt_root = Path(sys.argv[2]).resolve()
    reference = all_smali(reference_root)
    rebuilt = all_smali(rebuilt_root)

    if rebuilt[DASHBOARD] != reference[DASHBOARD]:
        raise RuntimeError("existing dashboard/map renderer changed")
    if rebuilt[SERVICE] != reference[SERVICE]:
        raise RuntimeError("1.8.16 AirPlay/media service changed")
    if b"AUDIO_PLAYBACK_IDLE_MS:J = 0x2ee0L" not in rebuilt[SERVICE]:
        raise RuntimeError("delivered 1.8.16 playback timer was not preserved")

    changed = {path for path in reference.keys() & rebuilt if reference[path] != rebuilt[path]}
    added = set(rebuilt) - set(reference)
    deleted = set(reference) - set(rebuilt)
    allowed_added = {
        path for path in rebuilt
        if path.name.startswith("RouteReceiverServer")
        or path.name.startswith("RouteWebViewModule")
        or path.name.startswith("RouteResourceProxy")
        or path.name.startswith("RouteProxyWebViewClient")
        or str(path).startswith("fi/iki/elonen/NanoHTTPD")
    }
    if changed != {ACTIVITY} or added != allowed_added or deleted:
        raise RuntimeError(
            f"unexpected APK scope: {sorted(changed)=} {sorted(added - allowed_added)=} {sorted(deleted)=}"
        )
    if ROUTE_SERVER not in rebuilt or ROUTE_MODULE not in rebuilt:
        raise RuntimeError("route receiver classes are missing")

    create = method(rebuilt[ACTIVITY], b"protected onCreate(Landroid/os/Bundle;)V")
    destroy = method(rebuilt[ACTIVITY], b"protected onDestroy()V")
    if b"RouteWebViewModule;->start(Landroid/app/Activity;)V" not in create:
        raise RuntimeError("route module does not start after Activity creation")
    if b"RouteWebViewModule;->stop(Landroid/app/Activity;)V" not in destroy:
        raise RuntimeError("route module is not stopped with the Activity")

    server = rebuilt[ROUTE_SERVER]
    for marker in (b"NanoHTTPD", b"/set-route", b"waze_url", b"ROTA_RECEBIDA", b"PARAMETRO_INVALIDO"):
        if marker not in server:
            raise RuntimeError(f"route receiver marker missing: {marker!r}")

    module = rebuilt[ROUTE_MODULE]
    for marker in (
        b"const/16 v", b"0x1f90", b"setJavaScriptEnabled", b"setDomStorageEnabled",
        b"setMixedContentMode", b"WebView;->loadDataWithBaseURL", b"RouteReceiverServer;->start",
    ):
        if marker not in module:
            raise RuntimeError(f"WebView module marker missing: {marker!r}")

    layout = (rebuilt_root / "res/layout/waze_webview.xml").read_bytes()
    for marker in (b"WebView", b"wazeWebView", b"match_parent"):
        if marker not in layout:
            raise RuntimeError(f"WebView layout marker missing: {marker!r}")

    manifest = (rebuilt_root / "AndroidManifest.xml").read_bytes()
    for marker in (b"android.permission.INTERNET", b"android.permission.ACCESS_NETWORK_STATE", b"usesCleartextTraffic"):
        if marker not in manifest:
            raise RuntimeError(f"manifest marker missing: {marker!r}")

    for name in ("lib", "assets", "unknown"):
        require_equal_tree(reference_root / name, rebuilt_root / name, name)
    config = (rebuilt_root / "apktool.yml").read_text(encoding="utf-8")
    if "versionCode: 10818" not in config or "versionName: 1.8.18" not in config:
        raise RuntimeError("rebuilt APK does not report 1.8.18")

    print("verified: isolated WebView/NanoHTTPD route viewer; unchanged 1.8.16 media; x86-only native libs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
