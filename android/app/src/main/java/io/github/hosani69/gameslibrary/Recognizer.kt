package io.github.hosani69.gameslibrary

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

fun Box.toRect() = Rect(left, top, right, bottom)

/** On-device recognition with ML Kit's bundled models: works offline and costs nothing. */
object Recognizer {
    private val detector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
        )
    }
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Loads a camera photo, shrunk to [maxSide] and turned the right way up. */
    fun loadUpright(file: File, maxSide: Int = 2000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Could not read the photo")
        val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** Prepares the item finder on a small copy of the photo (fast, and enough detail to find edges). */
    fun segmenter(photo: Bitmap): Pair<Segmenter, Double> {
        val f = 480.0 / max(photo.width, photo.height)
        val small = Bitmap.createScaledBitmap(photo, max(1, (photo.width * f).toInt()), max(1, (photo.height * f).toInt()), true)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return Segmenter(px, small.width, small.height) to (1.0 / f)
    }

    /**
     * Finds each separate item in the photo. The edge-based finder handles items lying side by side;
     * ML Kit's object detector is the second opinion; the whole photo is the last resort.
     */
    suspend fun findItems(photo: Bitmap, seg: Segmenter, scale: Double): List<Rect> {
        val found = seg.items().map { it.scaled(scale).toRect() }
        if (found.size >= 2) return found
        val ml = runCatching { detectObjects(photo) }.getOrDefault(emptyList())
        if (ml.size >= 2) return ml
        return found.ifEmpty { ml.ifEmpty { listOf(Rect(0, 0, photo.width, photo.height)) } }
    }

    private suspend fun detectObjects(photo: Bitmap): List<Rect> {
        val area = photo.width.toLong() * photo.height
        val boxes = detector.process(InputImage.fromBitmap(photo, 0)).await()
            .map { it.boundingBox }
            .filter { it.width().toLong() * it.height() >= area * 0.03 }
        // Drop boxes that sit mostly inside a bigger one (a label detected on top of a box).
        val kept = boxes.sortedByDescending { it.width() * it.height() }.fold(mutableListOf<Rect>()) { acc, r ->
            if (acc.none { overlap(r, it) > 0.7 }) acc.add(r)
            acc
        }
        val rowHeight = max(1, photo.height / 4)
        return kept.sortedWith(compareBy({ it.centerY() / rowHeight }, { it.left }))
    }

    fun crop(photo: Bitmap, r: Rect): Bitmap {
        val padX = (r.width() * 0.04).toInt()
        val padY = (r.height() * 0.04).toInt()
        val left = max(0, r.left - padX)
        val top = max(0, r.top - padY)
        val right = min(photo.width, r.right + padX)
        val bottom = min(photo.height, r.bottom + padY)
        return Bitmap.createBitmap(photo, left, top, max(1, right - left), max(1, bottom - top))
    }

    /** Reads the biggest words on the cover, which are almost always the title. */
    suspend fun readTitle(cover: Bitmap): String {
        val lines = reader.process(InputImage.fromBitmap(cover, 0)).await()
            .textBlocks.flatMap { it.lines }
            .filter { line -> line.text.count { it.isLetter() } >= 2 && line.boundingBox != null }
        if (lines.isEmpty()) return ""
        val tallest = lines.maxOf { it.boundingBox!!.height() }
        return lines
            .filter { it.boundingBox!!.height() >= tallest * 0.7 }
            .sortedBy { it.boundingBox!!.top }
            .take(3)
            .joinToString(" ") { it.text }
            .replace(Regex("[^\\p{L}\\p{N} :'&.\\-!?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let(::tidyCase)
    }

    /** "SUPER MARIO ODYSSEY" -> "Super Mario Odyssey"; mixed case is left alone. */
    private fun tidyCase(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        return s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }

    private fun overlap(small: Rect, big: Rect): Double {
        val inter = Rect()
        if (!inter.setIntersect(small, big)) return 0.0
        val a = small.width().toDouble() * small.height()
        return if (a == 0.0) 0.0 else inter.width().toDouble() * inter.height() / a
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
