package com.openminis.app.service

/** Physical screen coordinates, independent of layout direction and rotation. */
internal object OverlayPlacement {
    data class Point(val x: Int, val y: Int)
    data class SafeArea(
        val screenWidth: Int, val screenHeight: Int,
        val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0,
        val padding: Int = 0,
    ) {
        val availableWidth: Int get() = (screenWidth - left - right - 2 * padding).coerceAtLeast(1)
        val availableHeight: Int get() = (screenHeight - top - bottom - 2 * padding).coerceAtLeast(1)

        fun clamp(point: Point, width: Int, height: Int): Point {
            val minX = (left + padding).coerceAtMost((screenWidth - width).coerceAtLeast(0))
            val minY = (top + padding).coerceAtMost((screenHeight - height).coerceAtLeast(0))
            val maxX = (screenWidth - right - padding - width).coerceAtLeast(minX)
            val maxY = (screenHeight - bottom - padding - height).coerceAtLeast(minY)
            return Point(point.x.coerceIn(minX, maxX), point.y.coerceIn(minY, maxY))
        }

        fun initial(width: Int, height: Int): Point = clamp(Point(0, screenHeight), width, height)
    }
}
