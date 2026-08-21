.class public final Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;
.super Ljava/lang/Object;
.source "C3LinkRoadSafety.java"


# static fields
.field private static volatile cameraDistanceMeters:D

.field private static volatile cameraLatitude:D

.field private static volatile cameraLimitKph:D

.field private static volatile cameraLongitude:D

.field private static final paint:Landroid/graphics/Paint;

.field private static volatile speedLimitKph:D


# direct methods
.method static constructor <clinit>()V
    .locals 2

    new-instance v0, Landroid/graphics/Paint;

    const/4 v1, 0x3

    invoke-direct {v0, v1}, Landroid/graphics/Paint;-><init>(I)V

    sput-object v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->paint:Landroid/graphics/Paint;

    invoke-static {}, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->reset()V

    return-void
.end method

.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static drawRadar(Landroid/graphics/Canvas;DDI)V
    .locals 10

    sget-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraDistanceMeters:D

    const-wide/16 v2, 0x0

    cmpg-double v4, v0, v2

    if-ltz v4, :cond_done

    const-wide v2, 0x40b3880000000000L    # 5000.0

    cmpl-double v4, v0, v2

    if-lez v4, :cond_done

    sget-object v0, Lio/github/jqssun/airplay/connectivity/C3MapProjection;->INSTANCE:Lio/github/jqssun/airplay/connectivity/C3MapProjection;

    sget-wide v1, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLongitude:D

    invoke-virtual {v0, v1, v2, p5}, Lio/github/jqssun/airplay/connectivity/C3MapProjection;->longitudeToWorldX(DI)D

    move-result-wide v1

    sub-double/2addr v1, p1

    const-wide/high16 v3, 0x4084000000000000L    # 640.0

    add-double/2addr v1, v3

    double-to-float v7, v1

    sget-wide v1, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLatitude:D

    invoke-virtual {v0, v1, v2, p5}, Lio/github/jqssun/airplay/connectivity/C3MapProjection;->latitudeToWorldY(DI)D

    move-result-wide v0

    sub-double/2addr v0, p3

    const-wide v2, 0x4083600000000000L    # 620.0

    add-double/2addr v0, v2

    double-to-float v8, v0

    const/high16 v0, -0x3c000000    # -512.0f

    cmpg-float v1, v7, v0

    if-ltz v1, :cond_done

    cmpg-float v1, v8, v0

    if-ltz v1, :cond_done

    const/high16 v0, 0x44e00000    # 1792.0f

    cmpl-float v1, v7, v0

    if-gtz v1, :cond_done

    cmpl-float v1, v8, v0

    if-gtz v1, :cond_done

    sget-object v9, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->paint:Landroid/graphics/Paint;

    sget-object v0, Landroid/graphics/Paint$Style;->FILL:Landroid/graphics/Paint$Style;

    invoke-virtual {v9, v0}, Landroid/graphics/Paint;->setStyle(Landroid/graphics/Paint$Style;)V

    const/16 v0, 0xe6

    const/16 v1, 0x28

    const/16 v2, 0x32

    invoke-static {v0, v1, v2}, Landroid/graphics/Color;->rgb(III)I

    move-result v0

    invoke-virtual {v9, v0}, Landroid/graphics/Paint;->setColor(I)V

    const/high16 v0, 0x41900000    # 18.0f

    invoke-virtual {p0, v7, v8, v0, v9}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V

    const/16 v0, 0xff

    invoke-static {v0, v0, v0}, Landroid/graphics/Color;->rgb(III)I

    move-result v0

    invoke-virtual {v9, v0}, Landroid/graphics/Paint;->setColor(I)V

    const/high16 v0, 0x40c00000    # 6.0f

    invoke-virtual {p0, v7, v8, v0, v9}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V

    :cond_done
    return-void
.end method

.method public static drawSpeedLimit(Landroid/graphics/Canvas;)V
    .locals 12

    sget-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->speedLimitKph:D

    const-wide/16 v2, 0x0

    cmpg-double v2, v0, v2

    if-lez v2, :cond_camera

    sget-object v8, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->paint:Landroid/graphics/Paint;

    sget-object v2, Landroid/graphics/Paint$Style;->FILL:Landroid/graphics/Paint$Style;

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setStyle(Landroid/graphics/Paint$Style;)V

    const/16 v2, 0xfa

    invoke-static {v2, v2, v2}, Landroid/graphics/Color;->rgb(III)I

    move-result v2

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setColor(I)V

    const v9, 0x448aa000    # 1109.0f

    const v10, 0x441c4000    # 625.0f

    const/high16 v11, 0x41e00000    # 28.0f

    invoke-virtual {p0, v9, v10, v11, v8}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V

    sget-object v2, Landroid/graphics/Paint$Style;->STROKE:Landroid/graphics/Paint$Style;

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setStyle(Landroid/graphics/Paint$Style;)V

    const/high16 v2, 0x40a00000    # 5.0f

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setStrokeWidth(F)V

    const/16 v2, 0xe6

    const/16 v3, 0x28

    const/16 v4, 0x32

    invoke-static {v2, v3, v4}, Landroid/graphics/Color;->rgb(III)I

    move-result v2

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setColor(I)V

    invoke-virtual {p0, v9, v10, v11, v8}, Landroid/graphics/Canvas;->drawCircle(FFFLandroid/graphics/Paint;)V

    sget-object v2, Landroid/graphics/Paint$Style;->FILL:Landroid/graphics/Paint$Style;

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setStyle(Landroid/graphics/Paint$Style;)V

    const/16 v2, 0x12

    const/16 v3, 0x18

    const/16 v4, 0x20

    invoke-static {v2, v3, v4}, Landroid/graphics/Color;->rgb(III)I

    move-result v2

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setColor(I)V

    const/high16 v2, 0x41a00000    # 20.0f

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setTextSize(F)V

    sget-object v2, Landroid/graphics/Paint$Align;->CENTER:Landroid/graphics/Paint$Align;

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setTextAlign(Landroid/graphics/Paint$Align;)V

    sget-object v2, Landroid/graphics/Typeface;->DEFAULT_BOLD:Landroid/graphics/Typeface;

    invoke-virtual {v8, v2}, Landroid/graphics/Paint;->setTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;

    invoke-static {v0, v1}, Ljava/lang/Math;->round(D)J

    move-result-wide v0

    invoke-static {v0, v1}, Ljava/lang/String;->valueOf(J)Ljava/lang/String;

    move-result-object v0

    const v1, 0x441e8000    # 634.0f

    invoke-virtual {p0, v0, v9, v1, v8}, Landroid/graphics/Canvas;->drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V

    :cond_camera
    sget-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraDistanceMeters:D

    const-wide/16 v2, 0x0

    cmpg-double v2, v0, v2

    if-ltz v2, :cond_done

    const-wide v2, 0x40a7700000000000L    # 3000.0

    cmpl-double v2, v0, v2

    if-lez v2, :cond_done

    new-instance v2, Ljava/lang/StringBuilder;

    const-string v3, "RADAR  "

    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    double-to-int v0, v0

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v0, " m"

    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    sget-object v8, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->paint:Landroid/graphics/Paint;

    sget-object v1, Landroid/graphics/Paint$Style;->FILL:Landroid/graphics/Paint$Style;

    invoke-virtual {v8, v1}, Landroid/graphics/Paint;->setStyle(Landroid/graphics/Paint$Style;)V

    const/16 v1, 0xe6

    const/16 v2, 0x28

    const/16 v3, 0x32

    invoke-static {v1, v2, v3}, Landroid/graphics/Color;->rgb(III)I

    move-result v1

    invoke-virtual {v8, v1}, Landroid/graphics/Paint;->setColor(I)V

    const/high16 v1, 0x41600000    # 14.0f

    invoke-virtual {v8, v1}, Landroid/graphics/Paint;->setTextSize(F)V

    sget-object v1, Landroid/graphics/Paint$Align;->RIGHT:Landroid/graphics/Paint$Align;

    invoke-virtual {v8, v1}, Landroid/graphics/Paint;->setTextAlign(Landroid/graphics/Paint$Align;)V

    sget-object v1, Landroid/graphics/Typeface;->DEFAULT_BOLD:Landroid/graphics/Typeface;

    invoke-virtual {v8, v1}, Landroid/graphics/Paint;->setTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;

    const v1, 0x4499c000    # 1230.0f

    const/high16 v2, 0x442a0000    # 680.0f

    invoke-virtual {p0, v0, v1, v2, v8}, Landroid/graphics/Canvas;->drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V

    :cond_done
    return-void
.end method

.method public static isSpeeding(D)Z
    .locals 6

    sget-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->speedLimitKph:D

    const-wide/16 v2, 0x0

    cmpg-double v2, v0, v2

    const/4 v3, 0x0

    if-lez v2, :cond_done

    const-wide v4, 0x400ccccccccccccdL    # 3.6

    mul-double/2addr p0, v4

    const-wide/high16 v4, 0x4000000000000000L    # 2.0

    add-double/2addr v0, v4

    cmpl-double p0, p0, v0

    if-lez p0, :cond_done

    const/4 v3, 0x1

    :cond_done
    return v3
.end method

.method public static reset()V
    .locals 2

    const-wide/high16 v0, -0x4010000000000000L    # -1.0

    sput-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->speedLimitKph:D

    sput-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLatitude:D

    sput-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLongitude:D

    sput-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraDistanceMeters:D

    sput-wide v0, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLimitKph:D

    return-void
.end method

.method public static update(Lorg/json/JSONObject;)V
    .locals 4

    const-wide/high16 v0, -0x4010000000000000L    # -1.0

    const-string v2, "speedLimitKph"

    invoke-virtual {p0, v2, v0, v1}, Lorg/json/JSONObject;->optDouble(Ljava/lang/String;D)D

    move-result-wide v2

    sput-wide v2, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->speedLimitKph:D

    const-string v2, "cameraLatitude"

    invoke-virtual {p0, v2, v0, v1}, Lorg/json/JSONObject;->optDouble(Ljava/lang/String;D)D

    move-result-wide v2

    sput-wide v2, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLatitude:D

    const-string v2, "cameraLongitude"

    invoke-virtual {p0, v2, v0, v1}, Lorg/json/JSONObject;->optDouble(Ljava/lang/String;D)D

    move-result-wide v2

    sput-wide v2, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLongitude:D

    const-string v2, "cameraDistanceMeters"

    invoke-virtual {p0, v2, v0, v1}, Lorg/json/JSONObject;->optDouble(Ljava/lang/String;D)D

    move-result-wide v2

    sput-wide v2, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraDistanceMeters:D

    const-string v2, "cameraLimitKph"

    invoke-virtual {p0, v2, v0, v1}, Lorg/json/JSONObject;->optDouble(Ljava/lang/String;D)D

    move-result-wide v2

    sput-wide v2, Lio/github/jqssun/airplay/connectivity/C3LinkRoadSafety;->cameraLimitKph:D

    return-void
.end method
