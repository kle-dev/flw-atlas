package com.flowable.atlas.script

/**
 * GENERATED — the public instance methods of the engine *entities* the scope roots are bound to. The
 * catalog describes `execution`, `task`, `planItemInstance` and `caseInstance` by their delegate
 * interfaces, which is what completion advertises; but the object a script receives is the entity
 * itself — `VariableScopeResolver` binds the `ExecutionEntity` or `TaskEntity`,
 * `CmmnVariableScopeResolver` binds the `PlanItemInstanceEntity` and the `CaseInstanceEntity` it looks
 * up — and Groovy and JavaScript dispatch dynamically, so every public method of the entity works.
 * Without these, `execution.getRootProcessInstance()` or `caseInstance.setName(…)` read as a typo of
 * `getRootProcessInstanceId` / `getName`: 63 real methods were flagged.
 *
 * Accepted by the member-typo check, never advertised: these are the implementation's surface, and
 * most of it (`forceUpdate`, `setRevision`) is not something a script should be steered towards.
 *
 * Source: the union over Flowable 7.1.0.27 and 8.2.0.7 of every public, non-static method in the class
 * hierarchy of `ExecutionEntityImpl`, `TaskEntityImpl`, `PlanItemInstanceEntityImpl` and
 * `CaseInstanceEntityImpl` (`java.lang.Object`'s own left out).
 *
 * Regeneration: a throwaway `javap -public` walk over each class and its supertypes on the engine jars'
 * classpath, collecting `name(` tokens from the non-static member lines, unioned across versions.
 */
internal object ScriptEntityMembers {

    /** `ExecutionEntityImpl` — 203 methods. */
    val EXECUTION_ENTITY: Set<String> = setOf(
        "addChildExecution", "createVariablesLocal", "forceUpdate", "getActivityId", "getActivityName",
        "getBusinessKey", "getBusinessStatus", "getCachedElContext", "getCallbackId", "getCallbackType",
        "getClaimTime", "getClaimedBy", "getCurrentActivityId", "getCurrentActivityName", "getCurrentFlowElement",
        "getCurrentFlowableListener", "getDeadLetterJobCount", "getDeleteReason", "getDeploymentId",
        "getDescription", "getDueDate", "getEventName", "getEventSubscriptionCount", "getEventSubscriptions",
        "getExecutions", "getExternalWorkerJobCount", "getId", "getIdPrefix", "getIdentityLinkCount",
        "getIdentityLinks", "getIsActive", "getIsConcurrent", "getIsCountEnabled", "getIsEnded", "getIsEventScope",
        "getIsMultiInstanceRoot", "getIsScope", "getJobCount", "getLocalizedDescription", "getLocalizedName",
        "getLockOwner", "getLockTime", "getName", "getOriginalPersistentState", "getOriginatingCurrentFlowElement",
        "getParent", "getParentId", "getPersistentState", "getProcessDefinitionCategory", "getProcessDefinitionId",
        "getProcessDefinitionKey", "getProcessDefinitionName", "getProcessDefinitionVersion", "getProcessInstance",
        "getProcessInstanceBusinessKey", "getProcessInstanceBusinessStatus", "getProcessInstanceId",
        "getProcessVariables", "getPropagatedStageInstanceId", "getQueryVariables", "getReferenceId",
        "getReferenceType", "getRevision", "getRevisionNext", "getRootProcessInstance", "getRootProcessInstanceId",
        "getStartActivityId", "getStartTime", "getStartUserId", "getSubProcessInstance", "getSuperExecution",
        "getSuperExecutionId", "getSuspendedJobCount", "getSuspensionState", "getTaskCount", "getTenantId",
        "getTimerJobCount", "getTransientVariable", "getTransientVariableLocal", "getTransientVariables",
        "getTransientVariablesLocal", "getUsedVariablesCache", "getVariable", "getVariableCount",
        "getVariableInstance", "getVariableInstanceEntities", "getVariableInstanceLocal", "getVariableInstances",
        "getVariableInstancesLocal", "getVariableLocal", "getVariableNames", "getVariableNamesLocal",
        "getVariables", "getVariablesLocal", "hasVariable", "hasVariableLocal", "hasVariables", "hasVariablesLocal",
        "inactivate", "internalSetVariableInstances", "isActive", "isConcurrent", "isCountEnabled", "isDeleted",
        "isEnded", "isEventScope", "isInserted", "isMultiInstanceRoot", "isProcessInstanceType", "isScope",
        "isSuspended", "isUpdated", "removeTransientVariable", "removeTransientVariableLocal",
        "removeTransientVariables", "removeTransientVariablesLocal", "removeVariable", "removeVariableLocal",
        "removeVariables", "removeVariablesLocal", "setActive", "setActivityId", "setBusinessKey",
        "setBusinessStatus", "setCachedElContext", "setCallbackId", "setCallbackType", "setClaimTime",
        "setClaimedBy", "setConcurrent", "setCountEnabled", "setCurrentActivityName", "setCurrentFlowElement",
        "setCurrentFlowableListener", "setDeadLetterJobCount", "setDeleteReason", "setDeleted", "setDeploymentId",
        "setDescription", "setDueDate", "setEnded", "setEventName", "setEventScope", "setEventSubscriptionCount",
        "setExternalWorkerJobCount", "setId", "setIdentityLinkCount", "setInserted", "setIsActive",
        "setIsConcurrent", "setIsCountEnabled", "setIsEnded", "setIsEventScope", "setIsMultiInstanceRoot",
        "setIsScope", "setJobCount", "setLocalizedDescription", "setLocalizedName", "setLockOwner", "setLockTime",
        "setMultiInstanceRoot", "setName", "setOriginalPersistentState", "setOriginatingCurrentFlowElement",
        "setParent", "setParentId", "setProcessDefinitionCategory", "setProcessDefinitionId",
        "setProcessDefinitionKey", "setProcessDefinitionName", "setProcessDefinitionVersion", "setProcessInstance",
        "setProcessInstanceId", "setPropagatedStageInstanceId", "setQueryVariables", "setReferenceId",
        "setReferenceType", "setRevision", "setRootProcessInstance", "setRootProcessInstanceId", "setScope",
        "setStartActivityId", "setStartTime", "setStartUserId", "setSubProcessInstance", "setSuperExecution",
        "setSuperExecutionId", "setSuspendedJobCount", "setSuspensionState", "setTaskCount", "setTenantId",
        "setTimerJobCount", "setTransientVariable", "setTransientVariableLocal", "setTransientVariables",
        "setTransientVariablesLocal", "setUpdated", "setVariable", "setVariableCount", "setVariableLocal",
        "setVariables", "setVariablesLocal", "snapshotReadOnly",
    )

    /** `TaskEntityImpl` — 166 methods. */
    val TASK_ENTITY: Set<String> = setOf(
        "addCandidateGroup", "addCandidateGroups", "addCandidateUser", "addCandidateUsers", "addGroupIdentityLink",
        "addUserIdentityLink", "createVariablesLocal", "deleteCandidateGroup", "deleteCandidateUser",
        "deleteGroupIdentityLink", "deleteUserIdentityLink", "forceUpdate", "getAssignee", "getCachedElContext",
        "getCandidates", "getCaseVariables", "getCategory", "getClaimTime", "getClaimedBy", "getCreateTime",
        "getDelegationState", "getDelegationStateString", "getDescription", "getDueDate", "getEventHandlerId",
        "getEventName", "getExecutionId", "getFormKey", "getId", "getIdPrefix", "getIdentityLinkCount",
        "getIdentityLinks", "getInProgressStartDueDate", "getInProgressStartTime", "getInProgressStartedBy",
        "getIsCountEnabled", "getLocalizedDescription", "getLocalizedName", "getName", "getOriginalAssignee",
        "getOriginalPersistentState", "getOwner", "getParentTaskId", "getPersistentState", "getPriority",
        "getProcessDefinitionId", "getProcessInstanceId", "getProcessVariables", "getPropagatedStageInstanceId",
        "getQueryIdentityLinks", "getQueryVariables", "getRevision", "getRevisionNext", "getScopeDefinitionId",
        "getScopeId", "getScopeType", "getState", "getSubScopeId", "getSubTaskCount", "getSuspendedBy",
        "getSuspendedTime", "getSuspensionState", "getTaskDefinitionId", "getTaskDefinitionKey",
        "getTaskLocalVariables", "getTempCompletedBy", "getTenantId", "getTransientVariable",
        "getTransientVariableLocal", "getTransientVariables", "getTransientVariablesLocal", "getUsedVariablesCache",
        "getVariable", "getVariableCount", "getVariableInstance", "getVariableInstanceEntities",
        "getVariableInstanceLocal", "getVariableInstances", "getVariableInstancesLocal", "getVariableLocal",
        "getVariableNames", "getVariableNamesLocal", "getVariables", "getVariablesLocal", "hasVariable",
        "hasVariableLocal", "hasVariables", "hasVariablesLocal", "internalSetVariableInstances", "isCanceled",
        "isCountEnabled", "isDeleted", "isIdentityLinksInitialized", "isInserted", "isSuspended", "isUpdated",
        "removeTransientVariable", "removeTransientVariableLocal", "removeTransientVariables",
        "removeTransientVariablesLocal", "removeVariable", "removeVariableLocal", "removeVariables",
        "removeVariablesLocal", "setAssignee", "setAssigneeValue", "setCachedElContext", "setCanceled",
        "setCategory", "setClaimTime", "setClaimedBy", "setCountEnabled", "setCreateTime", "setDelegationState",
        "setDelegationStateString", "setDeleted", "setDescription", "setDueDate", "setEventHandlerId",
        "setEventName", "setExecutionId", "setFormKey", "setId", "setIdentityLinkCount",
        "setInProgressStartDueDate", "setInProgressStartTime", "setInProgressStartedBy", "setInserted",
        "setIsCountEnabled", "setLocalizedDescription", "setLocalizedName", "setName", "setOriginalPersistentState",
        "setOwner", "setOwnerValue", "setParentTaskId", "setPriority", "setProcessDefinitionId",
        "setProcessInstanceId", "setPropagatedStageInstanceId", "setQueryIdentityLinks", "setQueryVariables",
        "setRevision", "setScopeDefinitionId", "setScopeId", "setScopeType", "setState", "setSubScopeId",
        "setSubTaskCount", "setSuspendedBy", "setSuspendedTime", "setSuspensionState", "setTaskDefinitionId",
        "setTaskDefinitionKey", "setTempCompletedBy", "setTenantId", "setTransientVariable",
        "setTransientVariableLocal", "setTransientVariables", "setTransientVariablesLocal", "setUpdated",
        "setVariable", "setVariableCount", "setVariableLocal", "setVariables", "setVariablesLocal",
    )

    /** `PlanItemInstanceEntityImpl` — 156 methods. */
    val PLAN_ITEM_INSTANCE_ENTITY: Set<String> = setOf(
        "createVariablesLocal", "getAssignee", "getCachedElContext", "getCaseDefinitionId", "getCaseInstanceId",
        "getChildPlanItemInstances", "getCompletedBy", "getCompletedTime", "getCreateTime",
        "getCurrentFlowableListener", "getCurrentLifecycleListener", "getDerivedCaseDefinitionId", "getElementId",
        "getEndedTime", "getEntryCriterionId", "getExitCriterionId", "getExitTime", "getExtraValue",
        "getFailedTime", "getFormKey", "getId", "getIdPrefix", "getLastAvailableTime", "getLastDisabledTime",
        "getLastEnabledTime", "getLastStartedTime", "getLastSuspendedTime", "getLastUnavailableTime",
        "getLocalizedName", "getName", "getOccurredTime", "getOriginalPersistentState", "getParentVariableScope",
        "getPersistentState", "getPlanItem", "getPlanItemDefinition", "getPlanItemDefinitionId",
        "getPlanItemDefinitionType", "getPlanItemInstanceLocalVariables", "getPlanItems", "getQueryVariables",
        "getReferenceId", "getReferenceType", "getRevision", "getRevisionNext", "getSatisfiedSentryPartInstances",
        "getSentryPartInstanceCount", "getStageInstanceId", "getStagePlanItemInstance",
        "getStagePlanItemInstanceEntity", "getStartTime", "getStartUserId", "getState", "getTenantId",
        "getTerminatedTime", "getTransientVariable", "getTransientVariableLocal", "getTransientVariables",
        "getTransientVariablesLocal", "getUsedVariablesCache", "getVariable", "getVariableCount",
        "getVariableInstance", "getVariableInstanceEntities", "getVariableInstanceLocal", "getVariableInstances",
        "getVariableInstancesLocal", "getVariableLocal", "getVariableNames", "getVariableNamesLocal",
        "getVariables", "getVariablesLocal", "hasVariable", "hasVariableLocal", "hasVariables", "hasVariablesLocal",
        "internalSetVariableInstances", "isCompletable", "isCountEnabled", "isDeleted", "isInserted",
        "isPlannedForActivationInMigration", "isStage", "isStateChangeUnprocessed", "isUpdated",
        "removeTransientVariable", "removeTransientVariableLocal", "removeTransientVariables",
        "removeTransientVariablesLocal", "removeVariable", "removeVariableLocal", "removeVariables",
        "removeVariablesLocal", "setAssignee", "setCachedElContext", "setCaseDefinitionId", "setCaseInstanceId",
        "setChildPlanItemInstances", "setCompletable", "setCompletedBy", "setCompletedTime", "setCountEnabled",
        "setCreateTime", "setCurrentLifecycleListener", "setDeleted", "setDerivedCaseDefinitionId", "setElementId",
        "setEndedTime", "setEntryCriterionId", "setExitCriterionId", "setExitTime", "setExtraValue",
        "setFailedTime", "setFormKey", "setId", "setInserted", "setLastAvailableTime", "setLastDisabledTime",
        "setLastEnabledTime", "setLastStartedTime", "setLastSuspendedTime", "setLastUnavailableTime",
        "setLocalizedName", "setName", "setOccurredTime", "setOriginalPersistentState", "setPlanItem",
        "setPlanItemDefinitionId", "setPlanItemDefinitionType", "setPlannedForActivationInMigration",
        "setQueryVariables", "setReferenceId", "setReferenceType", "setRevision", "setSatisfiedSentryPartInstances",
        "setSentryPartInstanceCount", "setStage", "setStageInstanceId", "setStagePlanItemInstance", "setStartTime",
        "setStartUserId", "setState", "setStateChangeUnprocessed", "setTenantId", "setTerminatedTime",
        "setTransientVariable", "setTransientVariableLocal", "setTransientVariables", "setTransientVariablesLocal",
        "setUpdated", "setVariable", "setVariableCount", "setVariableLocal", "setVariables", "setVariablesLocal",
        "snapshotReadOnly",
    )

    /** `CaseInstanceEntityImpl` — 117 methods. */
    val CASE_INSTANCE_ENTITY: Set<String> = setOf(
        "createVariablesLocal", "getBusinessKey", "getBusinessStatus", "getCachedElContext", "getCallbackId",
        "getCallbackType", "getCaseDefinitionDeploymentId", "getCaseDefinitionId", "getCaseDefinitionKey",
        "getCaseDefinitionName", "getCaseDefinitionVersion", "getCaseVariables", "getChildPlanItemInstances",
        "getClaimTime", "getClaimedBy", "getDueDate", "getId", "getIdPrefix", "getLastReactivationTime",
        "getLastReactivationUserId", "getLocalizedName", "getLockOwner", "getLockTime", "getName",
        "getOriginalPersistentState", "getParentId", "getPersistentState", "getPlanItems", "getQueryVariables",
        "getReferenceId", "getReferenceType", "getRevision", "getRevisionNext", "getSatisfiedSentryPartInstances",
        "getStartTime", "getStartUserId", "getState", "getTenantId", "getTransientVariable",
        "getTransientVariableLocal", "getTransientVariables", "getTransientVariablesLocal", "getUsedVariablesCache",
        "getVariable", "getVariableInstance", "getVariableInstanceEntities", "getVariableInstanceLocal",
        "getVariableInstances", "getVariableInstancesLocal", "getVariableLocal", "getVariableNames",
        "getVariableNamesLocal", "getVariables", "getVariablesLocal", "hasVariable", "hasVariableLocal",
        "hasVariables", "hasVariablesLocal", "internalSetVariableInstances", "isCompletable", "isCompleteable",
        "isDeleted", "isInserted", "isUpdated", "removeTransientVariable", "removeTransientVariableLocal",
        "removeTransientVariables", "removeTransientVariablesLocal", "removeVariable", "removeVariableLocal",
        "removeVariables", "removeVariablesLocal", "setBusinessKey", "setBusinessStatus", "setCachedElContext",
        "setCallbackId", "setCallbackType", "setCaseDefinitionDeploymentId", "setCaseDefinitionId",
        "setCaseDefinitionKey", "setCaseDefinitionName", "setCaseDefinitionVersion", "setChildPlanItemInstances",
        "setClaimTime", "setClaimedBy", "setCompletable", "setCompleteable", "setDeleted", "setDueDate", "setId",
        "setInserted", "setLastReactivationTime", "setLastReactivationUserId", "setLocalizedName", "setLockOwner",
        "setLockTime", "setName", "setOriginalPersistentState", "setParentId", "setQueryVariables",
        "setReferenceId", "setReferenceType", "setRevision", "setSatisfiedSentryPartInstances", "setStartTime",
        "setStartUserId", "setState", "setTenantId", "setTransientVariable", "setTransientVariableLocal",
        "setTransientVariables", "setTransientVariablesLocal", "setUpdated", "setVariable", "setVariableLocal",
        "setVariables", "setVariablesLocal",
    )
}
