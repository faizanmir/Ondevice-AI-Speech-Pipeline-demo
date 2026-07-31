package com.example.aiagenttestapp.data.audit

/**
 * Reads the bullet points out of a quick-audit reply.
 *
 * Lives here, with the other parsers, rather than beside the prompt that asks for them: writing a
 * prompt and reading a reply are different jobs with different reasons to change. The prompt is
 * tuned when the model words things badly; this is tuned when the model *formats* things badly.
 */
object QuickPointsParser {

    /**
     * Reads a quick summary reply into at most [maxPoints] points.
     *
     * The cap is enforced here rather than trusted to the prompt: asked for "at most 10" a small
     * model will hand back fourteen, and a limit that lives only in a prompt is a request. Tolerant
     * of the decorations models add -- bullets, numbering, markdown emphasis -- and it drops any
     * preamble line that is not a point, so "Here are the key points:" never becomes point one.
     */
    fun parseQuickPoints(reply: String, maxPoints: Int = QuickAudit.MAX_POINTS): List<String> {
        val points = mutableListOf<String>()
        for (raw in reply.lineSequence()) {
            // Emphasis is unwrapped BEFORE the bullet is matched, and only in matched pairs. An
            // asterisk is both a bullet marker and an emphasis marker, so a blanket strip of '*'
            // deletes the very marker the match below looks for -- which silently dropped every
            // point a model wrote as "* like this".
            val line = unwrapEmphasis(raw)
            if (line.isEmpty()) continue
            // A point is a line the model marked as one: a bullet, or a number followed by a
            // separator. Anything else is preamble ("Here are the key points:") or trailing chatter,
            // and taking it would put the model's throat-clearing in the report.
            val body = BULLET.find(line)?.groupValues?.get(1) ?: continue
            // Again on the content, for a point whose text is itself emphasised.
            val point = unwrapEmphasis(body)
            if (point.isEmpty()) continue
            points += point
            if (points.size == maxPoints) break
        }
        return points
    }


    /**
     * Removes matched markdown wrappers from [text], longest marker first so `***x***` does not
     * leave a stray asterisk. Only pairs: a leading marker with no partner is left alone, because
     * that is what a bullet looks like.
     */
    private fun unwrapEmphasis(text: String): String {
        var value = text.trim()
        for (marker in EMPHASIS_MARKERS) {
            while (
                value.length > marker.length * 2 &&
                value.startsWith(marker) &&
                value.endsWith(marker)
            ) {
                value = value.substring(marker.length, value.length - marker.length).trim()
            }
        }
        return value
    }


    private val EMPHASIS_MARKERS = listOf("***", "___", "**", "__", "*", "_", "`")


    /** "- point", "* point", "1. point", "2) point" -- the markers a model actually emits. */
    private val BULLET = Regex("""^(?:[-–—•*+]|\d{1,2}\s*[.):])\s+(.+)$""")
}
