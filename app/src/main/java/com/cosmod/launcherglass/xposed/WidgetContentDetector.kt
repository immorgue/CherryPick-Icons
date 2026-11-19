package com.cosmod.launcherglass.xposed

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.AnalogClock
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.TextClock
import com.cosmod.launcherglass.util.GlassLogger

internal enum class WidgetMaterial { PHOTO, GLASS, NATIVE }

/** Provider-independent rules. Never tint or fade a widget's controls as photo content. */
internal object WidgetContentRules {
    fun material(knownPhoto: Boolean, fullImage: Boolean, structured: Boolean,
        separateSurface: Boolean, unsupportedBackground: Boolean): WidgetMaterial = when {
        separateSurface -> WidgetMaterial.NATIVE
        knownPhoto || (fullImage && !structured) -> WidgetMaterial.PHOTO
        unsupportedBackground -> WidgetMaterial.NATIVE
        else -> WidgetMaterial.GLASS
    }
}

internal object WidgetContentDetector {
    data class Backdrop(val view: View, val layer: WidgetBitmapBackdrop.Layer)
    data class Result(val material: WidgetMaterial, val kind: WidgetKind, val backgrounds: List<Backdrop> = emptyList())

    fun inspect(root: View, knownPhoto: Boolean, role: WidgetKind, providerClass: String?,
        suppressed: Map<Drawable, WidgetBitmapBackdrop.Layer>): Result {
        if (knownPhoto) return Result(WidgetMaterial.PHOTO, WidgetKind.PHOTO)
        val scan = Scan(root.width, root.height)
        scan.visit(root, 0f, 0f, root.javaClass.name.startsWith("org.hapjs.card."))
        val kind = if (role == WidgetKind.OTHER && scan.clock) WidgetKind.CLOCK else role

        // A clock or forecast can itself be rendered to a single raster.
        // The provider's role disambiguates that from a full-bleed photograph.
        val structured = scan.structured || kind != WidgetKind.OTHER ||
            structuredRoles.any { providerClass?.contains(it, ignoreCase = true) == true }
        val backgrounds = scan.backgrounds.mapNotNull { (view, engine) ->
            val drawable = view.background ?: return@mapNotNull null
            val layer = suppressed[drawable] ?: when {
                canReplaceFlatBackground(view, drawable, kind == WidgetKind.WEATHER) -> {
                    val color = when (drawable) {
                        is ColorDrawable -> drawable.color
                        is GradientDrawable -> drawable.color?.defaultColor ?: Color.TRANSPARENT
                        else -> Color.TRANSPARENT
                    }
                    WidgetBitmapBackdrop.Layer(null, Color.alpha(color) >= 200 && Color.luminance(color) > .60f)
                }
                engine && !drawable.isStateful && drawable.callback === view ->
                    WidgetBitmapBackdrop.inspect(drawable, replaceWeatherArt = kind == WidgetKind.WEATHER)
                else -> null
            }
            layer?.let { Backdrop(view, it) }
        }
        val customBackground = backgrounds.isEmpty() && scan.backgrounds.any { (view, _) -> isOpaque(view) }
        if (backgrounds.isEmpty() && root.javaClass.name.startsWith("org.hapjs.card.")) {
            GlassLogger.animationLimited("WidgetBackdrop", "unresolved/$providerClass", 50) {
                "Unresolved $providerClass backgrounds: ${scan.backgrounds.joinToString { it.first.background?.javaClass?.name.orEmpty() }}"
            }
        }
        val material = WidgetContentRules.material(false, scan.fullImage, structured, scan.separateSurface, customBackground)
        return Result(material, if (material == WidgetMaterial.PHOTO) WidgetKind.PHOTO else kind,
            if (material == WidgetMaterial.GLASS) backgrounds else emptyList())
    }

    private val structuredRoles = listOf("clock", "weather", "forecast", "calendar", "battery")

    private class Scan(private val width: Int, private val height: Int) {
        var fullImage = false
        var structured = false
        var clock = false
        var separateSurface = false
        val backgrounds = ArrayList<Pair<View, Boolean>>()
        private var visited = 0

        fun visit(view: View, left: Float, top: Float, engineRoot: Boolean) {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return
            if (++visited > 96) { structured = true; return }
            val coveredWidth = (minOf(width.toFloat(), left + view.width) - maxOf(0f, left)).coerceAtLeast(0f)
            val coveredHeight = (minOf(height.toFloat(), top + view.height) - maxOf(0f, top)).coerceAtLeast(0f)
            if (coveredWidth == 0f || coveredHeight == 0f) return
            val coversWidget = coveredWidth >= width * .95f && coveredHeight >= height * .95f
            val backdrop = view.background
            if (coversWidget && backdrop != null && backgrounds.size < 12 &&
                (visited == 1 || view.id == android.R.id.background || engineRoot)) {
                // Quick App cards can have a full-size decorative sibling at
                // the same level as their live clock, forecast or controls.
                backgrounds.add(view to engineRoot)
            }

            when (view) {
                is SurfaceView, is TextureView, is WebView -> { separateSurface = true; return }
                is TextClock, is AnalogClock -> { clock = true; structured = true }
                is TextView -> if (view.text.isNotBlank()) structured = true
                is AdapterView<*>, is ImageButton -> structured = true
                is ImageView -> if (view.drawable is BitmapDrawable && view.scaleType != ImageView.ScaleType.CENTER &&
                    coveredWidth >= width * .77f && coveredHeight >= height * .77f) fullImage = true
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    val wrapper = engineRoot && coversWidget
                    visit(child, left + child.x - view.scrollX, top + child.y - view.scrollY, wrapper)
                    if (visited > 96 || separateSurface) break
                }
            }
        }

    }

    private fun canReplaceFlatBackground(view: View, drawable: Drawable, replaceGradient: Boolean): Boolean {
        if (drawable.isStateful || drawable.callback !== view) return false
        return when (drawable) {
            is ColorDrawable -> drawable.alpha > 0
            is GradientDrawable -> drawable.alpha > 0 && drawable.shape == GradientDrawable.RECTANGLE &&
                (if (drawable.colors != null) replaceGradient else (drawable.color?.defaultColor?.ushr(24) ?: 0) > 0)
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun isOpaque(view: View) = view.background?.opacity == PixelFormat.OPAQUE
}
