package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grades a finding is allowed to carry, enforced where a model cannot sample its way past them.
 *
 * Both rules here exist because a model produced the contradiction on a real document: a section
 * that named a gap and then graded it `resultPotentialImprovement`, and a verdict field holding a
 * vocabulary name instead of the document's own wording. The prompt forbids both. A prompt is a
 * request, and the report renders whatever arrives -- so a finding badged "Improvement" and a
 * "Stated result" the document never stated both reach a reader as fact.
 */
class AuditGradeConsistencyTest {

    @Test
    fun `a non-conformity keeps a non-conformity grade`() {
        assertEquals(
            AuditResultType.MINOR_NONCONFORMITY,
            AuditResultType.asNonconformity(AuditResultType.MINOR_NONCONFORMITY),
        )
        assertEquals(
            AuditResultType.MAJOR_NONCONFORMITY,
            AuditResultType.asNonconformity(AuditResultType.MAJOR_NONCONFORMITY),
        )
    }

    @Test
    fun `every passing grade is cleared off a non-conformity`() {
        // resultPotentialImprovement is the one that was actually observed: the rule used to say
        // "never grade a non-conformity as OK", and a model graded them Improvement instead. All
        // three are equally not a non-conformity.
        listOf(
            AuditResultType.OK_FOR_DOCUMENTATION,
            AuditResultType.POTENTIAL_IMPROVEMENT,
        ).forEach { pass ->
            assertNull("${pass.wireName} survived on a non-conformity", AuditResultType.asNonconformity(pass))
        }
    }

    @Test
    fun `a contradictory grade is cleared, never promoted`() {
        // Null, not MINOR. Promoting would invent the conclusion this vocabulary exists to stop
        // inventing -- the model said the finding is real and said something incoherent about how
        // serious it is, and only the second part is unusable. Same trade AuditEvidence makes with
        // a quote that will not verify: drop the claim, keep the finding.
        assertNull(AuditResultType.asNonconformity(AuditResultType.POTENTIAL_IMPROVEMENT))
        assertNull(AuditResultType.asNonconformity(null))
    }

    @Test
    fun `a verdict that is a vocabulary name is not the document's wording`() {
        // "Stated result: resultPotentialImprovement" attributes to a document a word it never used,
        // in the one field whose entire purpose is that it holds the document's own.
        AuditResultType.entries.forEach { type ->
            assertTrue("${type.wireName} read as document wording", AuditResultType.isWireName(type.wireName))
        }
        // Case and surrounding space are the model's, not a different answer.
        assertTrue(AuditResultType.isWireName("  MinorNonconformity "))
    }

    @Test
    fun `a real stated result is left alone`() {
        // What the field is for. These are the document's words and must survive verbatim -- the
        // whole reason the pipeline keeps a stated verdict separate from its own grading.
        listOf(
            "OK for documentation",
            "Passed with observations",
            "Nicht konform",
            "",
        ).forEach {
            assertTrue("dropped a real verdict: $it", !AuditResultType.isWireName(it))
        }
    }

    @Test
    fun `the parsers still accept a wire name where a grade belongs`() {
        // The narrow rule: a vocabulary name is wrong in `verdict` and right in `result`. Guarding
        // one must not break the other.
        val analysis = AuditRecordParser.parse(
            """
            RECORDS
            NONCONFORMITY
            title: Certificate not signed off
            result: minorNonconformity
            """.trimIndent(),
        )

        assertEquals(
            AuditResultType.MINOR_NONCONFORMITY,
            analysis.nonConformities.single().resultType,
        )
    }
}
