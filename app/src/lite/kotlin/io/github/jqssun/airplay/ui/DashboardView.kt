package io.github.jqssun.airplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.jqssun.airplay.connectivity.ConnectionStatus
import io.github.jqssun.airplay.service.DisplayMode
import io.github.jqssun.airplay.service.MediaState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class DashboardView(context: Context) : View(context) {
    interface Actions {
        fun onConnectionHelp()
        fun onMirrorHelp()
        fun onMusicHelp()
        fun onTechnicalSettings()
        fun onCloseApp()
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

    fun triggerStartupAnimation() {
        splashUntil = SystemClock.uptimeMillis() + 2_200L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        applyAutomaticTheme()
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
            drawMirrorOverlay(canvas)
        } else {
            drawBackground(canvas)
            if (media.mode == DisplayMode.AUDIO) {
                drawAudio(canvas)
            } else {
                drawRail(canvas)
                drawStatus(canvas)
                when (media.mode) {
                    DisplayMode.PIN -> drawPin(canvas)
                    DisplayMode.ERROR -> drawError(canvas)
                    DisplayMode.STARTING -> drawStarting(canvas)
                    else -> drawIdle(canvas)
                }
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
        drawMirrorIcon(canvas, 66f, 410f, media.mode == DisplayMode.MIRROR)
        drawConnectionIcon(canvas, 66f, 650f, connection.networkReady, "REDE")
        drawConnectionIcon(canvas, 66f, 730f, true, "AUX")
    }

    private fun drawStatus(canvas: Canvas) {
        text(canvas, "CITROËN C3", 152f, 58f, 17f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, subtitle(), 152f, 84f, 13f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        statusPill(canvas, 884f, 34f, 1018f, 76f, connection.networkReady, connection.networkLabel)
        statusPill(canvas, 1030f, 34f, 1170f, 76f, true, "Cabo AUX")
        text(canvas, clock.format(Date()), 1242f, 65f, 24f, WHITE, Paint.Align.RIGHT, Typeface.DEFAULT_BOLD)
    }

    private fun subtitle(): String = when (media.mode) {
        DisplayMode.AUDIO -> "Música do iPhone"
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
        text(canvas, "ESPELHAMENTO", 854f, 172f, 14f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Tela do iPhone", 854f, 222f, 31f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Abra qualquer aplicativo e use", 854f, 266f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, "Espelhar a Tela. A imagem gira", 854f, 292f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, "e se ajusta sem deformação.", 854f, 318f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        drawRoute(canvas, 1055f, 350f)

        card(canvas, 816f, 444f, 1258f, 748f, 30f, CARD)
        text(canvas, "MÚSICA", 854f, 496f, 14f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "YouTube Music", 854f, 546f, 29f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "e Spotify", 854f, 580f, 29f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Toque pelo iPhone. Capa, faixa e", 854f, 626f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, "metadados aparecem nesta central.", 854f, 652f, 16f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        drawMusicDisc(canvas, 1160f, 628f)
    }

    private fun HotspotLabel(): String = if (connection.hotspotActive) "Citroen-C3" else "mesma Wi-Fi"

    private fun drawAudio(canvas: Canvas) {
        text(canvas, "CITROËN C3", 54f, 64f, 18f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Música do iPhone", 54f, 92f, 13f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        drawCloseButton(canvas)

        drawRotatingArtwork(canvas, 342f, 410f, 236f, media.track.coverArt)
        text(canvas, "TOCANDO AGORA", 650f, 206f, 14f, RED, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        val title = media.track.title.ifBlank { "YouTube Music / Spotify" }
        val artist = media.track.artist.ifBlank { "Reproduzindo do iPhone" }
        textFit(canvas, title, 650f, 290f, 548f, 42f, WHITE, Typeface.DEFAULT_BOLD)
        textFit(canvas, artist, 650f, 346f, 548f, 23f, MUTED, Typeface.DEFAULT)
        if (media.track.album.isNotBlank()) {
            textFit(canvas, media.track.album, 650f, 388f, 548f, 17f, MUTED_2, Typeface.DEFAULT)
        }

        val position = currentPosition()
        val duration = media.durationMs
        val fraction = if (duration > 0L) position.toFloat() / duration else 0f
        paint.color = DIVIDER
        canvas.drawRoundRect(650f, 492f, 1198f, 502f, 5f, 5f, paint)
        paint.color = RED
        canvas.drawRoundRect(650f, 492f, 650f + 548f * fraction.coerceIn(0f, 1f), 502f, 5f, 5f, paint)
        text(canvas, formatTime(position), 650f, 544f, 15f, MUTED, Paint.Align.LEFT, Typeface.DEFAULT)
        text(canvas, formatTime(duration), 1198f, 544f, 15f, MUTED, Paint.Align.RIGHT, Typeface.DEFAULT)

        card(canvas, 650f, 618f, 1198f, 704f, 24f, CARD)
        statusDot(canvas, 686f, 661f, true)
        text(canvas, "Áudio pelo cabo auxiliar", 708f, 668f, 17f, WHITE, Paint.Align.LEFT, Typeface.DEFAULT_BOLD)
        text(canvas, "Controle pelo iPhone", 1166f, 668f, 14f, MUTED, Paint.Align.RIGHT, Typeface.DEFAULT)
    }

    private fun drawMirrorOverlay(canvas: Canvas) {
        // Mirroring is a single full-screen module. This layer stays transparent
        // so VideoPipeline can show the iPhone proportionally over all 1280x800.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawCloseButton(canvas)
    }

    private fun drawCloseButton(canvas: Canvas) {
        paint.color = if (DayNightPolicy.isDaytimeNow()) Color.argb(220, 255, 255, 255)
            else Color.argb(220, 12, 15, 20)
        canvas.drawRoundRect(1200f, 22f, 1262f, 84f, 22f, 22f, paint)
        linePaint.color = RED
        linePaint.strokeWidth = 5f
        canvas.drawLine(1219f, 41f, 1243f, 65f, linePaint)
        canvas.drawLine(1243f, 41f, 1219f, 65f, linePaint)
    }

    private fun drawRotatingArtwork(canvas: Canvas, cx: Float, cy: Float, radius: Float, bitmap: Bitmap?) {
        val rotation = if (media.playing) (SystemClock.uptimeMillis() % 18_000L) * 360f / 18_000f else 0f
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        paint.shader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            Color.rgb(8, 10, 14),
            Color.rgb(55, 60, 70),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        if (bitmap != null && !bitmap.isRecycled) {
            canvas.save()
            path.reset()
            path.addCircle(cx, cy, radius - 18f, Path.Direction.CW)
            canvas.clipPath(path)
            val size = min(bitmap.width, bitmap.height)
            val left = (bitmap.width - size) / 2
            val top = (bitmap.height - size) / 2
            val destination = RectF(
                cx - radius + 18f,
                cy - radius + 18f,
                cx + radius - 18f,
                cy + radius - 18f,
            )
            canvas.drawBitmap(
                bitmap,
                android.graphics.Rect(left, top, left + size, top + size),
                destination,
                paint,
            )
            canvas.restore()
        } else {
            drawMusicDisc(canvas, cx, cy, radius - 24f)
        }
        paint.color = Color.argb(150, 8, 10, 14)
        canvas.drawCircle(cx, cy, 42f, paint)
        paint.color = RED
        canvas.drawCircle(cx, cy, 13f, paint)
        canvas.restore()
        if (media.playing) postInvalidateDelayed(50L)
    }

    private fun applyAutomaticTheme() {
        val palette = if (DayNightPolicy.isDaytimeNow()) DAY_PALETTE else NIGHT_PALETTE
        BG = palette[0]
        BG_2 = palette[1]
        RAIL = palette[2]
        CARD = palette[3]
        DIVIDER = palette[4]
        WHITE = palette[5]
        MUTED = palette[6]
        MUTED_2 = palette[7]
        GREEN = palette[8]
        AMBER = palette[9]
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

    private fun drawMirrorIcon(canvas: Canvas, x: Float, y: Float, active: Boolean) {
        iconBackground(canvas, x, y, active)
        linePaint.color = if (active) Color.BLACK else MUTED
        linePaint.strokeWidth = 3f
        canvas.drawRoundRect(x - 17f, y - 12f, x + 17f, y + 10f, 3f, 3f, linePaint)
        canvas.drawLine(x - 7f, y + 16f, x + 7f, y + 16f, linePaint)
        canvas.drawLine(x, y + 10f, x, y + 16f, linePaint)
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
                if (!settingsTriggered && x in 1190f..1275f && y in 10f..96f &&
                    (media.mode == DisplayMode.MIRROR || media.mode == DisplayMode.AUDIO)
                ) {
                    touchAction { actions?.onCloseApp() }
                } else if (!settingsTriggered && media.mode == DisplayMode.MIRROR) {
                    when {
                        x in 0f..DESIGN_W && y in 0f..DESIGN_H -> touchAction { actions?.onMirrorHelp() }
                    }
                } else if (!settingsTriggered && media.mode == DisplayMode.IDLE) {
                    when {
                        x in 146f..792f && y in 120f..748f -> touchAction { actions?.onConnectionHelp() }
                        x in 816f..1258f && y in 120f..420f -> touchAction { actions?.onMirrorHelp() }
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
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DESIGN_W = 1280f
        private const val DESIGN_H = 800f
        private var BG = Color.rgb(7, 9, 13)
        private var BG_2 = Color.rgb(15, 18, 25)
        private var RAIL = Color.rgb(20, 23, 30)
        private var CARD = Color.rgb(24, 28, 36)
        private var DIVIDER = Color.rgb(54, 59, 69)
        private var WHITE = Color.rgb(242, 245, 249)
        private var MUTED = Color.rgb(162, 169, 180)
        private var MUTED_2 = Color.rgb(111, 119, 132)
        private val RED = Color.rgb(232, 59, 69)
        private var GREEN = Color.rgb(75, 225, 145)
        private var AMBER = Color.rgb(245, 178, 66)
        private val NIGHT_PALETTE = intArrayOf(
            Color.rgb(7, 9, 13), Color.rgb(15, 18, 25), Color.rgb(20, 23, 30),
            Color.rgb(24, 28, 36), Color.rgb(54, 59, 69), Color.rgb(242, 245, 249),
            Color.rgb(162, 169, 180), Color.rgb(111, 119, 132), Color.rgb(75, 225, 145),
            Color.rgb(245, 178, 66),
        )
        private val DAY_PALETTE = intArrayOf(
            Color.rgb(232, 237, 244), Color.rgb(211, 220, 231), Color.rgb(250, 251, 253),
            Color.rgb(255, 255, 255), Color.rgb(171, 182, 197), Color.rgb(18, 26, 38),
            Color.rgb(57, 69, 86), Color.rgb(83, 97, 116), Color.rgb(24, 137, 83),
            Color.rgb(176, 105, 0),
        )
    }
}
