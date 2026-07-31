package com.example.aiagenttestapp.data.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditResultTypeTest {

    @Test
    fun `every case round-trips through its wire name`() {
        // The iOS app emits these spellings; an audit has to mean the same thing on both platforms.
        AuditResultType.entries.forEach { type ->
            assertEquals(type, AuditResultType.fromWire(type.wireName))
        }
    }

    @Test
    fun `the wire names are the ones the spec states`() {
        assertEquals("resultOK", AuditResultType.OK.wireName)
        assertEquals("resultOkForDocumentation", AuditResultType.OK_FOR_DOCUMENTATION.wireName)
        assertEquals("minorNonconformity", AuditResultType.MINOR_NONCONFORMITY.wireName)
        assertEquals("majorNonconformity", AuditResultType.MAJOR_NONCONFORMITY.wireName)
        assertEquals("resultPotentialImprovement", AuditResultType.POTENTIAL_IMPROVEMENT.wireName)
    }

    @Test
    fun `absence in any of its forms reads as no clear conclusion`() {
        // A missing key, an explicit null, the Swift spelling of nothing, and blank all mean the
        // same: the model did not reach a conclusion, and must not be given one.
        assertNull(AuditResultType.fromWire(null))
        assertNull(AuditResultType.fromWire(""))
        assertNull(AuditResultType.fromWire("   "))
        assertNull(AuditResultType.fromWire("nil"))
    }

    @Test
    fun `an invented case is no conclusion rather than an error`() {
        // Models occasionally invent a case. Failing loudly would lose a whole section; inventing a
        // verdict in response would be the exact failure this scheme exists to prevent.
        assertNull(AuditResultType.fromWire("catastrophicNonconformity"))
    }

    @Test
    fun `only the two nonconformities count as findings`() {
        assertTrue(AuditResultType.MAJOR_NONCONFORMITY.isNonconformity)
        assertTrue(AuditResultType.MINOR_NONCONFORMITY.isNonconformity)
        assertFalse(AuditResultType.OK.isNonconformity)
        assertFalse(AuditResultType.OK_FOR_DOCUMENTATION.isNonconformity)
        assertFalse(AuditResultType.POTENTIAL_IMPROVEMENT.isNonconformity)
    }

    @Test
    fun `severity never moves down`() {
        val major = AuditResultType.MAJOR_NONCONFORMITY
        val ok = AuditResultType.OK

        // The case that matters: a later chunk discussing the same item favourably must not undo a
        // major raised earlier. A finding that softens on its way to the report disappears.
        assertEquals(major, AuditResultType.neverDowngrade(current = major, proposed = ok))
        assertEquals(major, AuditResultType.neverDowngrade(current = ok, proposed = major))
    }

    @Test
    fun `no conclusion never overwrites one that was reached`() {
        val minor = AuditResultType.MINOR_NONCONFORMITY

        assertEquals(minor, AuditResultType.neverDowngrade(current = minor, proposed = null))
        assertEquals(minor, AuditResultType.neverDowngrade(current = null, proposed = minor))
        assertNull(AuditResultType.neverDowngrade(current = null, proposed = null))
    }

    @Test
    fun `the ordering between the milder verdicts is explicit`() {
        fun merge(a: AuditResultType, b: AuditResultType) = AuditResultType.neverDowngrade(a, b)

        // Documentation-weak outranks a pass and a suggestion: it is the one that still needs doing
        // something about, so a later "OK" must not bury it.
        assertEquals(
            AuditResultType.OK_FOR_DOCUMENTATION,
            merge(AuditResultType.OK_FOR_DOCUMENTATION, AuditResultType.OK),
        )
        assertEquals(
            AuditResultType.OK_FOR_DOCUMENTATION,
            merge(AuditResultType.POTENTIAL_IMPROVEMENT, AuditResultType.OK_FOR_DOCUMENTATION),
        )
        assertEquals(
            AuditResultType.MINOR_NONCONFORMITY,
            merge(AuditResultType.OK_FOR_DOCUMENTATION, AuditResultType.MINOR_NONCONFORMITY),
        )
    }
}
