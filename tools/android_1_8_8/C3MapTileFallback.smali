.class public final Lio/github/jqssun/airplay/connectivity/C3MapTileFallback;
.super Ljava/lang/Object;
.source "C3MapTileFallback.java"


# direct methods
.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static find(Lio/github/jqssun/airplay/connectivity/C3MapTileStore;Landroid/util/LruCache;Lio/github/jqssun/airplay/service/MapTileKey;)Landroid/graphics/Bitmap;
    .locals 12

    invoke-virtual {p2}, Lio/github/jqssun/airplay/service/MapTileKey;->getZoom()I

    move-result v0

    const/4 v1, 0x1

    if-lt v0, v1, :c3_none

    new-instance v2, Ljava/lang/StringBuilder;

    const-string v3, "fallback_"

    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p2}, Lio/github/jqssun/airplay/service/MapTileKey;->getId()Ljava/lang/String;

    move-result-object v3

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {p1, v2}, Landroid/util/LruCache;->get(Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v3

    check-cast v3, Landroid/graphics/Bitmap;

    if-eqz v3, :c3_parent

    invoke-virtual {v3}, Landroid/graphics/Bitmap;->isRecycled()Z

    move-result v4

    if-nez v4, :c3_parent

    return-object v3

    :c3_parent
    add-int/lit8 v0, v0, -0x1

    invoke-virtual {p2}, Lio/github/jqssun/airplay/service/MapTileKey;->getX()I

    move-result v3

    shr-int/lit8 v4, v3, 0x1

    invoke-virtual {p2}, Lio/github/jqssun/airplay/service/MapTileKey;->getY()I

    move-result v5

    shr-int/lit8 v6, v5, 0x1

    new-instance v7, Lio/github/jqssun/airplay/service/MapTileKey;

    invoke-direct {v7, v0, v4, v6}, Lio/github/jqssun/airplay/service/MapTileKey;-><init>(III)V

    invoke-virtual {p0, v7}, Lio/github/jqssun/airplay/connectivity/C3MapTileStore;->tile(Lio/github/jqssun/airplay/service/MapTileKey;)Landroid/graphics/Bitmap;

    move-result-object v0

    if-eqz v0, :c3_none

    invoke-virtual {v0}, Landroid/graphics/Bitmap;->isRecycled()Z

    move-result v4

    if-nez v4, :c3_none

    :try_start_0
    invoke-virtual {v0}, Landroid/graphics/Bitmap;->getWidth()I

    move-result v4

    div-int/lit8 v4, v4, 0x2

    invoke-virtual {v0}, Landroid/graphics/Bitmap;->getHeight()I

    move-result v6

    div-int/lit8 v6, v6, 0x2

    if-lt v4, v1, :c3_none

    if-lt v6, v1, :c3_none

    and-int/lit8 v3, v3, 0x1

    mul-int v8, v3, v4

    and-int/lit8 v3, v5, 0x1

    mul-int v9, v3, v6

    move-object v7, v0

    move v10, v4

    move v11, v6

    invoke-static/range {v7 .. v11}, Landroid/graphics/Bitmap;->createBitmap(Landroid/graphics/Bitmap;IIII)Landroid/graphics/Bitmap;

    move-result-object v0

    invoke-virtual {p1, v2, v0}, Landroid/util/LruCache;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    return-object v0
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0

    :c3_none
    const/4 v0, 0x0

    return-object v0
.end method
