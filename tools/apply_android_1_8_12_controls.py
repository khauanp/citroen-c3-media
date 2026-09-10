#!/usr/bin/env python3
"""Apply the 1.8.12 pause guard and iPhone controls over exact 1.8.11."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_11_daylight import main as apply_1_8_11


SERVICE = Path("smali/io/github/jqssun/airplay/service/AirPlayService.smali")
DASHBOARD = Path("smali/io/github/jqssun/airplay/ui/DashboardView.smali")
ACTIVITY = Path("smali/io/github/jqssun/airplay/MainActivity.smali")
ROOT = Path(__file__).resolve().parent


def patch_service(root: Path) -> None:
    path = root / SERVICE
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, ".field private static final AUDIO_PLAYBACK_IDLE_MS:J = 0xdacL", ".field private static final AUDIO_PLAYBACK_IDLE_MS:J = 0x2ee0L", "12 s playback-idle grace")
    value = replace_once(value, "    const-wide/16 v3, 0xdac", "    const-wide/16 v3, 0x2ee0", "12 s silence watchdog")
    dispatch = r'''.method public final dispatchMediaKey(I)V
    .locals 1

    iget-object v0, p0, Lio/github/jqssun/airplay/service/AirPlayService;->radioMediaSession:Lio/github/jqssun/airplay/audio/RadioMediaSession;

    if-eqz v0, :c3_media_key_done

    invoke-virtual {v0, p1}, Lio/github/jqssun/airplay/audio/RadioMediaSession;->dispatchMediaKey(I)V

    :c3_media_key_done
    return-void
.end method

'''
    value = replace_once(value, ".method public final nextTrack()V", dispatch + ".method public final nextTrack()V", "service media-key bridge")
    path.write_text(value, encoding="utf-8")


def patch_dashboard(root: Path) -> None:
    path = root / DASHBOARD
    value = path.read_text(encoding="utf-8")
    draw_controls = r'''.method private final drawPlayerControls(Landroid/graphics/Canvas;)V
    .locals 8

    move-object v0, p0
    move-object v1, p1
    const v2, 0x44308000           # 706.0f
    const/high16 v3, 0x43fa0000    # 500.0f
    const/high16 v4, 0x44980000    # 1216.0f
    const v5, 0x44138000           # 590.0f
    const/high16 v6, 0x41c00000    # 24.0f
    sget v7, Lio/github/jqssun/airplay/ui/DashboardView;->CARD:I
    invoke-direct/range {v0 .. v7}, Lio/github/jqssun/airplay/ui/DashboardView;->card(Landroid/graphics/Canvas;FFFFFI)V

    iget-object v0, p0, Lio/github/jqssun/airplay/ui/DashboardView;->paint:Landroid/graphics/Paint;
    const/16 v1, 0x24
    const/16 v2, 0xff
    invoke-static {v1, v2, v2, v2}, Landroid/graphics/Color;->argb(IIII)I
    move-result v1
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
    const v1, 0x44458000           # 790.0f
    const v2, 0x44084000           # 545.0f
    const/high16 v3, 0x42280000    # 42.0f
    invoke-virtual {p1, v1, v2, v3, v0}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V
    const/high16 v1, 0x44700000    # 960.0f
    invoke-virtual {p1, v1, v2, v3, v0}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V
    const v1, 0x448d4000           # 1130.0f
    invoke-virtual {p1, v1, v2, v3, v0}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V

    iget-object v0, p0, Lio/github/jqssun/airplay/ui/DashboardView;->linePaint:Landroid/graphics/Paint;
    sget v1, Lio/github/jqssun/airplay/ui/DashboardView;->WHITE:I
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
    const/high16 v1, 0x40e00000    # 7.0f
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setStrokeWidth(F)V

    new-instance v1, Landroid/graphics/Path;
    invoke-direct {v1}, Landroid/graphics/Path;-><init>()V
    const/high16 v2, 0x44480000    # 800.0f
    const/high16 v3, 0x44030000    # 524.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    const/high16 v3, 0x440d0000    # 564.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    const/high16 v3, 0x44030000    # 524.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    const/high16 v2, 0x44408000    # 770.0f
    const v3, 0x44084000           # 545.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    const/high16 v2, 0x44480000    # 800.0f
    const/high16 v3, 0x440d0000    # 564.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V

    iget-object v1, p0, Lio/github/jqssun/airplay/ui/DashboardView;->media:Lio/github/jqssun/airplay/service/MediaState;
    invoke-virtual {v1}, Lio/github/jqssun/airplay/service/MediaState;->getPlaying()Z
    move-result v1
    if-eqz v1, :c3_draw_play
    const/high16 v1, 0x446c0000    # 944.0f
    const/high16 v2, 0x44030000    # 524.0f
    const/high16 v3, 0x446c0000    # 944.0f
    const/high16 v4, 0x440d0000    # 564.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    invoke-virtual {v1, v4, v3}, Landroid/graphics/Path;->lineTo(FF)V
    const/high16 v1, 0x44740000    # 976.0f
    const/high16 v3, 0x44740000    # 976.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    invoke-virtual {v1, v4, v3}, Landroid/graphics/Path;->lineTo(FF)V
    goto :c3_draw_next

    :c3_draw_play
    new-instance v1, Landroid/graphics/Path;
    invoke-direct {v1}, Landroid/graphics/Path;-><init>()V
    const/high16 v2, 0x446c0000    # 944.0f
    const/high16 v3, 0x44030000    # 524.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    const/high16 v2, 0x44780000    # 992.0f
    const v3, 0x44084000           # 545.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    const/high16 v2, 0x446c0000    # 944.0f
    const/high16 v3, 0x440d0000    # 564.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    invoke-virtual {v1}, Landroid/graphics/Path;->close()V
    iget-object v2, p0, Lio/github/jqssun/airplay/ui/DashboardView;->paint:Landroid/graphics/Paint;
    sget v3, Lio/github/jqssun/airplay/ui/DashboardView;->WHITE:I
    invoke-virtual {v2, v3}, Landroid/graphics/Paint;->setColor(I)V
    invoke-virtual {p1, v1, v2}, Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V

    :c3_draw_next
    new-instance v1, Landroid/graphics/Path;
    invoke-direct {v1}, Landroid/graphics/Path;-><init>()V
    const v2, 0x448c4000           # 1122.0f
    const/high16 v2, 0x44030000    # 524.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    const/high16 v3, 0x440d0000    # 564.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    const v2, 0x44884000           # 1090.0f
    const/high16 v3, 0x44030000    # 524.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->moveTo(FF)V
    const v2, 0x448c4000           # 1122.0f
    const v3, 0x44084000           # 545.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    const v2, 0x44884000           # 1090.0f
    const/high16 v3, 0x440d0000    # 564.0f
    invoke-virtual {v1, v2, v3}, Landroid/graphics/Path;->lineTo(FF)V
    invoke-virtual {p1, v1, v0}, Landroid/graphics/Canvas;->drawPath(Landroid/graphics/Path;Landroid/graphics/Paint;)V
    return-void
.end method

'''
    draw_controls = (ROOT / "android_1_8_12/DrawPlayerControls.smali.inc").read_text(encoding="utf-8")
    value = replace_once(value, ".method private final drawSpinningRecord", draw_controls + ".method private final drawSpinningRecord", "player controls drawing method")
    value = replace_once(value, "    .line 304\n    iget-object v1, v0, Lio/github/jqssun/airplay/ui/DashboardView;->media:Lio/github/jqssun/airplay/service/MediaState;", "    invoke-direct {v0, v1}, Lio/github/jqssun/airplay/ui/DashboardView;->drawPlayerControls(Landroid/graphics/Canvas;)V\n\n    .line 304\n    iget-object v1, v0, Lio/github/jqssun/airplay/ui/DashboardView;->media:Lio/github/jqssun/airplay/service/MediaState;", "draw controls below track information")

    touch = r'''    iget-object p1, p0, Lio/github/jqssun/airplay/ui/DashboardView;->media:Lio/github/jqssun/airplay/service/MediaState;
    invoke-virtual {p1}, Lio/github/jqssun/airplay/service/MediaState;->getMode()Lio/github/jqssun/airplay/service/DisplayMode;
    move-result-object p1
    sget-object v2, Lio/github/jqssun/airplay/service/DisplayMode;->AUDIO:Lio/github/jqssun/airplay/service/DisplayMode;
    if-ne p1, v2, :c3_controls_miss
    const/high16 p1, 0x43f50000    # 490.0f
    cmpg-float p1, v1, p1
    if-gez p1, :c3_controls_miss
    const v2, 0x44174000           # 605.0f
    cmpl-float p1, v1, v2
    if-lez p1, :c3_controls_miss
    const v2, 0x44368000           # 730.0f
    cmpg-float p1, v0, v2
    if-gez p1, :c3_controls_miss
    const v2, 0x44988000           # 1220.0f
    cmpl-float p1, v0, v2
    if-lez p1, :c3_controls_miss
    invoke-virtual {p0, v4}, Lio/github/jqssun/airplay/ui/DashboardView;->performHapticFeedback(I)Z
    iget-object p1, p0, Lio/github/jqssun/airplay/ui/DashboardView;->actions:Lio/github/jqssun/airplay/ui/DashboardView$Actions;
    if-eqz p1, :goto_2
    const v2, 0x445ac000           # 875.0f
    cmpg-float v2, v0, v2
    if-ltz v2, :c3_controls_previous
    const v2, 0x4482a000           # 1045.0f
    cmpg-float v0, v0, v2
    if-ltz v0, :c3_controls_toggle
    invoke-interface {p1}, Lio/github/jqssun/airplay/ui/DashboardView$Actions;->onNext()V
    goto/16 :goto_2
    :c3_controls_previous
    invoke-interface {p1}, Lio/github/jqssun/airplay/ui/DashboardView$Actions;->onPrevious()V
    goto/16 :goto_2
    :c3_controls_toggle
    invoke-interface {p1}, Lio/github/jqssun/airplay/ui/DashboardView$Actions;->onPlayPause()V
    goto/16 :goto_2

    :c3_controls_miss
'''
    value = replace_once(value, "    :cond_5\n    iget-boolean p1, p0, Lio/github/jqssun/airplay/ui/DashboardView;->settingsTriggered:Z", "    :cond_5\n" + touch + "    iget-boolean p1, p0, Lio/github/jqssun/airplay/ui/DashboardView;->settingsTriggered:Z", "player control touch targets")
    path.write_text(value, encoding="utf-8")


def patch_activity(root: Path) -> None:
    path = root / ACTIVITY
    value = path.read_text(encoding="utf-8")
    method = r'''.method public dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 3

    if-eqz p1, :c3_key_super
    invoke-virtual {p1}, Landroid/view/KeyEvent;->getAction()I
    move-result v0
    if-nez v0, :c3_key_super
    invoke-virtual {p1}, Landroid/view/KeyEvent;->getRepeatCount()I
    move-result v0
    if-nez v0, :c3_key_super
    invoke-virtual {p1}, Landroid/view/KeyEvent;->getKeyCode()I
    move-result v0
    const/16 v1, 0x4f
    if-eq v0, v1, :c3_key_dispatch
    const/16 v1, 0x55
    if-eq v0, v1, :c3_key_dispatch
    const/16 v1, 0x57
    if-eq v0, v1, :c3_key_dispatch
    const/16 v1, 0x58
    if-eq v0, v1, :c3_key_dispatch
    const/16 v1, 0x7e
    if-eq v0, v1, :c3_key_dispatch
    const/16 v1, 0x7f
    if-ne v0, v1, :c3_key_super

    :c3_key_dispatch
    iget-object v1, p0, Lio/github/jqssun/airplay/MainActivity;->service:Lio/github/jqssun/airplay/service/AirPlayService;
    if-eqz v1, :c3_key_super
    invoke-virtual {v1, v0}, Lio/github/jqssun/airplay/service/AirPlayService;->dispatchMediaKey(I)V
    const/4 v2, 0x1
    return v2

    :c3_key_super
    invoke-super {p0, p1}, Landroid/app/Activity;->dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    move-result v0
    return v0
.end method

'''
    value = replace_once(value, ".method public onNext()V", method + ".method public onNext()V", "Activity Bluetooth HID media keys")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_12_controls.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()
    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_11_daylight.py", str(root), str(helper)]
        apply_1_8_11()
    finally:
        sys.argv = old_argv
    patch_service(root)
    patch_dashboard(root)
    patch_activity(root)
    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10811", "  versionCode: 10812", "versionCode")
    config = replace_once(config, "  versionName: 1.8.11", "  versionName: 1.8.12", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
