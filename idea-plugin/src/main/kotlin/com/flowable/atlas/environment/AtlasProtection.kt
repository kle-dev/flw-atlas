package com.flowable.atlas.environment

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import javax.swing.JComponent

/**
 * The guard on an environment marked *Protected*.
 *
 * Two rules that are not negotiable:
 *
 * **Protection follows the URL, not the pointer.** The playground lets the user type an arbitrary base
 * URL, so a guard keyed on the *selected* connection would be bypassed by typing one. [protecting]
 * looks the URL up across the whole catalog instead, so the guard cannot be walked around by accident.
 *
 * **Confirm before queueing background work, never inside it.** Both entry points are already on the
 * EDT and resolving a target touches neither keychain nor network, so the question is asked inline —
 * no `invokeAndWait` from a pooled thread, no modality juggling, and no dialog appearing from under a
 * progress indicator. Both confirmations take an injectable lambda so the paths stay headless-testable.
 *
 * There is deliberately **no "don't ask again"**. A guard you click through once and never see again is
 * not a guard; what makes it cheap instead is that the evaluation prompt is non-modal with *Cancel*
 * preselected, so declining is one keystroke — and the lock icon stays on the control the whole time,
 * so the environment is readable without any dialog at all.
 */
object AtlasProtection {

    /** The connection [baseUrl] actually addresses, whatever the project's pointer says. */
    fun protecting(baseUrl: String, kind: ConnectionKind, all: List<AtlasConnection>): AtlasConnection? =
        all.firstOrNull { it.kind == kind && it.requiresConfirmation && BaseUrls.sameUrl(kind, it.baseUrl, baseUrl) }

    /** True when [connection] is in a protected environment. Non-null on purpose: "unresolved" is not
     *  a state this may be asked about, so treating it as unprotected is a compile error, not a policy. */
    fun requiresConfirmation(connection: AtlasConnection): Boolean = connection.requiresConfirmation

    fun pullMessage(connection: AtlasConnection, targetFolder: String): String =
        "${connection.environmentName} is marked protected.\n\n" +
            "Pulling replaces the app archives under $targetFolder/ with ${connection.environmentName}'s models."

    fun evaluateMessage(connection: AtlasConnection): String =
        "Evaluate against ${connection.environmentName}? It is marked protected, and evaluating an " +
            "expression can call bean methods on the running app."

    /**
     * A pull overwrites files in the repository, so it asks modally — the weight matches the
     * consequence. EDT only.
     */
    fun confirmPull(project: Project, connection: AtlasConnection, targetFolder: String): Boolean =
        MessageDialogBuilder.yesNo("Pull from Flowable Design", pullMessage(connection, targetFolder))
            .yesText("Pull from ${connection.environmentName}")
            .noText("Cancel")
            .asWarning()
            .ask(project)

    /**
     * An evaluation changes nothing on disk, so it asks with a non-modal confirmation anchored at the
     * control — with *Cancel* preselected, so a reflexive Enter declines. EDT only.
     */
    fun confirmEvaluate(connection: AtlasConnection, near: JComponent, onConfirmed: () -> Unit) {
        JBPopupFactory.getInstance()
            .createConfirmation(
                evaluateMessage(connection),
                "Evaluate",
                "Cancel",
                onConfirmed,
                /* defaultOptionIndex = */ 1,
            )
            .show(RelativePoint.getSouthWestOf(near))
    }
}
