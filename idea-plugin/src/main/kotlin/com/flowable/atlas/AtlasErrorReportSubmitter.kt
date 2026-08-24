package com.flowable.atlas

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.datatransfer.StringSelection

/**
 * Turns the IDE's "an internal error occurred" dialog into something actionable for an Atlas bug.
 *
 * Without an [ErrorReportSubmitter] the dialog only offers JetBrains' own reporter, which discards
 * third-party plugin exceptions — so an Atlas stack trace reached nobody who could fix it, and the user
 * had no way to hand it over short of digging `idea.log` out of Help › Show Log.
 *
 * ## Why clipboard + browser rather than an HTTP POST
 * There is no Atlas report endpoint to POST to, and inventing one would mean shipping a plugin that
 * silently uploads stack traces. A stack trace from this plugin can carry model keys, file paths and
 * expression text out of a customer project, so it must not leave the machine without the user seeing
 * it first. This builds the report, puts it on the clipboard, and opens the issue tracker: the user
 * reviews and pastes. Nothing is transmitted by the plugin itself — which is also what
 * [getPrivacyNoticeText] promises.
 */
class AtlasErrorReportSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Report Flowable Atlas Problem…"

    override fun getPrivacyNoticeText(): String =
        "The report is copied to your clipboard and the issue tracker opens in your browser — " +
            "Atlas sends nothing on its own. Review the text before pasting: a stack trace can contain " +
            "model keys, file paths and expression text from your project."

    override fun submit(
        events: Array<IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        CopyPasteManager.getInstance().setContents(StringSelection(buildReport(events, additionalInfo)))
        BrowserUtil.browse(ISSUE_URL)
        // NEW_ISSUE, not FAILED: from the dialog's point of view the hand-off succeeded — the report is
        // on the clipboard and the tracker is open. Whether the user actually pastes is out of our hands.
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    /**
     * The environment block a maintainer always has to ask for otherwise, then the traces. Deliberately
     * says whether the running IDE is inside the verified range ([AtlasPlatformSupport]) — "works on an
     * untested platform branch" is the single most common explanation for an inexplicable report.
     */
    private fun buildReport(events: Array<IdeaLoggingEvent>, additionalInfo: String?): String = buildString {
        val appInfo = ApplicationInfo.getInstance()
        appendLine("### Environment")
        appendLine("- Atlas: ${pluginDescriptor?.version ?: AtlasBuildInfo.VERSION}")
        appendLine("- IDE: ${appInfo.fullApplicationName} (build ${appInfo.build.asString()})")
        appendLine("- Platform support: ${AtlasPlatformSupport.compatibilityNote()}")
        appendLine("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("- JRE: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        appendLine()

        appendLine("### What I was doing")
        appendLine(additionalInfo?.takeIf { it.isNotBlank() } ?: "_(not provided)_")
        appendLine()

        // Numbered only when there is more than one, so the common single-exception report reads cleanly.
        events.forEachIndexed { i, event ->
            appendLine("### Exception${if (events.size > 1) " ${i + 1} of ${events.size}" else ""}")
            event.message?.takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
            appendLine("```")
            appendLine(event.throwableText.trimEnd())
            appendLine("```")
            appendLine()
        }
    }

    private companion object {
        const val ISSUE_URL = "https://github.com/kle-dev/flw-atlas/issues/new"
    }
}
