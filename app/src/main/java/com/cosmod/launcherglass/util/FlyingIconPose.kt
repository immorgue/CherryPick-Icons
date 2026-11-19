package com.cosmod.launcherglass.util

/** Converge to the OEM pose before its animated surface hands off to the workspace icon. */
internal object FlyingIconPose {
    fun openingWindowOffsetY(
        sourceRow: Float?, progress: Float, iconSize: Float, roomAbove: Float, roomBelow: Float
    ): Float {
        if (sourceRow == null) return 0f
        // Normalized icon centers range from the top workspace row to the dock.
        val position = ((sourceRow + .67f) / (.82f + .67f)).coerceIn(0f, 1f)
        val offset = iconSize * (-.27f + (.27f + .25f) * position)
        val transition = smooth(progress / .12f) * (1f - smooth((progress - .13f) / .68f))
        return (offset * transition).coerceIn(-roomAbove.coerceAtLeast(0f), roomBelow.coerceAtLeast(0f))
    }

    fun windowOffsetY(openingOffset: Float, closingOffset: Float, openingMix: Float): Float =
        closingOffset + (openingOffset - closingOffset) * openingMix

    fun closingWindowOffsetY(sourceRow: Float, progress: Float, iconSize: Float, roomBelow: Float): Float {
        val dockProximity = smooth((sourceRow - .27f) / .55f)
        val offset = iconSize * .20f * dockProximity
        val transition = smooth(progress / .30f) * (1f - smooth((progress - .28f) / .53f))
        return (offset * transition).coerceAtMost(roomBelow.coerceAtLeast(0f))
    }

    fun windowFollowWeight(progress: Float): Float = smooth((progress - .015f) / .205f)

    fun launchOriginWeight(progress: Float): Float = 1f - smooth(progress / .32f)

    fun openingGrowth(progress: Float, openingMix: Float): Float {
        val catchUp = smooth(progress / .48f)
        return progress * (1f - .50f * openingMix * (1f - catchUp))
    }

    fun growthScale(nativeProgress: Float, displayedProgress: Float, height: Float, iconHeight: Float): Float {
        if (nativeProgress <= 0f || displayedProgress == nativeProgress || height <= iconHeight) return 1f
        val displayedHeight = iconHeight + (height - iconHeight) * displayedProgress / nativeProgress
        return displayedHeight / height
    }

    fun windowReveal(nativeAlpha: Float, progress: Float): Float {
        val reveal = smooth((progress - .025f) / .27f)
        // ColorOS can drop its fade to zero before the final small frames.
        // Carry just that tail, then respect native visibility at larger sizes.
        val tail = .18f * (1f - smooth((progress - .23f) / .17f))
        val alpha = nativeAlpha.coerceIn(0f, 1f)
        val available = .80f * alpha + .20f * smooth(alpha / .30f)
        return reveal * (tail + (1f - tail) * available)
    }

    fun iconReveal(windowAlpha: Float): Float =
        1f - smooth((windowAlpha - .50f) / .50f)

    fun upperIconFollow(row: Float): Float = smooth((-row - .12f) / .48f)

    fun workspaceBlur(nativeBlur: Float, progress: Float): Float =
        minOf(nativeBlur, smooth((progress - .04f) / .50f))

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
