package io.github.jqssun.airplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.jqssun.airplay.connectivity.ConnectionStatus
import io.github.jqssun.airplay.connectivity.C3MapProjection
import io.github.jqssun.airplay.connectivity.C3MapTileStore
import io.github.jqssun.airplay.service.DisplayMode
import io.github.jqssun.airplay.service.MediaState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DashboardView(context: Context) : View(context) {
    interface Actions {
        fun onPrevious()
        fun onPlayPause()
        fun onNext()
        fun onConnectionHelp()
        fun onMapHelp()
        fun onMusicHelp()
        fun onTechnicalSettings()
    }

    var actions: Actions? = null
    private var media = MediaState()
    private var connection = ConnectionStatus(false, false, false, "Preparando rede")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private var splashUntil = SystemClock.uptimeMillis() + 2200L
    private var scaleX = 1f
    private var scaleY = 1f
    private var pressedX = 0f
    private var pressedY = 0f
    private var settingsTriggered = false
    private var mapTileStore: C3MapTileStore? = null
    private var displayedLatitude = Double.NaN
    private var displayedLongitude = Double.NaN
    private var mapBearing = 0f
    private var mapBearingRouteId = ""
    private val mapTileFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(0.88f)
        postConcat(ColorMatrix(floatArrayOf(
            1.07f, 0f, 0f, 0f, -5f,
            0f, 1.07f, 0f, 0f, -5f,
            0f, 0f, 1.07f, 0f, -5f,
            0f, 0f, 0f, 1f, 0f,
        )))
    })
    private val tileInvalidator = { postInvalidateOnAnimation() }
    private val settingsLongPress = Runnable {
        settingsTriggered = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        actions?.onTechnicalSettings()
    }
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, if (media.mode == DisplayMode.STANDBY) 5_000L else 1_000L)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = true
        isClickable = true
        contentDescription = "Central multimídia C3 Media"
        handler.post(ticker)
    }

    fun updateMedia(state: MediaState) {
        media = state
        invalidate()
    }

    fun updateConnection(status: ConnectionStatus) {
        connection = status
        invalidate()
    }

    fun setMapTileStore(store: C3MapTileStore?) {
        if (mapTileStore === store) return
        mapTileStore?.onTileAvailable = null
        mapTileStore = store
        store?.onTileAvailable = tileInvalidator
        invalidate()
    }

    fun triggerStartupAnimation() {
        splashUntil = SystemClock.uptimeMillis() + 2_200L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scaleX = width / DESIGN_W
        scaleY = height / DESIGN_H
        canvas.save()
        canvas.scale(scaleX, scaleY)
        if (SystemClock.uptimeMillis() < splashUntil) {
            drawSplash(canvas)
            postInvalidateDelayed(80L)
        } else if (media.mode == DisplayMode.STANDBY) {
            drawStandby(canvas)
        } else if (media.mode == DisplayMode.MIRROR) {
            drawMirrorChrome(canvas)
        } else if (media.mode == DisplayMode.NAVIGATION) {
            drawNavigation(canvas)
        } else {
            drawBackground(canvas)
            drawRail(canvas)
            drawStatus(canvas)
            when (media.mode) {
                DisplayMode.AUDIO -> drawAudio(canvas)
                DisplayMode.PIN -> drawPin(canvas)
                DisplayMode.ERROR -> drawError(canvas)
                DisplayMode.STARTING -> drawStarting(canvas)
                else -> drawIdle(canvas)
            }
        }
        canvas.restore()
    }

    private fun drawSplash(canvas: Canvas) {
        canvas.drawColor(BG)
        drawChevrons(canvas, 640f, 312f, 90f, WHITE)
        text(canvas, "CITROËN", 640f, 474f, 44f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        text(canvas, "C3 MEDIA", 640f, 522f, 18f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
        paint.color = RED
        canvas.drawRoundRect(568f, 560f, 712f, 565f, 3f, 3f, paint)
    }

    private fun drawStandby(canvas: Canvas) {
        canvas.drawColor(Color.rgb(1, 2, 3))
        drawChevrons(canvas, 640f, 374f, 25f, Color.rgb(22, 24, 28))
        text(canvas, "AGUARDANDO IPHONE", 640f, 440f, 11f, Color.rgb(28, 31, 36), Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
    }

    private fun drawBackground(canvas: Canvas) {
        paint.shader = LinearGradient(0f, 0f, DESIGN_W, DESIGN_H, BG, BG_2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, DESIGN_W, DESIGN_H, paint)
        paint.shader = null
        paint.color = Color.argb(32, 232, 59, 69)
        canvas.drawCircle(1180f, 40f, 320f, paint)
    }

    private fun drawRail(canvas: Canvas) {
        paint.color = RAIL
        canvas.drawRoundRect(20f, 20f, 112f, 780f, 31f, 31f, paint)
        drawChevrons(canvas, 66f, 67f, 29f, WHITE)
        paint.color = DIVIDER
        canvas.drawRoundRect(43f, 122f, 89f, 124f, 1f, 1f, paint)

        drawHomeIcon(canvas, 66f, 210f, media.mode == DisplayMode.IDLE)
        drawMusicIcon(canvas, 66f, 310f, media.mode == DisplayMode.AUDIO)
        drawMapIcon(canvas, 66f, 410f, media.mode == DisplayMode.MIRROR || media.mode == DisplayMode.NAVIGATION)
        drawConnectionIcon(canvas, 66f, 650f, connection.networkReady, "REDE")
        drawConnectionIcon(canvas, 66f, 730f, connection.radioConnected, "RÁDIO")
    }

    private fun drawStatus(canvas: Canvas) {
        text(canvas, "CITROËN C3", 152f, 58f, 17f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, subtitle(), 152f, 84f, 13f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        statusPill(canvas, 884f, 34f, 1018f, 76f, connection.networkReady, connection.networkLabel)
        statusPill(canvas, 1030f, 34f, 1170f, 76f, connection.radioConnected, if (connection.radioConnected) "Rádio ligado" else "Sem rádio")
        text(canvas, clock.format(Date()), 1242f, 65f, 24f, WHITE, Paint.Align.RIGHT, Typeface.DEFAULT_BOLD)
    }

    private fun subtitle(): String = when (media.mode) {
        DisplayMode.AUDIO -> "Música do iPhone"
        DisplayMode.NAVIGATION -> "Navegação pelo GPS do iPhone"
        DisplayMode.PIN -> "Autorização"
        DisplayMode.ERROR -> "Atenção necessária"
        DisplayMode.STARTING -> "Inicializando"
        else -> "Central conectada"
    }

    private fun drawIdle(canvas: Canvas) {
        card(canvas, 146f, 120f, 792f, 748f, 34f, CARD)
        paint.color = Color.argb(34, 232, 59, 69)
        canvas.drawCircle(470f, 292f, 144f, paint)
        drawPhoneIcon(canvas, 470f, 286f)
        text(canvas, "Conecte seu iPhone", 470f, 486f, 40f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        text(canvas, "Use o AirPlay para música ou Espelhar a Tela para o Waze", 470f, 528f, 17f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
        step(canvas, 218f, 606f, "1", "Entre na rede", HotspotLabel())
        step(canvas, 422f, 606f, "2", "Abra a Central", "AirPlay")
        step(canvas, 626f, 606f, "3", "Selecione", "Citroën C3")

        card(canvas, 816f, 120f, 1258f, 420f, 30f, CARD)
        text(canvas, "WAZE", 854f, 172f, 14f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Rotas na tela", 854f, 222f, 31f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Defina a rota no celular e use", 854f, 266f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, "Espelhar a Tela. A navegação", 854f, 292f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, "aparece aqui imediatamente.", 854f, 318f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        drawRoute(canvas, 1055f, 350f)

        card(canvas, 816f, 444f, 1258f, 748f, 30f, CARD)
        text(canvas, "MÚSICA", 854f, 496f, 14f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "YouTube Music", 854f, 546f, 29f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "e Spotify", 854f, 580f, 29f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Toque pelo iPhone. Capa, faixa e", 854f, 626f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, "controles aparecem nesta central.", 854f, 652f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        drawMusicDisc(canvas, 1160f, 628f)
    }

    private fun HotspotLabel(): String = if (connection.hotspotActive) "Citroen-C3" else "mesma Wi-Fi"

    private fun drawAudio(canvas: Canvas) {
        val artRect = RectF(166f, 138f, 656f, 628f)
        drawArtwork(canvas, artRect, media.track.coverArt)
        text(canvas, "TOCANDO AGORA", 706f, 180f, 14f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        val title = media.track.title.ifBlank { "YouTube Music / Spotify" }
        val artist = media.track.artist.ifBlank { "Reproduzindo do iPhone" }
        textFit(canvas, title, 706f, 252f, 510f, 38f, WHITE, Typeface.DEFAULT_BOLD)
        textFit(canvas, artist, 706f, 300f, 510f, 22f, MUTED, Typeface.DEFAULT)
        if (media.track.album.isNotBlank()) {
            textFit(canvas, media.track.album, 706f, 336f, 510f, 16f, MUTED_2, Typeface.DEFAULT)
        }

        val position = currentPosition()
        val duration = media.durationMs
        val fraction = if (duration > 0L) position.toFloat() / duration else 0f
        paint.color = DIVIDER
        canvas.drawRoundRect(706f, 400f, 1216f, 407f, 4f, 4f, paint)
        paint.color = WHITE
        canvas.drawRoundRect(706f, 400f, 706f + 510f * fraction.coerceIn(0f, 1f), 407f, 4f, 4f, paint)
        text(canvas, formatTime(position), 706f, 437f, 14f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, formatTime(duration), 1216f, 437f, 14f, MUTED, Paint.Align.RIGHT, Typeface.DEFAULT)

        mediaButton(canvas, 772f, 545f, 52f, false) { drawPrevious(canvas, 772f, 545f) }
        mediaButton(canvas, 954f, 545f, 68f, true) { if (media.playing) drawPause(canvas, 954f, 545f) else drawPlay(canvas, 954f, 545f) }
        mediaButton(canvas, 1136f, 545f, 52f, false) { drawNext(canvas, 1136f, 545f) }

        card(canvas, 166f, 660f, 1216f, 734f, 24f, CARD)
        statusDot(canvas, 202f, 697f, connection.radioConnected)
        text(canvas, if (connection.radioConnected) "Áudio sendo enviado ao rádio" else "Conecte o Bluetooth do rádio", 222f, 703f, 16f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "O volume do iPhone controla a saída", 1182f, 703f, 14f, MUTED, Paint.Align.RIGHT, Typeface.DEFAULT)
    }

    private fun drawMirrorChrome(canvas: Canvas) {
        // The SurfaceView occupies the left card. Everything drawn here is an
        // independent touch-friendly module, similar to an in-car dashboard.
        paint.color = BG
        canvas.drawRect(0f, 0f, DESIGN_W, 108f, paint)
        canvas.drawRect(0f, 108f, 124f, DESIGN_H, paint)
        canvas.drawRect(902f, 108f, DESIGN_W, DESIGN_H, paint)
        canvas.drawRect(124f, 780f, 902f, DESIGN_H, paint)

        paint.color = RAIL
        canvas.drawRoundRect(18f, 18f, 104f, 782f, 30f, 30f, paint)
        drawChevrons(canvas, 61f, 65f, 27f, WHITE)
        drawConnectionIcon(canvas, 61f, 650f, connection.networkReady, "REDE")
        drawConnectionIcon(canvas, 61f, 730f, connection.radioConnected, "RÁDIO")

        paint.color = RAIL
        canvas.drawRoundRect(122f, 18f, 1262f, 90f, 25f, 25f, paint)
        text(canvas, "PAINEL DE VIAGEM", 150f, 62f, 17f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        statusDot(canvas, 984f, 53f, connection.radioConnected)
        text(canvas, if (connection.radioConnected) "Áudio no rádio" else "Sem rádio", 1004f, 60f, 14f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, clock.format(Date()), 1232f, 62f, 24f, WHITE, Paint.Align.RIGHT, Typeface.DEFAULT_BOLD)

        linePaint.color = Color.argb(135, 255, 255, 255)
        linePaint.strokeWidth = 2f
        canvas.drawRoundRect(124f, 108f, 902f, 780f, 25f, 25f, linePaint)
        paint.color = Color.argb(205, 7, 9, 13)
        canvas.drawRoundRect(146f, 130f, 314f, 176f, 19f, 19f, paint)
        statusDot(canvas, 168f, 153f, true)
        text(canvas, "WAZE AO VIVO", 188f, 160f, 14f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)

        card(canvas, 918f, 108f, 1262f, 780f, 28f, Color.rgb(18, 21, 28))
        text(canvas, "TOCANDO AGORA", 946f, 148f, 13f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        drawArtwork(canvas, RectF(946f, 170f, 1234f, 458f), media.track.coverArt)
        textFit(
            canvas,
            media.track.title.ifBlank { "Música do iPhone" },
            946f,
            506f,
            288f,
            21f,
            WHITE,
            Typeface.DEFAULT_BOLD,
        )
        textFit(
            canvas,
            media.track.artist.ifBlank { "YouTube Music / Spotify" },
            946f,
            542f,
            288f,
            16f,
            MUTED,
            Typeface.DEFAULT,
        )
        val duration = media.durationMs
        val fraction = if (duration > 0L) currentPosition().toFloat() / duration else 0f
        paint.color = DIVIDER
        canvas.drawRoundRect(946f, 580f, 1234f, 587f, 4f, 4f, paint)
        paint.color = WHITE
        canvas.drawRoundRect(946f, 580f, 946f + 288f * fraction.coerceIn(0f, 1f), 587f, 4f, 4f, paint)
        mediaButton(canvas, 976f, 666f, 42f, false) { drawPrevious(canvas, 976f, 666f) }
        mediaButton(canvas, 1090f, 666f, 55f, true) {
            if (media.playing) drawPause(canvas, 1090f, 666f) else drawPlay(canvas, 1090f, 666f)
        }
        mediaButton(canvas, 1204f, 666f, 42f, false) { drawNext(canvas, 1204f, 666f) }
        text(canvas, if (media.energy.thermalLimited) "PROTEÇÃO TÉRMICA" else "CONTROLES DE MÍDIA", 1090f, 754f, 11f, if (media.energy.thermalLimited) AMBER else MUTED_2, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
    }

    private fun drawNavigation(canvas: Canvas) {
        val navigation = media.navigation
        val route = navigation.route
        val fallback = route.firstOrNull()
        val targetLatitude = navigation.latitude.takeIf { it in -90.0..90.0 && (it != 0.0 || navigation.longitude != 0.0) }
            ?: fallback?.latitude
        val targetLongitude = navigation.longitude.takeIf { it in -180.0..180.0 && (navigation.latitude != 0.0 || it != 0.0) }
            ?: fallback?.longitude
        canvas.drawColor(MAP_BACKGROUND)
        if (targetLatitude == null || targetLongitude == null) {
            drawRail(canvas)
            card(canvas, 250f, 280f, 1110f, 520f, 32f, Color.argb(238, 12, 19, 25))
            text(canvas, "Aguardando a rota do iPhone", 680f, 377f, 31f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
            text(canvas, "Abra o C3 Link e escolha um destino", 680f, 428f, 18f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
            return
        }

        if (!displayedLatitude.isFinite() || !displayedLongitude.isFinite() || mapBearingRouteId != navigation.routeId) {
            displayedLatitude = targetLatitude
            displayedLongitude = targetLongitude
            mapBearingRouteId = navigation.routeId
            mapBearing = (C3MapProjection.routeBearingDegrees(targetLatitude, targetLongitude, route)
                ?: navigation.courseDegrees).toFloat()
        } else {
            displayedLatitude += (targetLatitude - displayedLatitude) * POSITION_SMOOTHING
            displayedLongitude += (targetLongitude - displayedLongitude) * POSITION_SMOOTHING
        }

        val routeBearing = C3MapProjection.routeBearingDegrees(targetLatitude, targetLongitude, route)
        val wantedBearing = if (navigation.speedMps >= 3.0 && navigation.courseDegrees.isFinite()) {
            navigation.courseDegrees
        } else {
            routeBearing ?: navigation.courseDegrees
        }
        val delta = C3MapProjection.shortestBearingDelta(mapBearing.toDouble(), wantedBearing).toFloat()
        mapBearing = (mapBearing + delta * BEARING_SMOOTHING + 360f) % 360f

        val zoom = C3MapProjection.zoomForSpeed(navigation.speedMps)
        val centerWorldX = C3MapProjection.longitudeToWorldX(displayedLongitude, zoom)
        val centerWorldY = C3MapProjection.latitudeToWorldY(displayedLatitude, zoom)
        val markerX = 650f
        val markerY = 545f
        val tiles = C3MapProjection.visibleTiles(displayedLatitude, displayedLongitude, zoom, DESIGN_W.toDouble(), DESIGN_H.toDouble())
        mapTileStore?.prefetch(tiles)

        canvas.save()
        canvas.clipRect(112f, 0f, DESIGN_W, DESIGN_H)
        canvas.rotate(-mapBearing, markerX, markerY)
        paint.shader = null
        paint.alpha = 255
        paint.isFilterBitmap = true
        paint.colorFilter = mapTileFilter
        var visibleTileCount = 0
        tiles.forEach { key ->
            val bitmap = mapTileStore?.tile(key) ?: return@forEach
            if (bitmap.isRecycled) return@forEach
            val left = ((key.x * C3MapProjection.TILE_SIZE - centerWorldX) + markerX).toFloat()
            val top = ((key.y * C3MapProjection.TILE_SIZE - centerWorldY) + markerY).toFloat()
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + 257f, top + 257f), paint)
            visibleTileCount++
        }
        paint.colorFilter = null

        // The route is independent from tile availability and always stays on
        // top of the basemap, including during cache/network recovery.
        if (route.size >= 2) {
            path.reset()
            val drawStep = (route.size / MAX_DRAW_ROUTE_POINTS).coerceAtLeast(1)
            route.forEachIndexed { index, point ->
                if (index != 0 && index != route.lastIndex && index % drawStep != 0) return@forEachIndexed
                val x = (C3MapProjection.longitudeToWorldX(point.longitude, zoom) - centerWorldX + markerX).toFloat()
                val y = (C3MapProjection.latitudeToWorldY(point.latitude, zoom) - centerWorldY + markerY).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = ROUTE_SHADOW
            linePaint.strokeWidth = 20f
            linePaint.alpha = 235
            canvas.drawPath(path, linePaint)
            linePaint.color = ROUTE_BLUE
            linePaint.strokeWidth = 13f
            linePaint.alpha = 255
            canvas.drawPath(path, linePaint)
            linePaint.color = Color.argb(165, 196, 239, 255)
            linePaint.strokeWidth = 3f
            canvas.drawPath(path, linePaint)
            linePaint.alpha = 255

            route.lastOrNull()?.let { destination ->
                val x = (C3MapProjection.longitudeToWorldX(destination.longitude, zoom) - centerWorldX + markerX).toFloat()
                val y = (C3MapProjection.latitudeToWorldY(destination.latitude, zoom) - centerWorldY + markerY).toFloat()
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 19f, paint)
                paint.color = RED
                canvas.drawCircle(x, y, 13f, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 5f, paint)
            }
        }
        canvas.restore()

        drawNavigationMarker(canvas, markerX, markerY)
        drawRail(canvas)

        card(canvas, 132f, 18f, 864f, 116f, 27f, Color.argb(238, 8, 17, 24))
        drawManeuverArrow(canvas, 182f, 67f, navigation.maneuver)
        textFit(
            canvas,
            navigation.instruction.ifBlank { "Siga a rota" },
            232f,
            62f,
            596f,
            24f,
            WHITE,
            Typeface.DEFAULT_BOLD,
        )
        text(
            canvas,
            formatNavigationDistance(navigation.stepDistanceMeters),
            232f,
            94f,
            14f,
            ROUTE_LIGHT,
            Paint.Align.LEFT,
            Typeface.DEFAULT_BOLD,
        )

        card(canvas, 888f, 18f, 1262f, 116f, 27f, Color.argb(238, 8, 17, 24))
        text(canvas, formatNavigationDistance(navigation.remainingDistanceMeters), 920f, 59f, 24f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, formatNavigationDuration(navigation.remainingSeconds), 1228f, 59f, 22f, ROUTE_LIGHT, Paint.Align.RIGHT, Typeface.DEFAULT_BOLD)
        textFit(canvas, navigation.destination.ifBlank { "Destino" }, 920f, 91f, 308f, 13f, MUTED, Typeface.DEFAULT)

        if (!navigation.connected) {
            card(canvas, 888f, 130f, 1262f, 174f, 18f, Color.argb(230, 70, 43, 7))
            text(canvas, "RECONECTANDO GPS DO IPHONE", 1075f, 159f, 12f, AMBER, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        } else if (visibleTileCount == 0) {
            card(canvas, 936f, 714f, 1248f, 758f, 18f, Color.argb(220, 8, 17, 24))
            text(canvas, "CARREGANDO RUAS…", 1092f, 743f, 12f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        }
        text(canvas, "© OpenStreetMap  © CARTO", 1250f, 788f, 9f, Color.rgb(64, 76, 84), Paint.Align.RIGHT, Typeface.DEFAULT)

        val stillMoving = kotlin.math.abs(targetLatitude - displayedLatitude) > 0.0000005 ||
            kotlin.math.abs(targetLongitude - displayedLongitude) > 0.0000005 || kotlin.math.abs(delta) > 0.5f
        if (stillMoving) postInvalidateDelayed(40L)
    }

    private fun drawNavigationMarker(canvas: Canvas, x: Float, y: Float) {
        paint.setShadowLayer(8f, 0f, 3f, Color.argb(100, 0, 0, 0))
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(x, y - 29f)
        path.lineTo(x + 23f, y + 23f)
        path.lineTo(x, y + 13f)
        path.lineTo(x - 23f, y + 23f)
        path.close()
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
        paint.color = ROUTE_BLUE
        path.reset()
        path.moveTo(x, y - 21f)
        path.lineTo(x + 15f, y + 15f)
        path.lineTo(x, y + 9f)
        path.lineTo(x - 15f, y + 15f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawManeuverArrow(canvas: Canvas, x: Float, y: Float, maneuver: String) {
        linePaint.color = ROUTE_LIGHT
        linePaint.strokeWidth = 8f
        path.reset()
        when {
            maneuver.contains("left", ignoreCase = true) -> {
                path.moveTo(x + 22f, y + 22f)
                path.lineTo(x + 22f, y - 8f)
                path.lineTo(x - 15f, y - 8f)
                path.moveTo(x - 15f, y - 8f)
                path.lineTo(x - 1f, y - 22f)
                path.moveTo(x - 15f, y - 8f)
                path.lineTo(x - 1f, y + 6f)
            }
            maneuver.contains("right", ignoreCase = true) -> {
                path.moveTo(x - 22f, y + 22f)
                path.lineTo(x - 22f, y - 8f)
                path.lineTo(x + 15f, y - 8f)
                path.moveTo(x + 15f, y - 8f)
                path.lineTo(x + 1f, y - 22f)
                path.moveTo(x + 15f, y - 8f)
                path.lineTo(x + 1f, y + 6f)
            }
            else -> {
                path.moveTo(x, y + 23f)
                path.lineTo(x, y - 22f)
                path.moveTo(x, y - 22f)
                path.lineTo(x - 14f, y - 6f)
                path.moveTo(x, y - 22f)
                path.lineTo(x + 14f, y - 6f)
            }
        }
        canvas.drawPath(path, linePaint)
    }

    private fun formatNavigationDistance(meters: Double): String = when {
        !meters.isFinite() || meters <= 0.0 -> "—"
        meters < 1_000.0 -> "${(meters / 10.0).roundToInt() * 10} m"
        else -> String.format(Locale("pt", "BR"), "%.1f km", meters / 1_000.0)
    }

    private fun formatNavigationDuration(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return "—"
        val minutes = (seconds / 60.0).roundToInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"
    }

    private fun drawStarting(canvas: Canvas) {
        drawChevrons(canvas, 684f, 314f, 70f, WHITE)
        text(canvas, "Preparando sua central", 684f, 452f, 36f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        text(canvas, media.message, 684f, 494f, 17f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
        paint.color = DIVIDER
        canvas.drawRoundRect(508f, 546f, 860f, 552f, 3f, 3f, paint)
        paint.color = RED
        val phase = ((SystemClock.uptimeMillis() / 9L) % 352L).toFloat()
        canvas.drawRoundRect(508f + phase, 546f, min(860f, 588f + phase), 552f, 3f, 3f, paint)
        postInvalidateDelayed(40L)
    }

    private fun drawPin(canvas: Canvas) {
        text(canvas, "Código de conexão", 684f, 248f, 24f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
        text(canvas, media.pin.ifBlank { "— — — —" }, 684f, 390f, 92f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        text(canvas, "Digite este código no iPhone", 684f, 462f, 20f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
    }

    private fun drawError(canvas: Canvas) {
        paint.color = Color.argb(34, 232, 59, 69)
        canvas.drawCircle(684f, 302f, 104f, paint)
        linePaint.color = RED
        linePaint.strokeWidth = 12f
        canvas.drawLine(684f, 248f, 684f, 320f, linePaint)
        canvas.drawCircle(684f, 350f, 7f, paint.apply { color = RED })
        text(canvas, "A central não conseguiu iniciar", 684f, 466f, 32f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        textFit(canvas, media.message, 684f, 510f, 760f, 17f, MUTED, Typeface.DEFAULT, Paint.Align.CENTER)
        text(canvas, "Abra os ajustes técnicos mantendo o logotipo pressionado", 684f, 566f, 15f, MUTED_2, Paint.Align.CENTER, Typeface.DEFAULT)
    }

    private fun statusPill(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, ok: Boolean, label: String) {
        paint.color = if (ok) Color.argb(25, 75, 225, 145) else Color.argb(30, 255, 255, 255)
        canvas.drawRoundRect(l, t, r, b, 18f, 18f, paint)
        statusDot(canvas, l + 18f, (t + b) / 2f, ok)
        textFit(canvas, label, l + 33f, t + 27f, r - l - 45f, 12f, if (ok) WHITE else MUTED, Typeface.DEFAULT_BOLD)
    }

    private fun drawConnectionIcon(canvas: Canvas, x: Float, y: Float, ok: Boolean, label: String) {
        paint.color = if (ok) Color.argb(30, 75, 225, 145) else Color.argb(22, 255, 255, 255)
        canvas.drawCircle(x, y - 8f, 25f, paint)
        linePaint.color = if (ok) GREEN else MUTED
        linePaint.strokeWidth = 4f
        canvas.drawCircle(x, y - 8f, 8f, linePaint)
        if (ok) canvas.drawCircle(x, y - 8f, 3f, paint.apply { color = GREEN })
        text(canvas, label, x, y + 31f, 9f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
    }

    private fun drawHomeIcon(canvas: Canvas, x: Float, y: Float, active: Boolean) {
        iconBackground(canvas, x, y, active)
        linePaint.color = if (active) Color.BLACK else MUTED
        linePaint.strokeWidth = 4f
        path.reset()
        path.moveTo(x - 13f, y)
        path.lineTo(x, y - 12f)
        path.lineTo(x + 13f, y)
        path.moveTo(x - 9f, y - 2f)
        path.lineTo(x - 9f, y + 12f)
        path.lineTo(x + 9f, y + 12f)
        path.lineTo(x + 9f, y - 2f)
        canvas.drawPath(path, linePaint)
    }

    private fun drawMusicIcon(canvas: Canvas, x: Float, y: Float, active: Boolean) {
        iconBackground(canvas, x, y, active)
        linePaint.color = if (active) Color.BLACK else MUTED
        linePaint.strokeWidth = 4f
        canvas.drawLine(x + 7f, y - 15f, x + 7f, y + 7f, linePaint)
        canvas.drawLine(x + 7f, y - 15f, x - 8f, y - 11f, linePaint)
        canvas.drawCircle(x - 9f, y + 10f, 6f, linePaint)
        canvas.drawCircle(x + 1f, y + 7f, 6f, linePaint)
    }

    private fun drawMapIcon(canvas: Canvas, x: Float, y: Float, active: Boolean) {
        iconBackground(canvas, x, y, active)
        linePaint.color = if (active) Color.BLACK else MUTED
        linePaint.strokeWidth = 3f
        path.reset()
        path.moveTo(x - 15f, y - 12f)
        path.lineTo(x - 5f, y - 16f)
        path.lineTo(x + 6f, y - 11f)
        path.lineTo(x + 15f, y - 15f)
        path.lineTo(x + 15f, y + 12f)
        path.lineTo(x + 5f, y + 16f)
        path.lineTo(x - 6f, y + 11f)
        path.lineTo(x - 15f, y + 15f)
        path.close()
        canvas.drawPath(path, linePaint)
    }

    private fun iconBackground(canvas: Canvas, x: Float, y: Float, active: Boolean) {
        paint.color = if (active) WHITE else Color.TRANSPARENT
        canvas.drawCircle(x, y, 30f, paint)
    }

    private fun drawPhoneIcon(canvas: Canvas, x: Float, y: Float) {
        linePaint.color = WHITE
        linePaint.strokeWidth = 7f
        canvas.drawRoundRect(x - 50f, y - 82f, x + 50f, y + 82f, 15f, 15f, linePaint)
        canvas.drawCircle(x, y + 62f, 5f, paint.apply { color = WHITE })
        linePaint.strokeWidth = 5f
        canvas.drawArc(x + 26f, y - 55f, x + 88f, y + 7f, 225f, 90f, false, linePaint)
        canvas.drawArc(x + 42f, y - 39f, x + 72f, y - 9f, 225f, 90f, false, linePaint)
    }

    private fun drawRoute(canvas: Canvas, x: Float, y: Float) {
        linePaint.color = RED
        linePaint.strokeWidth = 7f
        path.reset()
        path.moveTo(x - 118f, y + 24f)
        path.cubicTo(x - 52f, y - 72f, x + 16f, y + 86f, x + 104f, y - 26f)
        canvas.drawPath(path, linePaint)
        canvas.drawCircle(x - 118f, y + 24f, 9f, paint.apply { color = WHITE })
        canvas.drawCircle(x + 104f, y - 26f, 12f, paint.apply { color = RED })
    }

    private fun drawMusicDisc(canvas: Canvas, x: Float, y: Float, radius: Float = 64f) {
        paint.shader = LinearGradient(x - radius, y - radius, x + radius, y + radius, RED, Color.rgb(101, 61, 226), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
        canvas.drawCircle(x, y, radius * 0.36f, paint.apply { color = CARD })
        canvas.drawCircle(x, y, radius * 0.11f, paint.apply { color = WHITE })
    }

    private fun drawArtwork(canvas: Canvas, rect: RectF, bitmap: Bitmap?) {
        paint.color = CARD
        canvas.drawRoundRect(rect, 34f, 34f, paint)
        if (bitmap != null && !bitmap.isRecycled) {
            canvas.save()
            path.reset()
            path.addRoundRect(rect, 34f, 34f, Path.Direction.CW)
            canvas.clipPath(path)
            val size = min(bitmap.width, bitmap.height)
            val left = (bitmap.width - size) / 2
            val top = (bitmap.height - size) / 2
            canvas.drawBitmap(bitmap, android.graphics.Rect(left, top, left + size, top + size), rect, paint)
            canvas.restore()
        } else {
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, Color.rgb(38, 42, 52), Color.rgb(98, 35, 69), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 34f, 34f, paint)
            paint.shader = null
            drawMusicDisc(canvas, rect.centerX(), rect.centerY(), min(64f, min(rect.width(), rect.height()) * 0.38f))
        }
    }

    private inline fun mediaButton(canvas: Canvas, x: Float, y: Float, radius: Float, primary: Boolean, icon: () -> Unit) {
        paint.color = if (primary) WHITE else Color.argb(30, 255, 255, 255)
        canvas.drawCircle(x, y, radius, paint)
        icon()
    }

    private fun drawPrevious(canvas: Canvas, x: Float, y: Float) {
        paint.color = WHITE
        canvas.drawRect(x - 18f, y - 15f, x - 13f, y + 15f, paint)
        path.reset()
        path.moveTo(x + 16f, y - 17f)
        path.lineTo(x - 10f, y)
        path.lineTo(x + 16f, y + 17f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawNext(canvas: Canvas, x: Float, y: Float) {
        paint.color = WHITE
        canvas.drawRect(x + 13f, y - 15f, x + 18f, y + 15f, paint)
        path.reset()
        path.moveTo(x - 16f, y - 17f)
        path.lineTo(x + 10f, y)
        path.lineTo(x - 16f, y + 17f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawPlay(canvas: Canvas, x: Float, y: Float) {
        paint.color = Color.BLACK
        path.reset()
        path.moveTo(x - 10f, y - 18f)
        path.lineTo(x + 19f, y)
        path.lineTo(x - 10f, y + 18f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float) {
        paint.color = Color.BLACK
        canvas.drawRoundRect(x - 14f, y - 18f, x - 5f, y + 18f, 3f, 3f, paint)
        canvas.drawRoundRect(x + 5f, y - 18f, x + 14f, y + 18f, 3f, 3f, paint)
    }

    private fun drawChevrons(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        paint.color = color
        chevron(canvas, x, y - size * 0.34f, size)
        chevron(canvas, x, y + size * 0.34f, size)
    }

    private fun chevron(canvas: Canvas, x: Float, y: Float, size: Float) {
        path.reset()
        path.moveTo(x - size, y - size * 0.18f)
        path.lineTo(x, y - size * 0.70f)
        path.lineTo(x + size, y - size * 0.18f)
        path.lineTo(x + size, y + size * 0.18f)
        path.lineTo(x, y - size * 0.34f)
        path.lineTo(x - size, y + size * 0.18f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun step(canvas: Canvas, x: Float, y: Float, number: String, top: String, bottom: String) {
        paint.color = Color.argb(30, 255, 255, 255)
        canvas.drawCircle(x, y, 22f, paint)
        text(canvas, number, x, y + 6f, 15f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
        text(canvas, top, x, y + 50f, 13f, MUTED, Paint.Align.CENTER, Typeface.DEFAULT)
        text(canvas, bottom, x, y + 73f, 14f, WHITE, Paint.Align.CENTER, Typeface.DEFAULT_BOLD)
    }

    private fun card(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(l, t, r, b, radius, radius, paint)
    }

    private fun statusDot(canvas: Canvas, x: Float, y: Float, ok: Boolean) {
        paint.color = if (ok) GREEN else AMBER
        canvas.drawCircle(x, y, 6f, paint)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
        typeface: android.graphics.Typeface,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = readableTextSize(size)
        paint.textAlign = align
        paint.typeface = typeface
        canvas.drawText(value, x, y, paint)
    }

    private fun textFit(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        size: Float,
        color: Int,
        typeface: android.graphics.Typeface,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        val renderedSize = readableTextSize(size)
        paint.textSize = renderedSize
        paint.typeface = typeface
        val count = paint.breakText(value, true, maxWidth, null)
        val shown = if (count < value.length && count > 2) value.take(count - 1) + "…" else value
        textExact(canvas, shown, x, y, renderedSize, color, align, typeface)
    }

    private fun readableTextSize(size: Float): Float = when {
        size <= 18f -> size * 1.20f
        size <= 24f -> size * 1.12f
        else -> size * 1.06f
    }

    private fun textExact(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align,
        typeface: android.graphics.Typeface,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = size
        paint.textAlign = align
        paint.typeface = typeface
        canvas.drawText(value, x, y, paint)
    }

    private fun currentPosition(): Long {
        if (!media.playing || media.durationMs <= 0L) return media.positionMs
        return media.positionMs.coerceAtMost(media.durationMs)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val total = ms / 1000L
        return "%d:%02d".format(Locale.US, total / 60L, total % 60L)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / scaleX
        val y = event.y / scaleY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedX = x
                pressedY = y
                settingsTriggered = false
                if (x <= 116f && y <= 128f) handler.postDelayed(settingsLongPress, 1800L)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (max(kotlin.math.abs(x - pressedX), kotlin.math.abs(y - pressedY)) > 24f) {
                    handler.removeCallbacks(settingsLongPress)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(settingsLongPress)
                if (!settingsTriggered && media.mode == DisplayMode.AUDIO) {
                    when {
                        distance(x, y, 772f, 545f) < 82f -> touchAction { actions?.onPrevious() }
                        distance(x, y, 954f, 545f) < 98f -> touchAction { actions?.onPlayPause() }
                        distance(x, y, 1136f, 545f) < 82f -> touchAction { actions?.onNext() }
                    }
                } else if (!settingsTriggered && media.mode == DisplayMode.MIRROR) {
                    when {
                        distance(x, y, 976f, 666f) < 58f -> touchAction { actions?.onPrevious() }
                        distance(x, y, 1090f, 666f) < 70f -> touchAction { actions?.onPlayPause() }
                        distance(x, y, 1204f, 666f) < 58f -> touchAction { actions?.onNext() }
                        x in 124f..902f && y in 108f..780f -> touchAction { actions?.onMapHelp() }
                    }
                } else if (!settingsTriggered && media.mode == DisplayMode.NAVIGATION) {
                    if (x in 112f..1280f && y in 116f..800f) touchAction { actions?.onMapHelp() }
                } else if (!settingsTriggered && media.mode == DisplayMode.IDLE) {
                    when {
                        x in 146f..792f && y in 120f..748f -> touchAction { actions?.onConnectionHelp() }
                        x in 816f..1258f && y in 120f..420f -> touchAction { actions?.onMapHelp() }
                        x in 816f..1258f && y in 444f..748f -> touchAction { actions?.onMusicHelp() }
                    }
                }
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(settingsLongPress)
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))

    private inline fun touchAction(action: () -> Unit) {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }

    override fun onDetachedFromWindow() {
        mapTileStore?.onTileAvailable = null
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DESIGN_W = 1280f
        private const val DESIGN_H = 800f
        private val BG = Color.rgb(7, 9, 13)
        private val BG_2 = Color.rgb(15, 18, 25)
        private val RAIL = Color.rgb(20, 23, 30)
        private val CARD = Color.rgb(24, 28, 36)
        private val DIVIDER = Color.rgb(54, 59, 69)
        private val WHITE = Color.rgb(242, 245, 249)
        private val MUTED = Color.rgb(162, 169, 180)
        private val MUTED_2 = Color.rgb(111, 119, 132)
        private val RED = Color.rgb(232, 59, 69)
        private val GREEN = Color.rgb(75, 225, 145)
        private val AMBER = Color.rgb(245, 178, 66)
        private val MAP_BACKGROUND = Color.rgb(229, 237, 240)
        private val ROUTE_BLUE = Color.rgb(7, 169, 239)
        private val ROUTE_LIGHT = Color.rgb(111, 215, 255)
        private val ROUTE_SHADOW = Color.rgb(8, 56, 83)
        private const val POSITION_SMOOTHING = 0.32
        private const val BEARING_SMOOTHING = 0.22f
        private const val MAX_DRAW_ROUTE_POINTS = 2_000
    }
}
