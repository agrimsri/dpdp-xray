package com.dpdpxray.app.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Renders the Markdown report into a clean A4 PDF with Android's built-in PdfDocument (no network, no library). */
object PdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 40

    private enum class Kind { H1, H2, H3, BODY, QUOTE, CODE, BULLET, TABLE, RULE }

    fun render(markdown: String, out: File): File {
        val doc = PdfDocument()
        val lines = classify(markdown)
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
            val footer = TextPaint().apply { textSize = 7f; color = Color.GRAY; isAntiAlias = true }
            page!!.canvas.drawText("DPDP X-Ray · generated on-device · page $pageNo", M.toFloat(), (H - 20).toFloat(), footer)
        }
        newPage()

        for ((kind, text) in lines) {
            if (kind == Kind.RULE) {
                y += 6f
                continue
            }
            val paint = paintFor(kind)
            val indent = when (kind) { Kind.QUOTE, Kind.BULLET -> 12; Kind.CODE -> 8; else -> 0 }
            val width = W - 2 * M - indent
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
            val before = when (kind) { Kind.H1 -> 6f; Kind.H2 -> 12f; Kind.H3 -> 8f; else -> 2f }
            if (y + before + layout.height > H - M) newPage()
            y += before
            val canvas = page!!.canvas
            if (kind == Kind.CODE) {
                val bg = android.graphics.Paint().apply { color = Color.rgb(242, 244, 247) }
                canvas.drawRect((M + indent - 4).toFloat(), y - 2, (W - M).toFloat(), y + layout.height + 2, bg)
            }
            if (kind == Kind.QUOTE) {
                val bar = android.graphics.Paint().apply { color = Color.rgb(45, 212, 191); strokeWidth = 2f }
                canvas.drawLine((M + 4).toFloat(), y, (M + 4).toFloat(), y + layout.height, bar)
            }
            canvas.save()
            canvas.translate((M + indent).toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 3f
        }
        page?.let { doc.finishPage(it) }
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun paintFor(kind: Kind) = TextPaint().apply {
        isAntiAlias = true
        color = Color.rgb(27, 35, 51)
        when (kind) {
            Kind.H1 -> { textSize = 17f; typeface = Typeface.DEFAULT_BOLD }
            Kind.H2 -> { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
            Kind.H3 -> { textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }
            Kind.QUOTE -> { textSize = 8.5f; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); color = Color.rgb(70, 80, 95) }
            Kind.CODE -> { textSize = 7.5f; typeface = Typeface.MONOSPACE }
            Kind.TABLE -> { textSize = 8f; typeface = Typeface.MONOSPACE }
            else -> textSize = 9f
        }
    }

    private fun classify(md: String): List<Pair<Kind, String>> {
        val out = mutableListOf<Pair<Kind, String>>()
        var inCode = false
        val code = StringBuilder()
        for (raw in md.lines()) {
            if (raw.trim().startsWith("```")) {
                if (inCode) { out += Kind.CODE to code.toString().trimEnd(); code.clear() }
                inCode = !inCode
                continue
            }
            if (inCode) { code.appendLine(raw); continue }
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("# ") -> out += Kind.H1 to clean(line.drop(2))
                line.startsWith("## ") -> out += Kind.H2 to clean(line.drop(3))
                line.startsWith("### ") -> out += Kind.H3 to clean(line.drop(4))
                line.startsWith("> ") || line == ">" -> out += Kind.QUOTE to clean(line.removePrefix(">").trim())
                line.startsWith("- ") -> out += Kind.BULLET to "• " + clean(line.drop(2))
                line.startsWith("|") -> if (!line.startsWith("|---")) out += Kind.TABLE to line.trim('|').split('|').joinToString("  ·  ") { clean(it.trim()) }
                line.startsWith("---") -> out += Kind.RULE to ""
                else -> out += Kind.BODY to clean(line)
            }
        }
        return out.filterNot { it.first == Kind.QUOTE && it.second.isBlank() }
    }

    private fun clean(s: String) = s.replace("**", "").replace("`", "").replace(Regex("(?<!\\w)\\*(.+?)\\*(?!\\w)"), "$1")
}

object ExportManager {
    /** Copies to the shared Downloads folder, where any phone-to-laptop file transfer can pick it up. */
    fun saveToDownloads(context: Context, file: File, displayName: String, mime: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DPDP-XRay")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }.isSuccess

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** With clipboard sync to a laptop, a copied fix can be pasted straight into Android Studio. */
    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
