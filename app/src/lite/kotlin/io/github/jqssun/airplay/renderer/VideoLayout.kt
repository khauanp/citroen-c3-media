package io.github.jqssun.airplay.renderer

/** Geometry used to render an AirPlay frame without stretching it. */
data class VideoLayout(
    val scaleX: Float,
    val scaleY: Float,
    val rotateClockwise: Boolean,
)

object VideoLayoutCalculator {
    fun calculate(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        autoRotatePortrait: Boolean = true,
    ): VideoLayout {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return VideoLayout(1f, 1f, false)
        }

        val targetIsLandscape = targetWidth >= targetHeight
        val sourceIsPortrait = sourceHeight > sourceWidth
        val rotate = autoRotatePortrait && targetIsLandscape && sourceIsPortrait
        val displayWidth = if (rotate) sourceHeight else sourceWidth
        val displayHeight = if (rotate) sourceWidth else sourceHeight
        val sourceAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()

        return if (sourceAspect > targetAspect) {
            VideoLayout(1f, targetAspect / sourceAspect, rotate)
        } else {
            VideoLayout(sourceAspect / targetAspect, 1f, rotate)
        }
    }
}
