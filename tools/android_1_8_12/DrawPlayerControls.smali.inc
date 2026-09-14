.method private final drawPlayerControls(Landroid/graphics/Canvas;)V
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

    sget v1, Lio/github/jqssun/airplay/ui/DashboardView;->WHITE:I
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setColor(I)V
    const/high16 v1, 0x42100000    # 36.0f
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setTextSize(F)V
    sget-object v1, Landroid/graphics/Paint$Align;->CENTER:Landroid/graphics/Paint$Align;
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setTextAlign(Landroid/graphics/Paint$Align;)V
    sget-object v1, Landroid/graphics/Typeface;->DEFAULT_BOLD:Landroid/graphics/Typeface;
    invoke-virtual {v0, v1}, Landroid/graphics/Paint;->setTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;

    const-string v1, "|<"
    const v2, 0x44458000           # 790.0f
    const v3, 0x440b8000           # 558.0f
    invoke-virtual {p1, v1, v2, v3, v0}, Landroid/graphics/Canvas;->drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V

    iget-object v1, p0, Lio/github/jqssun/airplay/ui/DashboardView;->media:Lio/github/jqssun/airplay/service/MediaState;
    invoke-virtual {v1}, Lio/github/jqssun/airplay/service/MediaState;->getPlaying()Z
    move-result v1
    if-eqz v1, :c3_control_play_icon
    const-string v1, "||"
    goto :c3_control_center_icon
    :c3_control_play_icon
    const-string v1, ">"
    :c3_control_center_icon
    const/high16 v2, 0x44700000    # 960.0f
    invoke-virtual {p1, v1, v2, v3, v0}, Landroid/graphics/Canvas;->drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V

    const-string v1, ">|"
    const v2, 0x448d4000           # 1130.0f
    invoke-virtual {p1, v1, v2, v3, v0}, Landroid/graphics/Canvas;->drawText(Ljava/lang/String;FFLandroid/graphics/Paint;)V
    return-void
.end method

