.class public final Lio/github/jqssun/airplay/ui/DayNightPolicy;
.super Ljava/lang/Object;
.source "DayNightPolicy.kt"


# static fields
.field public static final DAY_BRIGHTNESS:F = 1.0f

.field public static final DAY_START_HOUR:I = 0x7

.field public static final NIGHT_BRIGHTNESS:F = 0.78f

.field public static final NIGHT_START_HOUR:I = 0x13


# direct methods
.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static activeBrightnessNow()F
    .locals 1

    invoke-static {}, Lio/github/jqssun/airplay/ui/DayNightPolicy;->isDaytimeNow()Z

    move-result v0

    if-eqz v0, :night

    const/high16 v0, 0x3f800000    # 1.0f

    return v0

    :night
    const v0, 0x3f47ae14    # 0.78f

    return v0
.end method

.method public static isDaytime(I)Z
    .locals 1

    const/4 v0, 0x7

    if-lt p0, v0, :not_daytime

    const/16 v0, 0x13

    if-ge p0, v0, :not_daytime

    const/4 v0, 0x1

    return v0

    :not_daytime
    const/4 v0, 0x0

    return v0
.end method

.method public static isDaytimeNow()Z
    .locals 2

    invoke-static {}, Ljava/util/Calendar;->getInstance()Ljava/util/Calendar;

    move-result-object v0

    const/16 v1, 0xb

    invoke-virtual {v0, v1}, Ljava/util/Calendar;->get(I)I

    move-result v0

    invoke-static {v0}, Lio/github/jqssun/airplay/ui/DayNightPolicy;->isDaytime(I)Z

    move-result v0

    return v0
.end method
