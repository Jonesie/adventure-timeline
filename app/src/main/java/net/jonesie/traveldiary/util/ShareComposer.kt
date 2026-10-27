package net.jonesie.traveldiary.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.TripDetail
import net.jonesie.traveldiary.ui.starString
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class ShareFormat(val label: String, val mimeType: String, val extension: String) {
    IMAGE("Picture", "image/jpeg", "jpg"),
    PDF("PDF", "application/pdf", "pdf")
}

data class ShareOptions(
    val title: String,
    val message: String,
    val includeDates: Boolean,
    val includeRating: Boolean,
    val includeMap: Boolean,
    val includeCompanions: Boolean,
    val includeTripNotes: Boolean,
    val includeImpressions: Boolean,
    val photos: List<MediaItem>,
    val format: ShareFormat
)

class ShareResult(val file: File, val text: String)

/**
 * Lays a trip out as one shareable document: title, map snapshot, people, notes and chosen photos,
 * rendered either as a single tall picture or as a paginated PDF. Everything is drawn from
 * small blocks so a PDF can break between them.
 */
object ShareComposer {

    private const val DESIGN_WIDTH = 1080f
    // 1.5x the design size so text and photos stay sharp when the recipient zooms in.
    private const val IMAGE_WIDTH = 1620
    private const val MAX_IMAGE_HEIGHT = 12000
    private const val PDF_WIDTH = 595
    private const val PDF_HEIGHT = 842

    private abstract class Block {
        abstract fun height(u: Float, width: Float): Float
        abstract fun draw(canvas: Canvas, u: Float, x: Float, y: Float, width: Float)
    }

    private fun textPaint(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layoutFor(text: String, paint: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .build()

    private const val INK = 0xFF222222.toInt()
    private const val MUTED = 0xFF6B6B6B.toInt()

    private class TextBlock(
        val text: String,
        val size: Float,
        val color: Int,
        val bold: Boolean = false,
        val gapBelow: Float = 14f
    ) : Block() {
        override fun height(u: Float, width: Float) = layoutFor(text, textPaint(size * u, color, bold), width).height + gapBelow * u
        override fun draw(canvas: Canvas, u: Float, x: Float, y: Float, width: Float) {
            val layout = layoutFor(text, textPaint(size * u, color, bold), width)
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private class SpacerBlock(val size: Float) : Block() {
        override fun height(u: Float, width: Float) = size * u
        override fun draw(canvas: Canvas, u: Float, x: Float, y: Float, width: Float) {}
    }

    private class MapBlock(val bitmap: Bitmap) : Block() {
        override fun height(u: Float, width: Float) =
            width * (bitmap.height.toFloat() / bitmap.width).coerceAtMost(1.1f) + 24f * u

        override fun draw(canvas: Canvas, u: Float, x: Float, y: Float, width: Float) {
            val h = width * (bitmap.height.toFloat() / bitmap.width).coerceAtMost(1.1f)
            drawRounded(canvas, bitmap, RectF(x, y, x + width, y + h), 24f * u)
        }
    }

    /** One row of photos; a lone photo gets the full width, otherwise square cells. */
    private class PhotoRowBlock(val photos: List<Bitmap>, val columns: Int) : Block() {
        private fun cell(u: Float, width: Float): Float = (width - 16f * u * (columns - 1)) / columns
        override fun height(u: Float, width: Float): Float =
            if (photos.size == 1 && columns == 1) fullHeight(width) + 16f * u else cell(u, width) + 16f * u

        private fun fullHeight(width: Float): Float {
            val b = photos.first()
            return width * (b.height.toFloat() / b.width).coerceIn(0.5f, 1.3f)
        }

        override fun draw(canvas: Canvas, u: Float, x: Float, y: Float, width: Float) {
            if (photos.size == 1 && columns == 1) {
                drawRounded(canvas, photos.first(), RectF(x, y, x + width, y + fullHeight(width)), 20f * u, crop = true)
                return
            }
            val size = cell(u, width)
            photos.forEachIndexed { i, bitmap ->
                val left = x + i * (size + 16f * u)
                drawRounded(canvas, bitmap, RectF(left, y, left + size, y + size), 20f * u, crop = true)
            }
        }
    }

    private fun drawRounded(canvas: Canvas, bitmap: Bitmap, target: RectF, radius: Float, crop: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(target, radius, radius, Path.Direction.CW) })
        val src = if (crop) {
            // Centre-crop to the target's shape.
            val targetRatio = target.width() / target.height()
            val bitmapRatio = bitmap.width.toFloat() / bitmap.height
            if (bitmapRatio > targetRatio) {
                val w = (bitmap.height * targetRatio).toInt()
                android.graphics.Rect((bitmap.width - w) / 2, 0, (bitmap.width + w) / 2, bitmap.height)
            } else {
                val h = (bitmap.width / targetRatio).toInt()
                android.graphics.Rect(0, (bitmap.height - h) / 2, bitmap.width, (bitmap.height + h) / 2)
            }
        } else null
        canvas.drawBitmap(bitmap, src, target, paint)
        canvas.restore()
    }

    suspend fun compose(context: Context, trip: TripDetail, mapBitmap: Bitmap?, options: ShareOptions): ShareResult =
        withContext(Dispatchers.IO) {
            val dateFormat = DateFormat.getDateInstance(DateFormat.LONG)
            val timeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            val t = trip.trip

            val dates = dateFormat.format(Date(t.startDate)) +
                (t.endDate?.takeIf { it != t.startDate }?.let { " – " + dateFormat.format(Date(it)) } ?: "")
            val subtitle = listOfNotNull(
                dates.takeIf { options.includeDates },
                starString(t.rating).takeIf { options.includeRating && t.rating > 0 }
            ).joinToString("   ")

            val blocks = mutableListOf<Block>()
            val plain = StringBuilder(t.title)
            val title = options.title.trim().ifEmpty { t.title }
            plain.setLength(0)
            plain.append(title)
            blocks += TextBlock(title, 68f, INK, bold = true, gapBelow = 10f)
            if (subtitle.isNotEmpty()) {
                blocks += TextBlock(subtitle, 36f, MUTED, gapBelow = 24f)
                plain.append('\n').append(subtitle)
            }
            if (options.includeMap && mapBitmap != null) blocks += MapBlock(mapBitmap)
            // The message reads as a caption under the map.
            if (options.message.isNotBlank()) {
                blocks += TextBlock(options.message.trim(), 40f, INK, gapBelow = 24f)
                plain.append("\n\n").append(options.message.trim())
            }

            if (options.includeCompanions && trip.companions.isNotEmpty()) {
                val names = trip.companions.joinToString(", ") { it.name }
                blocks += TextBlock("With", 32f, MUTED, bold = true, gapBelow = 4f)
                blocks += TextBlock(names, 40f, INK, gapBelow = 24f)
                plain.append("\n\nWith ").append(names)
            }
            // The importer stamps trips with "Imported from Google Timeline"; that's not something to share.
            val notes = t.notes.lines().filterNot { it.contains("Imported from Google Timeline", ignoreCase = true) }
                .joinToString("\n").trim()
            if (options.includeTripNotes && notes.isNotEmpty()) {
                blocks += TextBlock("Notes", 32f, MUTED, bold = true, gapBelow = 4f)
                blocks += TextBlock(notes, 38f, INK, gapBelow = 24f)
                plain.append("\n\n").append(notes)
            }
            if (options.includeImpressions && trip.impressions.isNotEmpty()) {
                blocks += TextBlock("Impressions", 32f, MUTED, bold = true, gapBelow = 8f)
                plain.append("\n")
                trip.impressions.sortedBy { it.timestamp }.forEach { note ->
                    blocks += TextBlock(timeFormat.format(Date(note.timestamp)), 28f, MUTED, gapBelow = 2f)
                    blocks += TextBlock(note.text.trim(), 38f, INK, gapBelow = 18f)
                    plain.append('\n').append(note.text.trim())
                }
            }

            // The PDF stores photos losslessly, so keep them smaller there to keep the file shareable.
            val photoSize = if (options.format == ShareFormat.PDF) 1400 else 1100
            val bitmaps = options.photos.mapNotNull { loadPhoto(context, it, photoSize) }
            if (bitmaps.isNotEmpty()) {
                blocks += SpacerBlock(8f)
                if (bitmaps.size == 1) blocks += PhotoRowBlock(bitmaps, 1)
                else bitmaps.chunked(2).forEach { blocks += PhotoRowBlock(it, 2) }
            }
            blocks += SpacerBlock(10f)
            blocks += TextBlock("Made with Travel Diary", 26f, MUTED, gapBelow = 0f)

            val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val safeName = t.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "trip" }
            val file = File(dir, "$safeName.${options.format.extension}")
            when (options.format) {
                ShareFormat.IMAGE -> writeImage(file, blocks)
                ShareFormat.PDF -> writePdf(file, blocks)
            }
            bitmaps.forEach { it.recycle() }
            ShareResult(file, plain.toString())
        }

    private fun writeImage(file: File, blocks: List<Block>) {
        val width = IMAGE_WIDTH
        val u = width / DESIGN_WIDTH
        val margin = 64f * u
        val contentWidth = width - 2 * margin
        val total = margin * 2 + blocks.sumOf { it.height(u, contentWidth).toDouble() }.toFloat()
        require(total <= MAX_IMAGE_HEIGHT) { "That's too long for a single picture. Choose fewer photos or notes, or share as a PDF." }

        val bitmap = Bitmap.createBitmap(width, total.toInt() + 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = margin
        blocks.forEach { block ->
            block.draw(canvas, u, margin, y, contentWidth)
            y += block.height(u, contentWidth)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private fun writePdf(file: File, blocks: List<Block>) {
        val u = PDF_WIDTH / DESIGN_WIDTH
        val margin = 64f * u
        val contentWidth = PDF_WIDTH - 2 * margin
        val pdf = PdfDocument()
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create())
        var y = margin
        blocks.forEach { block ->
            val h = block.height(u, contentWidth)
            if (y + h > PDF_HEIGHT - margin && y > margin) {
                pdf.finishPage(page)
                pageNumber++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(PDF_WIDTH, PDF_HEIGHT, pageNumber).create())
                y = margin
            }
            block.draw(page.canvas, u, margin, y, contentWidth)
            y += h
        }
        pdf.finishPage(page)
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    /** Decodes a photo with its EXIF rotation applied, no longer than [maxSide] px on its long edge. */
    private fun loadPhoto(context: Context, item: MediaItem, maxSide: Int): Bitmap? = runCatching {
        val uri = android.net.Uri.parse(item.uri)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies the orientation recorded in the file for us.
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val factor = maxSide.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * factor).toInt().coerceAtLeast(1),
                        (info.size.height * factor).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            loadPhotoLegacy(context, uri, maxSide)
        }
    }.getOrNull()

    private fun loadPhotoLegacy(context: Context, uri: android.net.Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = resolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return decoded
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
}
