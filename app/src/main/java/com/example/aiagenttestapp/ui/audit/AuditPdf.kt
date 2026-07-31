package com.example.aiagenttestapp.ui.audit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.example.aiagenttestapp.data.audit.AuditAnalysis
import com.example.aiagenttestapp.data.audit.AuditFinding
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditResultType
import com.example.aiagenttestapp.data.audit.AuditSeverity
import com.example.aiagenttestapp.ui.components.formatDuration
import java.io.ByteArrayOutputStream

/**
 * Renders a finished audit report as a PDF, mirroring [AuditReportContent] section for section --
 * provenance, the incomplete banner, stated result, summary, then non-conformities and actions with
 * their severity badges, verified quotes and standards. Same data points, same order, so the
 * exported artefact says exactly what the screen said, just on paper.
 *
 * Colours are fixed print-safe values (Material baseline light) rather than the app theme: a PDF
 * has no dark mode, and an exported compliance artefact should look the same on every device.
 */
object AuditPdf {

    fun render(
        documentName: String,
        analysis: AuditAnalysis,
        modelName: String?,
        analysisMillis: Long,
    ): ByteArray {
        val pdf = PdfDocument()
        val page = Writer(pdf)

        page.text(documentName, TITLE, spacingAfter = 4f)
        val provenance = buildList {
            modelName?.takeIf { it.isNotBlank() }?.let(::add)
            analysis.engineName.takeIf { it.isNotBlank() }?.let { engine ->
                add(
                    if (analysis.promptProfile.isBlank()) engine
                    else "$engine · ${analysis.promptProfile}",
                )
            }
            formatDuration(analysisMillis).takeIf { it.isNotEmpty() }?.let { add("Generated in $it") }
        }.joinToString(" · ")
        if (provenance.isNotEmpty()) page.text(provenance, MUTED, spacingAfter = 14f)

        // Above everything, exactly like the screen: a report covering only part of its document
        // must say so before it shows findings.
        if (analysis.isIncomplete) {
            page.banner(
                buildString {
                    append(
                        when {
                            analysis.unanalysedSections == 1 ->
                                "1 section of this document could not be analysed. " +
                                    "These findings are incomplete."

                            analysis.unanalysedSections > 1 ->
                                "${analysis.unanalysedSections} sections of this document could not " +
                                    "be analysed. These findings are incomplete."

                            analysis.truncatedChars > 0 ->
                                "This report does not cover the whole document."

                            else -> "This report covers the whole document, with one caveat."
                        },
                    )
                    // The why, matching the on-screen banner line for line.
                    analysis.unanalysedReasons.forEach { reason ->
                        append('\n')
                        append(reason)
                    }
                },
                background = BANNER_BG,
                paint = BANNER_TEXT,
                spacingAfter = 14f,
            )
        }

        page.heading("Summary")
        if (analysis.verdict.isNotBlank()) {
            page.text("Stated result: “${analysis.verdict}”", BODY_BOLD, spacingAfter = 4f)
        }
        // Mirrors the screen: a quick read's summary is its point list, so it prints as one.
        if (analysis.auditMode == AuditMode.QUICK && analysis.keyPoints.isNotEmpty()) {
            analysis.keyPoints.forEachIndexed { index, point ->
                page.text("${index + 1}.  $point", BODY, spacingAfter = 4f)
            }
            page.text("", BODY, spacingAfter = 12f)
        } else {
            page.text(
                analysis.summary.ifBlank { "No summary was produced for this document." },
                BODY,
                spacingAfter = 16f,
            )
        }

        // Omitted entirely for a quick read, exactly as on screen: quick mode never looks for
        // non-conformities, so printing "none found" would assert something it never checked.
        if (analysis.auditMode == AuditMode.DETAILED) {
            page.findings(
                title = "Non-conformities (${analysis.nonConformities.size})",
                findings = analysis.nonConformities,
                emptyText = "No non-conformities found.",
            )
        }
        page.findings(
            title = "Actions needed (${analysis.actions.size})",
            findings = analysis.actions,
            emptyText = "No actions identified.",
        )

        page.finish()
        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }

    private fun Writer.heading(title: String) = text(title, HEADING, spacingAfter = 6f)

    /** One findings section, ordered and annotated exactly like [AuditReportContent]'s cards. */
    private fun Writer.findings(title: String, findings: List<AuditFinding>, emptyText: String) {
        heading(title)
        val breakdown = severityBreakdown(findings)
        if (breakdown.isNotEmpty()) text(breakdown, MUTED, spacingAfter = 6f)
        if (findings.isEmpty()) {
            text(emptyText, MUTED_BODY, spacingAfter = 16f)
            return
        }
        // Worst grade first, stable within a grade -- the same order the screen shows.
        val ordered = findings.sortedByDescending { AuditSeverity.rank(it.severity) }
        ordered.forEachIndexed { index, finding ->
            // Keep the badge and title from straddling a page break; the prose below may split.
            reserve(36f)
            val tag = finding.resultType?.let(::resultColours) ?: severityColours(finding.severity)
            tag?.let { (bg, fg, label) -> badge(label, bg, fg) }
            text("${index + 1}. ${finding.title}", BODY_BOLD, spacingAfter = 2f)
            if (finding.detail.isNotBlank()) {
                text(finding.detail, MUTED_BODY, indent = INDENT, spacingAfter = 2f)
            }
            if (finding.evidence.isNotBlank()) {
                text("“${finding.evidence}”", QUOTE, indent = INDENT, spacingAfter = 2f)
            }
            if (finding.standards.isNotEmpty()) {
                text(
                    "Standards: ${finding.standards.joinToString(", ")}",
                    MUTED,
                    indent = INDENT,
                    spacingAfter = 2f,
                )
            }
            spacer(if (index == ordered.lastIndex) 16f else 10f)
        }
    }

    /** The PDF's copy of the screen's result badge -- same words, same colours, printed. */
    private fun resultColours(resultType: AuditResultType): Triple<Int, Int, String> = when (resultType) {
        AuditResultType.MAJOR_NONCONFORMITY -> Triple(0xFFF9DEDC.toInt(), 0xFF410E0B.toInt(), "MAJOR")
        AuditResultType.MINOR_NONCONFORMITY -> Triple(0xFFFFD8E4.toInt(), 0xFF31111D.toInt(), "MINOR")
        AuditResultType.OK_FOR_DOCUMENTATION ->
            Triple(0xFFE8DEF8.toInt(), 0xFF1D192B.toInt(), "OK · DOCUMENTATION")
        AuditResultType.POTENTIAL_IMPROVEMENT ->
            Triple(0xFFE7E0EC.toInt(), 0xFF49454F.toInt(), "IMPROVEMENT")
        AuditResultType.OK -> Triple(0xFFE7E0EC.toInt(), 0xFF49454F.toInt(), "OK")
    }

    private fun severityColours(severity: String): Triple<Int, Int, String>? = when (severity) {
        AuditSeverity.MAJOR -> Triple(0xFFF9DEDC.toInt(), 0xFF410E0B.toInt(), "MAJOR")
        AuditSeverity.MINOR -> Triple(0xFFFFD8E4.toInt(), 0xFF31111D.toInt(), "MINOR")
        AuditSeverity.OBSERVATION -> Triple(0xFFE8DEF8.toInt(), 0xFF1D192B.toInt(), "OBSERVATION")
        else -> null
    }

    /**
     * Cursor-and-pages state over a [PdfDocument]: blocks are appended top to bottom, and anything
     * that does not fit continues on a fresh page. Text splits at line granularity, so a long
     * summary flows across pages instead of being clipped or dropped.
     */
    private class Writer(private val pdf: PdfDocument) {

        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = 0f

        private fun canvas(): Canvas {
            val open = page ?: pdf.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, ++pageNumber).create(),
            ).also {
                page = it
                y = MARGIN
            }
            return open.canvas
        }

        private fun breakPage() {
            page?.let(pdf::finishPage)
            page = null
        }

        fun finish() {
            page?.let(pdf::finishPage)
            page = null
        }

        fun spacer(height: Float) {
            if (page != null) y += height
        }

        /** Starts a fresh page unless [height] fits below the cursor -- for blocks kept whole. */
        fun reserve(height: Float) {
            canvas()
            if (y + height > PAGE_HEIGHT - MARGIN) breakPage()
        }

        fun text(text: String, paint: TextPaint, indent: Float = 0f, spacingAfter: Float = 0f) {
            if (text.isBlank()) return
            val width = (CONTENT_WIDTH - indent).toInt()
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setLineSpacing(2f, 1f)
                .build()
            var line = 0
            while (line < layout.lineCount) {
                val c = canvas()
                val remaining = PAGE_HEIGHT - MARGIN - y
                var end = line
                while (end < layout.lineCount &&
                    layout.getLineBottom(end) - layout.getLineTop(line) <= remaining
                ) {
                    end++
                }
                if (end == line) {
                    // Not even one line fits here; on a fresh page at least one always will.
                    breakPage()
                    continue
                }
                val sliceTop = layout.getLineTop(line)
                val sliceBottom = layout.getLineBottom(end - 1)
                c.save()
                c.translate(MARGIN + indent, y - sliceTop)
                c.clipRect(0f, sliceTop.toFloat(), width.toFloat(), sliceBottom.toFloat())
                layout.draw(c)
                c.restore()
                y += sliceBottom - sliceTop
                line = end
            }
            y += spacingAfter
        }

        /** A small rounded grade tag, the PDF's stand-in for the screen's SeverityBadge. */
        fun badge(label: String, background: Int, foreground: Int) {
            val paint = paint(8f, foreground, bold = true)
            val width = paint.measureText(label) + 2 * BADGE_PAD
            val height = 13f
            reserve(height)
            val c = canvas()
            c.drawRoundRect(
                RectF(MARGIN, y, MARGIN + width, y + height),
                3f,
                3f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
            )
            c.drawText(
                label,
                MARGIN + BADGE_PAD,
                y + height / 2 - (paint.ascent() + paint.descent()) / 2,
                paint,
            )
            y += height + 4f
        }

        /** A full-width tinted block, the PDF's stand-in for the screen's IncompleteBanner. */
        fun banner(text: String, background: Int, paint: TextPaint, spacingAfter: Float) {
            val width = (CONTENT_WIDTH - 2 * BANNER_PAD).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build()
            val height = layout.height + 2 * BANNER_PAD
            reserve(height)
            val c = canvas()
            c.drawRoundRect(
                RectF(MARGIN, y, MARGIN + CONTENT_WIDTH, y + height),
                6f,
                6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
            )
            c.save()
            c.translate(MARGIN + BANNER_PAD, y + BANNER_PAD)
            layout.draw(c)
            c.restore()
            y += height + spacingAfter
        }
    }

    // A4 at 72 dpi, the PdfDocument convention.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val INDENT = 14f
    private const val BADGE_PAD = 5f
    private const val BANNER_PAD = 10f

    private fun paint(size: Float, colour: Int, bold: Boolean = false, italic: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = colour
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )
        }

    private val TITLE = paint(17f, 0xFF1B1B1F.toInt(), bold = true)
    private val HEADING = paint(12f, 0xFF445E91.toInt(), bold = true)
    private val BODY = paint(10f, 0xFF1B1B1F.toInt())
    private val BODY_BOLD = paint(10f, 0xFF1B1B1F.toInt(), bold = true)
    private val MUTED_BODY = paint(10f, 0xFF5F5F66.toInt())
    private val MUTED = paint(9f, 0xFF5F5F66.toInt())
    private val QUOTE = paint(9.5f, 0xFF5F5F66.toInt(), italic = true)
    private val BANNER_BG = 0xFFF9DEDC.toInt()
    private val BANNER_TEXT = paint(9.5f, 0xFF410E0B.toInt())
}
