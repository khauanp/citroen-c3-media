#!/usr/bin/env python3
"""Build 1.8.16 from the bootable 1.8.11 base with a locked audio session."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_11_daylight import main as apply_1_8_11
from apply_android_1_8_15_recovery import patch_pause_guard


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")


def replace_method(value: str, signature: str, body: str) -> str:
    start = value.find(signature)
    if start < 0:
        raise RuntimeError(f"method not found: {signature}")
    end = value.find("\n.end method", start)
    if end < 0:
        raise RuntimeError(f"method end not found: {signature}")
    end += len("\n.end method")
    return value[:start] + body + value[end:]


def patch_media_lock(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")

    # A pause between AirPlay tracks is not a session end.  Do not hide the
    # player or publish a false stopped state merely because PCM is late.
    playback_idle = ".method private static final delayedPlaybackIdle$lambda$3(Lio/github/jqssun/airplay/service/AirPlayService;)V"
    value = replace_method(
        value,
        playback_idle,
        playback_idle + """
    .locals 0

    return-void
.end method""",
    )

    # iOS emits audio-teardown while changing source/track.  Keep AudioTrack,
    # audio focus and the visible player intact.  A confirmed connection loss
    # still invokes delayedStreamCleanup and returns to the dashboard.
    teardown = ".method private static final onAudioTeardown$lambda$19(Lio/github/jqssun/airplay/service/AirPlayService;)V"
    value = replace_method(
        value,
        teardown,
        teardown + """
    .locals 0

    return-void
.end method""",
    )

    # The sender may briefly announce audioOnly=false while replacing its RAOP
    # stream.  It is not authoritative enough to leave the player; only the
    # confirmed zero-connection cleanup may return to the dashboard.
    audio_only = ".method public onAudioOnly(Z)V"
    value = replace_method(
        value,
        audio_only,
        audio_only + """
    .locals 2

    if-nez p1, :c3_audio_only_active

    return-void

    :c3_audio_only_active
    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->mainHandler:Landroid/os/Handler;

    new-instance v1, Lio/github/jqssun/airplay/service/AirPlayService$$ExternalSyntheticLambda7;

    invoke-direct {v1, p0, p1}, Lio/github/jqssun/airplay/service/AirPlayService$$ExternalSyntheticLambda7;-><init>(Lio/github/jqssun/airplay/service/AirPlayService;Z)V

    invoke-virtual {v0, v1}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z

    return-void
.end method""",
    )

    # Allow a full 90 seconds for screen-off, Control Center, Waze and poor RF
    # handovers before treating the iPhone as truly disconnected.
    value = replace_once(
        value,
        ".field private static final CONNECTION_CLEANUP_GRACE_MS:J = 0x4e20L",
        ".field private static final CONNECTION_CLEANUP_GRACE_MS:J = 0x15f90L",
        "90-second confirmed disconnect grace",
    )
    value = replace_once(
        value,
        "    const-wide/16 v2, 0x4e20",
        "    const-wide/32 v2, 0x15f90",
        "90-second disconnect cleanup delay",
    )

    # Keep only the newest artwork job.  This bounds memory and prevents an old
    # decode from replacing the cover of a newer song after rapid skips.
    queue_cover = ".method private final queueCoverArt([B)V"
    value = replace_method(
        value,
        queue_cover,
        queue_cover + """
    .locals 3

    :try_start_0
    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->artworkDecoder:Ljava/util/concurrent/ThreadPoolExecutor;

    invoke-virtual {v0}, Ljava/util/concurrent/ThreadPoolExecutor;->getQueue()Ljava/util/concurrent/BlockingQueue;

    move-result-object v1

    invoke-interface {v1}, Ljava/util/concurrent/BlockingQueue;->clear()V

    new-instance v1, Lio/github/jqssun/airplay/service/AirPlayService$$ExternalSyntheticLambda26;

    invoke-direct {v1, p0, p1}, Lio/github/jqssun/airplay/service/AirPlayService$$ExternalSyntheticLambda26;-><init>(Lio/github/jqssun/airplay/service/AirPlayService;[B)V

    invoke-virtual {v0, v1}, Ljava/util/concurrent/ThreadPoolExecutor;->execute(Ljava/lang/Runnable;)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    return-void

    :catchall_0
    move-exception v0

    const-string v1, "C3MediaService"

    const-string v2, "Cover art queue ignored"

    invoke-static {v1, v2, v0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method""",
    )

    # Reject unusually large payloads before allocation.  Standard YouTube
    # artwork remains accepted and is decoded to the existing 360 px RGB565 cap.
    value = replace_once(
        value,
        "    const/high16 v1, 0x300000",
        "    const/high16 v1, 0x100000",
        "1 MiB artwork input fuse",
    )

    # The old one-second recycle raced the tablet UI; never recycling leaked
    # every skipped cover.  Sixty seconds gives all snapshots time to detach
    # while keeping long drives memory-bounded.
    value = replace_once(
        value,
        "    const-wide/16 v0, 0x3e8\n\n    invoke-virtual {v2, v3, v0, v1}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z",
        "    const-wide/32 v0, 0xea60\n\n    invoke-virtual {v2, v3, v0, v1}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z",
        "60-second safe artwork retirement",
    )
    recycler = ".method private static final queueCoverArt$lambda$31$lambda$30$lambda$29(Lio/github/jqssun/airplay/service/AirPlayService;Landroid/graphics/Bitmap;)V"
    value = replace_method(
        value,
        recycler,
        recycler + """
    .locals 1

    invoke-virtual {p1}, Landroid/graphics/Bitmap;->isRecycled()Z

    move-result v0

    if-nez v0, :c3_cover_retired

    invoke-virtual {p1}, Landroid/graphics/Bitmap;->recycle()V

    :c3_cover_retired
    return-void
.end method""",
    )

    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: apply_android_1_8_16_media_lock.py APKTOOL_DIRECTORY HELPER_DECODE"
        )
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_11_daylight.py", str(root), str(helper)]
        apply_1_8_11()
    finally:
        sys.argv = old_argv

    patch_pause_guard(root)
    patch_media_lock(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10811", "  versionCode: 10816", "versionCode")
    config = replace_once(config, "  versionName: 1.8.11", "  versionName: 1.8.16", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
