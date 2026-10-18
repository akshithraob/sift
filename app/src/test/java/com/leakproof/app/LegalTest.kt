package com.leakproof.app

import com.leakproof.app.legal.LegalInfo
import com.leakproof.app.legal.MdBlock
import com.leakproof.app.legal.MdSpan
import com.leakproof.app.legal.PolicyText
import com.leakproof.app.legal.SimpleMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegalTest {

    private val template = File("src/main/assets/privacy_policy.md").readText()
    private val legalXml = File("src/main/res/values/legal.xml").readText()

    private val info = LegalInfo("Test Publisher", "privacy@example.test", "1 January 2027", 3)

    @Test
    fun everyPlaceholderInThePolicyIsFilledIn() {
        val rendered = PolicyText.render(template, info)

        assertFalse("unresolved {{...}} left in the policy", rendered.contains("{{"))
        assertTrue(rendered.contains("Test Publisher"))
        assertTrue(rendered.contains("privacy@example.test"))
        assertTrue(rendered.contains("Version 3"))
        assertTrue(rendered.contains("Effective 1 January 2027"))
    }

    @Test
    fun policyCoversWhatTheDpdpActAsksFor() {
        val text = PolicyText.render(template, info)

        for (needle in listOf(
            "What the app handles", "Why the app uses this information", "Who it is shared with",
            "How long it is kept", "Withdraw consent", "Data Protection Board", "Grievance", "within 30 days", "under 18"
        )) {
            assertTrue("policy is missing: $needle", text.contains(needle, ignoreCase = true))
        }
    }

    @Test
    fun policyStaysConsistentWithTheAppsPromises() {
        val text = PolicyText.render(template, info)

        // These claims are enforced elsewhere (manifest guard, instrumented test, backup code)
        assertTrue(text.contains("no network permission", ignoreCase = true))
        assertTrue(text.contains("AES-256"))
        assertTrue(text.contains("Delete all my data & withdraw consent"))
        assertFalse("must not promise things the app does not do", text.contains("100% secure", ignoreCase = true))
    }

    @Test
    fun legalResourcesDefineEveryValueTheTemplateUses() {
        for (key in listOf("publisher_name", "privacy_contact_email", "policy_effective_date", "policy_version")) {
            assertTrue("legal.xml is missing $key", legalXml.contains("name=\"$key\""))
        }
    }

    @Test
    fun markdownParsesHeadingsBulletsAndParagraphs() {
        val blocks = SimpleMarkdown.parse(
            """
            # Title

            Intro line one
            continues here.

            ## Section
            - first **bold** item
            - second item
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Intro line one continues here."),
                MdBlock.Heading(2, "Section"),
                MdBlock.Bullet("first **bold** item"),
                MdBlock.Bullet("second item")
            ),
            blocks
        )
    }

    @Test
    fun boldSpansAreSplitOut() {
        assertEquals(
            listOf(MdSpan("a ", false), MdSpan("b", true), MdSpan(" c", false)),
            SimpleMarkdown.spans("a **b** c")
        )
        assertEquals(listOf(MdSpan("plain", false)), SimpleMarkdown.spans("plain"))
    }

    @Test
    fun theRealPolicyParsesIntoReadableBlocks() {
        val blocks = SimpleMarkdown.parse(PolicyText.render(template, info))

        assertEquals(MdBlock.Heading(1, "Leakproof Privacy Policy"), blocks.first())
        assertTrue(blocks.count { it is MdBlock.Heading && it.level == 2 } >= 10)
        assertTrue(blocks.any { it is MdBlock.Bullet })
    }
}
