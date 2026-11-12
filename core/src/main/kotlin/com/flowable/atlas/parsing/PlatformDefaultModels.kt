package com.flowable.atlas.parsing

/**
 * GENERATED — the models the Flowable platform ships itself, by the reference kind that names them. A
 * project uses them without defining them: a copied `deactivate-user.action` keeps its
 * `"formKey": "F07-deactivateUser"`, a permission check calls `R01_defaultUserPermissions`. The runtime
 * resolves those keys, so a reference to one is a platform-provided external node, not a missing model —
 * and a `missingRefs` *error* there failed `--fail-on error` on a reference that works.
 *
 * Keyed by kind on purpose: the platform ships a *form* `F07-deactivateUser`, not a process, so a call
 * activity naming that key is still a missing process.
 *
 * Source: `src/main/resources/com/flowable/{forms,dmn,bpmn,template,document,action,policy}/default/` of
 * the platform's `modules/flowable-platform-default-models` and `modules/flowable-work-chat-default-models`,
 * plus the system `.event` files the platform and Engage palettes ship. The union across platform
 * versions: 3.15.x, 3.17.x, 2026.1 and main were compared, and main holds every key the others have.
 *
 * Regeneration: a throwaway script over those folders — a `.form`'s key is `metadata.key`; a `.tpl`,
 * `.document`, `.action` and `.policy`'s is the top-level `key`; a `.dmn` contributes every
 * `<decision id>`, a `.bpmn20.xml` every `<process id>`. A `.tplvariation` is a variation of a template
 * and has no key of its own.
 *
 * Public because the IDE's broken-key inspections ask the same question: otherwise the editor flags a key
 * the report resolves.
 */
object PlatformDefaultModels {

    val KEYS: Map<String, Set<String>> = mapOf(
        "form" to setOf(
            "A10_joinParticipantActionForm", "A10_joinParticipantMobileActionForm", "A11_dropParticipantActionForm",
            "A20_confirmJoinConversation", "D01_contactForm", "D02_policyForm", "D03_defaultGeneralDocumentEditForm",
            "F01_userInitFormDefault", "F02_userViewFormDefault", "F03_userEditFormDefault", "F04_userInitFormClient",
            "F05_userEditFormClient", "F06_userViewFormClient", "F07-deactivateUser", "F08_userInitFormClient",
            "F09_userSnoozeForm", "F50_conversationSelfInitForm", "F51_conversationDMInitForm",
            "F52_conversationGMInitForm", "TF_F01_editTemplateVariationForm", "TF_F05_detailTemplateVariationForm",
            "TM-P01-F1_Review_Change_Form", "TM_F02_parameterDetailSubform", "TM_F06_auditDetailSubform",
            "simpleFormKey",
        ),
        "decision" to setOf(
            "R01_defaultConversationPermissions", "R01_defaultUserPermissions", "R02_createConversationPermissions",
            "R03_archiveConversationPermissions", "R04_reopenConversationPermissions", "R05_inviteParticipantPermissions",
            "R06_joinParticipantPermissions", "R07_dropParticipantPermissions", "R08_postMessagePermissions",
            "R09_postVoiceMessagePermissions", "R10_postVideoMessagePermissions", "R11_postImagePermissions",
            "R12_postDocumentPermissions", "R13_editMessagePermissions",
        ),
        "process" to setOf("TM_P01_templateChangeApprovalProcess"),
        "template" to setOf(
            "confirmationMessage", "confirmationTitle", "demo-pdf-template-02", "demo-word-template-01",
            "demo-word-template-02", "flwNotificationBusinessStatusChanged", "flwNotificationIdentityLinkAdded",
            "flwNotificationIdentityLinkRemoved", "flwNotificationInstanceCancelled", "flwNotificationInstanceCompleted",
            "flwNotificationInstanceCreated", "flwNotificationMilestoneReached", "flwNotificationStageCompleted",
            "flwNotificationStageStarted", "flwNotificationTaskAssigned", "flwNotificationTaskCancelled",
            "flwNotificationTaskCompleted", "flwNotificationTaskCreated", "permissionError", "processMessage",
            "standardMessage", "unreadMessageNotificationEmail",
        ),
        "document" to setOf("contract", "general", "policy"),
        "action" to setOf(
            "agent-cmmn-ai-activated-planitem", "bpmn-cancel-process", "bpmn-complete-usertask", "bpmn-task-start-progress",
            "bpmn-test-action", "cmmn-cancel-case", "cmmn-complete-humantask", "cmmn-manual-activated-planitem",
            "cmmn-reactivate-case", "cmmn-task-start-progress", "cmmn-user-event-listener", "conversation-archive",
            "conversation-drop-participant", "conversation-join-participant", "conversation-mobile-join-participant",
            "create-task", "join-conversation-confirmation", "user-deactivate", "user-internal-deactivate",
            "user-internal-reactivate", "user-reactivate",
        ),
        "securityPolicy" to setOf("basic-security-policy", "basic-strict-security-policy"),
        "event" to setOf(
            "_flowableMailEvent",
            "_flowableEngageExternalMessageReceived",
            "_flowableEngageMessageReceivedNoAccount", "_flowableEngageMessageReceivedNoAccountAndTenant",
            "_flowableEngageMessageReceivedInactiveAccount",
            "_flowableEngageReactionReceivedNoAccount", "_flowableEngageReactionReceivedNoAccountAndTenant",
            "_flowableEngageReactionReceivedInactiveAccount",
        ),
    )

    /**
     * Whether the platform ships a model of [kind] — a model kind as the model index names it (`form`,
     * `decision`, `securityPolicy`; the reference resolver's `modelKind` normalises a ref's spelling) —
     * under [key]. A form key may name a page — the resolver's own fallback — so a page reference is
     * answered by the platform's forms too.
     */
    fun provides(kind: String, key: String): Boolean =
        key in KEYS[kind].orEmpty() || (kind == "page" && key in KEYS["form"].orEmpty())
}
