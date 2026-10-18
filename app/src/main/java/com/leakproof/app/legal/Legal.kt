package com.leakproof.app.legal

import android.content.Context
import com.leakproof.app.R

data class LegalInfo(
    val publisher: String,
    val email: String,
    val effectiveDate: String,
    val version: Int
)

object Legal {
    fun load(context: Context): LegalInfo = LegalInfo(
        publisher = context.getString(R.string.publisher_name),
        email = context.getString(R.string.privacy_contact_email),
        effectiveDate = context.getString(R.string.policy_effective_date),
        version = context.resources.getInteger(R.integer.policy_version)
    )

    /** The policy with publisher, contact, date and version filled in. */
    fun policyText(context: Context): String {
        val template = context.assets.open("privacy_policy.md").bufferedReader().use { it.readText() }
        return PolicyText.render(template, load(context))
    }
}

object PolicyText {
    fun render(template: String, info: LegalInfo): String =
        template
            .replace("{{PUBLISHER}}", info.publisher)
            .replace("{{PRIVACY_EMAIL}}", info.email)
            .replace("{{EFFECTIVE_DATE}}", info.effectiveDate)
            .replace("{{POLICY_VERSION}}", info.version.toString())
}

/** Just enough Markdown for the policy: #, ##, "- " bullets, paragraphs and **bold**. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

data class MdSpan(val text: String, val bold: Boolean)

object SimpleMarkdown {

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
                paragraph.clear()
            }
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.startsWith("## ") -> { flush(); blocks.add(MdBlock.Heading(2, line.removePrefix("## ").trim())) }
                line.startsWith("# ") -> { flush(); blocks.add(MdBlock.Heading(1, line.removePrefix("# ").trim())) }
                line.startsWith("- ") -> { flush(); blocks.add(MdBlock.Bullet(line.removePrefix("- ").trim())) }
                else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(line.trim()) }
            }
        }
        flush()
        return blocks
    }

    /** Splits "a **b** c" into plain and bold spans. */
    fun spans(text: String): List<MdSpan> {
        val result = mutableListOf<MdSpan>()
        var bold = false
        for ((i, part) in text.split("**").withIndex()) {
            if (i > 0) bold = !bold
            if (part.isNotEmpty()) result.add(MdSpan(part, bold))
        }
        return result
    }
}
