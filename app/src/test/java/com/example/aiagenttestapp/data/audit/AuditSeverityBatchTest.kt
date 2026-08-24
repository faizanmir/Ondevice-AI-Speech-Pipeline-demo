package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Batched grading is only safe because anything the batch drops is re-graded individually. These pin
 * what "dropped" means: a line the model skipped, renumbered, or wrote as prose must come back absent
 * rather than come back wrong, because an absent grade is retried and a wrong one is not.
 */
class AuditSeverityBatchTest {

    @Test
    fun `reads numbered grades in the common separator styles`() {
        val parsed = AuditSeverity.parseBatch(
            """
            1: minor
            2 - major
            3. observation
            """.trimIndent(),
        )

        assertEquals(AuditSeverity.MINOR, parsed[0])
        assertEquals(AuditSeverity.MAJOR, parsed[1])
        assertEquals(AuditSeverity.OBSERVATION, parsed[2])
    }

    @Test
    fun `omits an item the model skipped rather than shifting the rest`() {
        // 2 is missing. 3 must stay at index 2 -- shifting it up would silently mislabel a finding.
        val parsed = AuditSeverity.parseBatch("1: major\n3: minor")

        assertEquals(AuditSeverity.MAJOR, parsed[0])
        assertNull(parsed[1])
        assertEquals(AuditSeverity.MINOR, parsed[2])
    }

    @Test
    fun `ignores preamble and trailing chatter`() {
        val parsed = AuditSeverity.parseBatch(
            """
            Sure, here are the grades:
            1: minor
            Let me know if you need more detail.
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals(AuditSeverity.MINOR, parsed[0])
    }

    @Test
    fun `a numbered line naming no grade is left absent for the fallback`() {
        val parsed = AuditSeverity.parseBatch("1: not sure, needs review\n2: major")

        assertNull(parsed[0])
        assertEquals(AuditSeverity.MAJOR, parsed[1])
    }
}
