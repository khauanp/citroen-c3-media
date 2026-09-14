#!/usr/bin/env python3
"""Apply automatic daylight theme and conservative thermal limits over 1.8.10."""

from __future__ import annotations

import sys
from pathlib import Path

from apply_android_1_8_6_maintenance import replace_once
from apply_android_1_8_10_media import main as apply_1_8_10


DASHBOARD = Path("smali/io/github/jqssun/airplay/ui/DashboardView.smali")
ACTIVITY = Path("smali/io/github/jqssun/airplay/MainActivity.smali")
ENERGY = Path("smali/io/github/jqssun/airplay/power/EnergyPolicy.smali")
ROOT = Path(__file__).resolve().parent


def rgb_assign(field: str, red: int, green: int, blue: int) -> str:
    return f"""    const/16 v1, 0x{red:x}

    const/16 v2, 0x{green:x}

    const/16 v3, 0x{blue:x}

    invoke-static {{v1, v2, v3}}, Landroid/graphics/Color;->rgb(III)I

    move-result v0

    sput v0, Lio/github/jqssun/airplay/ui/DashboardView;->{field}:I

"""


def copy_daylight_helper(root: Path) -> None:
    destination = root / "smali/io/github/jqssun/airplay/ui/DayNightPolicy.smali"
    destination.write_bytes((ROOT / "android_1_8_11/DayNightPolicy.smali").read_bytes())


def patch_dashboard(root: Path) -> None:
    path = root / DASHBOARD
    value = path.read_text(encoding="utf-8")
    for field in ("BG", "BG_2", "RAIL", "CARD", "DIVIDER", "WHITE", "MUTED", "MUTED_2", "GREEN", "AMBER"):
        value = replace_once(
            value,
            f".field private static final {field}:I",
            f".field private static {field}:I",
            f"mutable automatic palette field {field}",
        )

    day = {
        "BG": (232, 237, 244),
        "BG_2": (211, 220, 231),
        "RAIL": (250, 251, 253),
        "CARD": (255, 255, 255),
        "DIVIDER": (171, 182, 197),
        "WHITE": (18, 26, 38),
        "MUTED": (57, 69, 86),
        "MUTED_2": (83, 97, 116),
        "GREEN": (24, 137, 83),
        "AMBER": (176, 105, 0),
    }
    night = {
        "BG": (7, 9, 13),
        "BG_2": (15, 18, 25),
        "RAIL": (20, 23, 30),
        "CARD": (24, 28, 36),
        "DIVIDER": (54, 59, 69),
        "WHITE": (242, 245, 249),
        "MUTED": (162, 169, 180),
        "MUTED_2": (111, 119, 132),
        "GREEN": (75, 225, 145),
        "AMBER": (245, 178, 66),
    }
    method = """.method private final applyAutomaticTheme()V
    .locals 4

    invoke-static {}, Lio/github/jqssun/airplay/ui/DayNightPolicy;->isDaytimeNow()Z

    move-result v0

    if-eqz v0, :c3_night_palette

"""
    method += "".join(rgb_assign(name, *color) for name, color in day.items())
    method += "    return-void\n\n    :c3_night_palette\n"
    method += "".join(rgb_assign(name, *color) for name, color in night.items())
    method += "    return-void\n.end method\n\n"

    constructor = ".method public constructor <init>(Landroid/content/Context;)V"
    value = replace_once(value, constructor, method + constructor, "automatic theme method")
    draw_anchor = """    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    .line 126
    invoke-super {p0, p1}, Landroid/view/View;->onDraw(Landroid/graphics/Canvas;)V
"""
    draw_themed = """    invoke-static {p1, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V

    invoke-direct {p0}, Lio/github/jqssun/airplay/ui/DashboardView;->applyAutomaticTheme()V

    .line 126
    invoke-super {p0, p1}, Landroid/view/View;->onDraw(Landroid/graphics/Canvas;)V
"""
    value = replace_once(value, draw_anchor, draw_themed, "theme selection before draw")
    path.write_text(value, encoding="utf-8")


def patch_brightness(root: Path) -> None:
    path = root / ACTIVITY
    value = path.read_text(encoding="utf-8")
    value = replace_once(
        value,
        "    const v2, 0x3f47ae14    # 0.78f",
        "    invoke-static {}, Lio/github/jqssun/airplay/ui/DayNightPolicy;->activeBrightnessNow()F\n\n    move-result v2",
        "automatic active brightness",
    )
    value = replace_once(
        value,
        "    const v3, 0x3f47ae14    # 0.78f",
        "    invoke-static {}, Lio/github/jqssun/airplay/ui/DayNightPolicy;->activeBrightnessNow()F\n\n    move-result v3",
        "automatic initial brightness",
    )
    path.write_text(value, encoding="utf-8")


def patch_thermal_policy(root: Path) -> None:
    path = root / ENERGY
    value = path.read_text(encoding="utf-8")
    value = replace_once(value, ".field public static final THERMAL_LIMIT_C:F = 43.0f", ".field public static final THERMAL_LIMIT_C:F = 45.0f", "45 C thermal limit")
    value = replace_once(value, ".field public static final THERMAL_RECOVERY_C:F = 39.0f", ".field public static final THERMAL_RECOVERY_C:F = 41.0f", "41 C recovery")
    value = replace_once(value, "    const/high16 p5, 0x421c0000    # 39.0f", "    const/high16 p5, 0x42240000    # 41.0f", "thermal recovery comparison")
    value = replace_once(value, "    const/high16 p5, 0x422c0000    # 43.0f", "    const/high16 p5, 0x42340000    # 45.0f", "thermal entry comparison")
    path.write_text(value, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: apply_android_1_8_11_daylight.py APKTOOL_DIRECTORY HELPER_DECODE")
    root = Path(sys.argv[1]).resolve()
    helper = Path(sys.argv[2]).resolve()

    old_argv = sys.argv
    try:
        sys.argv = ["apply_android_1_8_10_media.py", str(root), str(helper)]
        apply_1_8_10()
    finally:
        sys.argv = old_argv

    copy_daylight_helper(root)
    patch_dashboard(root)
    patch_brightness(root)
    patch_thermal_policy(root)

    config_file = root / "apktool.yml"
    config = config_file.read_text(encoding="utf-8")
    config = replace_once(config, "  versionCode: 10810", "  versionCode: 10811", "versionCode")
    config = replace_once(config, "  versionName: 1.8.10", "  versionName: 1.8.11", "versionName")
    config_file.write_text(config, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
