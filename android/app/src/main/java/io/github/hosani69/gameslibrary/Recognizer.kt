package io.github.hosani69.gameslibrary

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/** On-device recognition with ML Kit's bundled models: works offline and costs nothing. */
object Recognizer {
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    /** Loads a picture, shrunk to [maxSide] and turned the right way up. */
    fun loadUpright(file: File, maxSide: Int = 2000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Could not read the picture")
        val degrees = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /**
     * Makes the square (1:1) image kept for the item: finds the item against the background,
     * centres it and fills any gap with the background colour. Falls back to a centre crop.
     */
    fun square(photo: Bitmap, size: Int = 1000): Bitmap {
        val f = 480.0 / max(photo.width, photo.height)
        val small = Bitmap.createScaledBitmap(photo, max(1, (photo.width * f).toInt()), max(1, (photo.height * f).toInt()), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val item = runCatching { Segmenter(px, small.width, small.height).items().maxByOrNull { it.area } }.getOrNull()
            ?.takeIf { it.area >= small.width.toLong() * small.height * 0.08 }
            ?.scaled(1 / f)?.toRect()

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        // The item with a little margin, or the whole picture when the item fills it; never cut off.
        val src = if (item == null) Rect(0, 0, photo.width, photo.height) else {
            val m = (max(item.width(), item.height()) * 0.06).toInt()
            Rect(max(0, item.left - m), max(0, item.top - m), min(photo.width, item.right + m), min(photo.height, item.bottom + m))
        }
        canvas.drawColor(px[0] or (0xFF shl 24))
        val scale = size.toFloat() / max(src.width(), src.height())
        val w = (src.width() * scale).toInt()
        val h = (src.height() * scale).toInt()
        canvas.drawBitmap(photo, src, Rect((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), paint)
        return out
    }

    /** Shrinks a picture so its longest side is at most [maxSide]. */
    fun fit(photo: Bitmap, maxSide: Int): Bitmap {
        val f = maxSide.toFloat() / max(photo.width, photo.height)
        if (f >= 1f) return photo
        return Bitmap.createScaledBitmap(photo, max(1, (photo.width * f).toInt()), max(1, (photo.height * f).toInt()), true)
    }

    /**
     * Cuts the square at ([left], [top]) with sides [side] (in [src] pixels) into a [size]x[size] image.
     * The square may reach past the picture's edges; that part is filled with the edge colour.
     */
    fun cropSquare(src: Bitmap, left: Float, top: Float, side: Float, size: Int = 1000): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(src.getPixel(0, 0) or (0xFF shl 24))
        val scale = size / side
        val m = Matrix().apply { postTranslate(-left, -top); postScale(scale, scale) }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Reads every line of text and what the picture looks like (labels such as "Toy"). */
    suspend fun read(image: Bitmap): Pair<List<TextLine>, List<String>> {
        val input = InputImage.fromBitmap(image, 0)
        val lines = runCatching {
            reader.process(input).await().textBlocks.flatMap { it.lines }.mapNotNull { l ->
                l.boundingBox?.let { TextLine(l.text, it.height(), it.top) }
            }
        }.getOrDefault(emptyList())
        val labels = runCatching {
            labeler.process(input).await().filter { it.confidence >= 0.6f }.map { it.text }
        }.getOrDefault(emptyList())
        return lines to labels
    }
}

fun Box.toRect() = Rect(left, top, right, bottom)

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
