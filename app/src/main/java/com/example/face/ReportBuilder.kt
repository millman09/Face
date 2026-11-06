package com.example.face

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

/** Builds a PDF of saved matches that can be attached to a takedown request. */
object ReportBuilder {
    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 40
    private const val ROW_H = 190

    /** [loadImage] returns the match image (or null if it is gone); the report notes when it is missing. */
    fun write(matches: List<MatchResult>, out: OutputStream, loadImage: (MatchResult) -> Bitmap?) {
        val pdf = PdfDocument()
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.rgb(40, 40, 40) }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val frame = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(200, 200, 205) }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { pdf.finishPage(it) }
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            y = MARGIN.toFloat()
            if (pageNumber == 1) {
                page!!.canvas.drawText("Possible matches report", MARGIN.toFloat(), y + 18, title)
                y += 30
                page!!.canvas.drawText(
                    "Created ${dateFormat.format(Date())}  ·  ${matches.size} possible match(es)", MARGIN.toFloat(), y + 12, body
                )
                y += 22
                for (line in wrap(
                    "These are automatic face-similarity results from public pages, not proof of identity. " +
                        "Each entry lists where the image was found so you can report it to the site or a removal service.",
                    body, PAGE_W - 2f * MARGIN
                )) {
                    page!!.canvas.drawText(line, MARGIN.toFloat(), y + 12, body); y += 14
                }
                y += 10
            }
        }

        newPage()
        for ((index, match) in matches.withIndex()) {
            if (y + ROW_H > PAGE_H - MARGIN) newPage()
            val canvas = page!!.canvas
            canvas.drawRect(MARGIN.toFloat(), y, PAGE_W - MARGIN.toFloat(), y + ROW_H - 10, frame)

            val image = try { loadImage(match) } catch (_: Exception) { null }
            val boxLeft = MARGIN + 8f
            val boxTop = y + 8
            val boxSize = ROW_H - 26f
            if (image != null) {
                val shown = MatchTuning.highlight(image, match.faceBox)
                val scale = minOf(boxSize / shown.width, boxSize / shown.height)
                val w = (shown.width * scale).toInt().coerceAtLeast(1)
                val h = (shown.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(shown, w, h, true)
                canvas.drawBitmap(scaled, boxLeft, boxTop, null)
                if (scaled !== shown) scaled.recycle()
                if (shown !== image) shown.recycle()
                image.recycle()
            } else {
                canvas.drawText("Image no longer available", boxLeft, boxTop + 20, body)
            }

            val textLeft = boxLeft + boxSize + 14
            val textWidth = PAGE_W - MARGIN - 8f - textLeft
            var ty = y + 22
            val label = if (match.confirmed) "Confirmed by you" else matchLabel(match.similarity)
            canvas.drawText("${index + 1}. $label  (score ${String.format(java.util.Locale.US, "%.3f", match.similarity)})", textLeft, ty, bold)
            ty += 18
            val lines = listOf(
                "Found: ${dateFormat.format(Date(match.checkedAt))}",
                "Page: ${match.pageUrl.ifBlank { "unknown" }}",
                "Image: ${match.imageUrl}"
            )
            for (text in lines) {
                for (line in wrap(text, body, textWidth).take(4)) {
                    canvas.drawText(line, textLeft, ty, body); ty += 13
                }
                ty += 3
            }
            y += ROW_H
        }
        page?.let { pdf.finishPage(it) }
        pdf.writeTo(out)
        pdf.close()
    }

    /** Breaks [text] into lines no wider than [width], splitting long URLs where needed. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            var remaining = word
            while (paint.measureText(remaining) > width) {
                val fit = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
                if (current.isNotEmpty()) { lines += current.toString(); current = StringBuilder() }
                lines += remaining.substring(0, fit)
                remaining = remaining.substring(fit)
            }
            val candidate = if (current.isEmpty()) remaining else "$current $remaining"
            if (paint.measureText(candidate) > width && current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder(remaining)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
