package deckers.thibault.aves.telegram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Renders the small JPEGs stored (encrypted) next to each upload, so grids never download originals. */
internal object TelegramPreviews {
    private const val THUMB_SIDE = 256
    private const val PREVIEW_SIDE = 1024

    /** (thumbnail, preview) or null when the platform cannot decode the file. */
    fun render(context: Context, uri: Uri, isVideo: Boolean): Pair<ByteArray, ByteArray>? {
        return try {
            val source = if (isVideo) videoFrame(context, uri) else image(context, uri)
            source ?: return null
            try {
                val preview = jpeg(scaled(source, PREVIEW_SIDE), 82)
                val thumb = jpeg(scaled(source, THUMB_SIDE), 80)
                thumb to preview
            } finally {
                source.recycle()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun image(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotation = try {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (rotation == 0f) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun videoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaled(source: Bitmap, side: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= side) return source
        val ratio = side.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            maxOf(1, (source.width * ratio).toInt()),
            maxOf(1, (source.height * ratio).toInt()),
            true
        )
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
