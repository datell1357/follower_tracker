package dev.datell.followertracker.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.createBitmap
import dev.datell.followertracker.R
import dev.datell.followertracker.core.Provider

/** Small, cached local artwork; refreshing a count does not redraw the gradient or load an image. */
internal object WidgetArtwork {
    private val cache = object : LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Synchronized
    fun background(provider: Provider?, dark: Boolean): Bitmap {
        val key = "${provider?.name}:$dark"
        cache.get(key)?.let { return it }
        val side = 256
        val bitmap = createBitmap(side, side)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val tones = when (provider) {
            Provider.INSTAGRAM -> intArrayOf(0xFF552A91.toInt(), 0xFF942A67.toInt(), 0xFF993A2C.toInt())
            Provider.TIKTOK -> intArrayOf(0xFF103739.toInt(), 0xFF131922.toInt(), 0xFF462033.toInt())
            Provider.X -> intArrayOf(0xFF343A44.toInt(), 0xFF161A21.toInt(), 0xFF0C0F14.toInt())
            Provider.FACEBOOK -> intArrayOf(0xFF174FBF.toInt(), 0xFF123E9B.toInt(), 0xFF112551.toInt())
            Provider.REDDIT -> intArrayOf(0xFFA93608.toInt(), 0xFF94300D.toInt(), 0xFF582413.toInt())
            null -> if (dark) intArrayOf(0xFF1E283D.toInt(), 0xFF151D2D.toInt())
                else intArrayOf(0xFFF9FBFF.toInt(), 0xFFEDF2FA.toInt())
        }
        paint.shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), tones, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        val glow = if (provider == Provider.TIKTOK) 0x4025F4EE else if (provider == null && !dark) 0x405E83EE else 0x12FFFFFF
        paint.shader = RadialGradient(238f, 8f, 200f, glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .7f
        paint.color = if (provider == null && !dark) 0x0D294B8A else 0x10FFFFFF
        for (radius in listOf(64f, 104f, 146f)) canvas.drawCircle(242f, 16f, radius, paint)
        cache.put(key, bitmap)
        return bitmap
    }

    fun icon(provider: Provider): Int = when (provider) {
        Provider.INSTAGRAM -> R.drawable.provider_instagram
        Provider.TIKTOK -> R.drawable.provider_tiktok
        Provider.X -> R.drawable.provider_x
        Provider.FACEBOOK -> R.drawable.provider_facebook
        Provider.REDDIT -> R.drawable.provider_reddit
    }
}
