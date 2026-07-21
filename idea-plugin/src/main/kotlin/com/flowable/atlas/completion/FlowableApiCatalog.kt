package com.flowable.atlas.completion

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.model.ModelType.ACTION
import com.flowable.atlas.model.ModelType.AGENT
import com.flowable.atlas.model.ModelType.CASE
import com.flowable.atlas.model.ModelType.CHANNEL
import com.flowable.atlas.model.ModelType.DASHBOARD_COMPONENT
import com.flowable.atlas.model.ModelType.DATA_DICTIONARY
import com.flowable.atlas.model.ModelType.DATA_OBJECT
import com.flowable.atlas.model.ModelType.DECISION
import com.flowable.atlas.model.ModelType.EVENT
import com.flowable.atlas.model.ModelType.FORM
import com.flowable.atlas.model.ModelType.KNOWLEDGE_BASE
import com.flowable.atlas.model.ModelType.MASTER_DATA
import com.flowable.atlas.model.ModelType.PAGE
import com.flowable.atlas.model.ModelType.PROCESS
import com.flowable.atlas.model.ModelType.QUERY
import com.flowable.atlas.model.ModelType.SECURITY_POLICY
import com.flowable.atlas.model.ModelType.SEQUENCE
import com.flowable.atlas.model.ModelType.SERVICE
import com.flowable.atlas.model.ModelType.SLA
import com.flowable.atlas.model.ModelType.TEMPLATE
import com.flowable.atlas.model.ModelType.VARIABLE_EXTRACTOR

/**
 * The catalog of Flowable public-API positions where a String argument is a model key, an
 * operation, or a value field. Transcribed from the Flowable engine + platform public API
 * interfaces (org.flowable.* and com.flowable.*).
 *
 * This is the single version-sensitive artifact: adding support for new APIs / Flowable
 * versions means appending entries here.
 */
object FlowableApiCatalog {

    private val sites: List<ApiSite> = buildList {
        // ---------------------------------------------------------------- PROCESS
        key("org.flowable.engine.RuntimeService", "startProcessInstanceByKey", 0, PROCESS)
        key("org.flowable.engine.RuntimeService", "startProcessInstanceByKeyAndTenantId", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceBuilder", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceStartEventSubscriptionBuilder", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processDefinitionKeyLikeIgnoreCase", 0, PROCESS)
        // processInstanceBusinessKey(businessKey, processDefinitionKey) — key is arg 1
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processInstanceBusinessKey", 1, PROCESS)
        key("org.flowable.engine.runtime.ExecutionQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.runtime.ExecutionQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.engine.runtime.ExecutionQuery", "processDefinitionKeyLikeIgnoreCase", 0, PROCESS)
        key("org.flowable.engine.repository.ProcessDefinitionQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.repository.ProcessDefinitionQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.engine.history.HistoricProcessInstanceQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.history.HistoricProcessInstanceQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.engine.history.HistoricProcessInstanceQuery", "processDefinitionKeyLikeIgnoreCase", 0, PROCESS)
        // TaskInfoQuery is the base of TaskQuery + HistoricTaskInstanceQuery
        key("org.flowable.task.api.TaskInfoQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.task.api.TaskInfoQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.task.api.TaskInfoQuery", "processDefinitionKeyLikeIgnoreCase", 0, PROCESS)
        key("org.flowable.engine.RepositoryService", "suspendProcessDefinitionByKey", 0, PROCESS)
        key("org.flowable.engine.RepositoryService", "activateProcessDefinitionByKey", 0, PROCESS)

        // ---------------------------------------------------------------- CASE (cmmn)
        key("org.flowable.cmmn.api.runtime.CaseInstanceBuilder", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "caseDefinitionKeyLike", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "caseDefinitionKeyLikeIgnoreCase", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceStartEventSubscriptionBuilder", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.repository.CaseDefinitionQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.repository.CaseDefinitionQuery", "caseDefinitionKeyLike", 0, CASE)
        key("org.flowable.cmmn.api.history.HistoricCaseInstanceQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.history.HistoricCaseInstanceQuery", "caseDefinitionKeyLike", 0, CASE)
        key("org.flowable.cmmn.api.history.HistoricCaseInstanceQuery", "caseDefinitionKeyLikeIgnoreCase", 0, CASE)
        key("org.flowable.task.api.TaskInfoQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.task.api.TaskInfoQuery", "caseDefinitionKeyLikeIgnoreCase", 0, CASE)

        // ---------------------------------------------------------------- DECISION (dmn)
        key("org.flowable.dmn.api.ExecuteDecisionBuilder", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnDecisionQuery", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnDecisionQuery", "decisionKeyLike", 0, DECISION)
        key("org.flowable.dmn.api.DmnHistoricDecisionExecutionQuery", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnDeploymentQuery", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnDeploymentQuery", "decisionKeyLike", 0, DECISION)

        // ---------------------------------------------------------------- FORM
        key("org.flowable.form.api.FormRepositoryService", "getFormModelByKey", 0, FORM)
        key("org.flowable.form.api.FormRepositoryService", "getFormModelByKeyAndParentDeploymentId", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormModelWithVariablesByKey", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormModelWithVariablesByKeyAndParentDeploymentId", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormInstanceModelByKey", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormInstanceModelByKeyAndParentDeploymentId", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormInstanceModelByKeyAndScopeId", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormInstanceModelByKeyAndParentDeploymentIdAndScopeId", 0, FORM)
        key("org.flowable.form.api.FormDefinitionQuery", "formDefinitionKey", 0, FORM)
        key("org.flowable.form.api.FormDefinitionQuery", "formDefinitionKeyLike", 0, FORM)
        key("org.flowable.task.api.TaskInfoQuery", "taskFormKey", 0, FORM)

        // ---------------------------------------------------------------- EVENT + CHANNEL
        key("org.flowable.eventregistry.api.EventRepositoryService", "getEventModelByKey", 0, EVENT)
        key("org.flowable.eventregistry.api.EventRepositoryService", "getEventModelByKeyAndParentDeploymentId", 0, EVENT)
        key("org.flowable.eventregistry.api.EventDefinitionQuery", "eventDefinitionKey", 0, EVENT)
        key("org.flowable.eventregistry.api.EventDefinitionQuery", "eventDefinitionKeyLike", 0, EVENT)
        key("org.flowable.eventregistry.api.EventDefinitionQuery", "eventDefinitionKeyLikeIgnoreCase", 0, EVENT)
        key("org.flowable.eventregistry.api.EventRepositoryService", "getChannelModelByKey", 0, CHANNEL)
        key("org.flowable.eventregistry.api.EventRepositoryService", "getChannelModelByKeyAndParentDeploymentId", 0, CHANNEL)
        key("org.flowable.eventregistry.api.ChannelDefinitionQuery", "channelDefinitionKey", 0, CHANNEL)
        key("org.flowable.eventregistry.api.ChannelDefinitionQuery", "channelDefinitionKeyLike", 0, CHANNEL)
        key("org.flowable.eventregistry.api.ChannelDefinitionQuery", "channelDefinitionKeyLikeIgnoreCase", 0, CHANNEL)
        key("org.flowable.eventsubscription.api.EventSubscriptionQuery", "scopeDefinitionKey", 0, listOf(PROCESS, CASE))

        // ---------------------------------------------------------------- DATA OBJECT (platform)
        val doQuery = "com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerQuery"
        val doBuilder = "com.flowable.dataobject.api.runtime.DataObjectInstanceVariableContainerBuilder"
        val doMod = "com.flowable.dataobject.api.runtime.DataObjectModificationBuilder"
        val doDel = "com.flowable.dataobject.api.runtime.DataObjectDeletionBuilder"
        val doRuntime = "com.flowable.dataobject.api.runtime.DataObjectRuntimeService"
        key(doQuery, "definitionKey", 0, DATA_OBJECT)
        key(doBuilder, "definitionKey", 0, DATA_OBJECT)
        key(doMod, "definitionKey", 0, DATA_OBJECT)
        key(doDel, "definitionKey", 0, DATA_OBJECT)
        key("com.flowable.dataobject.api.runtime.DataObjectInstanceEntityQuery", "definitionKey", 0, DATA_OBJECT)
        key("com.flowable.dataobject.api.repository.DataObjectDefinitionQuery", "key", 0, DATA_OBJECT)
        key(doRuntime, "createDataObjectValueInstanceBuilderByDefinitionKey", 0, DATA_OBJECT)
        key(doRuntime, "createDataObjectValueInstanceBuilderByDefinitionKeyAndTenantId", 0, DATA_OBJECT)
        // methods where the data-object key is the 2nd argument
        key(doRuntime, "findDataObjectValueByLookupIdAndDefinitionKey", 1, DATA_OBJECT)
        key(doRuntime, "getIdentityLinksForDataObjectInstance", 1, DATA_OBJECT)
        key(doRuntime, "addUserIdentityLink", 1, DATA_OBJECT)
        key(doRuntime, "addGroupIdentityLink", 1, DATA_OBJECT)
        key(doRuntime, "deleteUserIdentityLink", 1, DATA_OBJECT)
        key(doRuntime, "deleteGroupIdentityLink", 1, DATA_OBJECT)

        // MASTER DATA
        val mdQuery = "com.flowable.dataobject.api.runtime.MasterDataInstanceQuery"
        key(mdQuery, "definitionKey", 0, MASTER_DATA)
        key(mdQuery, "key", 0, MASTER_DATA)
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceBuilder", "dataObjectDefinitionKey", 0, listOf(MASTER_DATA, DATA_OBJECT))
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceBuilder", "key", 0, MASTER_DATA)
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceImportBuilder", "dataObjectDefinitionKey", 0, listOf(MASTER_DATA, DATA_OBJECT))
        key("com.flowable.dataobject.api.repository.DataObjectDeploymentQuery", "dataObjectDefinitionKey", 0, DATA_OBJECT)

        // ---------------------------------------------------------------- SERVICE REGISTRY
        val svcInvoke = "com.flowable.serviceregistry.api.runtime.ServiceInvocationBuilder"
        key("com.flowable.serviceregistry.api.runtime.ServiceRegistryRuntimeService", "getLookupIdByServiceKey", 1, SERVICE)
        // getLookupIdByReferenceKey(data, referenceKey, tenantId) — a service's referenceKey names
        // the data object it backs (see ServiceModelReferenceExtractor), so offer data-object keys
        key("com.flowable.serviceregistry.api.runtime.ServiceRegistryRuntimeService", "getLookupIdByReferenceKey", 1, DATA_OBJECT)
        key(svcInvoke, "serviceKey", 0, SERVICE)
        key("com.flowable.serviceregistry.api.repository.ServiceRegistryRepositoryService", "getServiceDefinitionModelByKey", 0, SERVICE)
        key("com.flowable.serviceregistry.api.repository.ServiceRegistryRepositoryService", "getServiceDefinitionByKey", 0, SERVICE)
        key("com.flowable.serviceregistry.api.repository.ServiceDefinitionQuery", "key", 0, SERVICE)

        // ---------------------------------------------------------------- ACTION
        key("com.flowable.action.api.runtime.ActionInstanceBuilder", "actionDefinitionKey", 0, ACTION)
        key("com.flowable.action.api.runtime.ActionInstanceBuilder", "formKey", 0, FORM)
        key("com.flowable.action.api.runtime.ExecuteActionInstanceBuilder", "actionDefinitionKey", 0, ACTION)
        key("com.flowable.action.api.repository.ActionRepositoryService", "getActionDefinitionModelByKey", 0, ACTION)
        key("com.flowable.action.api.repository.ActionRepositoryService", "getActionDefinitionByKey", 0, ACTION)
        key("com.flowable.action.api.repository.ActionDefinitionQuery", "key", 0, ACTION)

        // ---------------------------------------------------------------- AGENT + KNOWLEDGE BASE
        val agentRepo = "com.flowable.agent.api.repository.AgentRepositoryService"
        key(agentRepo, "getAgentDefinitionByKeyAndTenant", 0, AGENT)
        key(agentRepo, "getAgentDefinitionModelByKeyAndTenant", 0, AGENT)
        key("com.flowable.agent.api.repository.AgentDefinitionQuery", "key", 0, AGENT)
        key("com.flowable.agent.api.runtime.AgentInstanceBuilder", "agentDefinitionKey", 0, AGENT)
        key("com.flowable.agent.api.runtime.AgentInstanceQuery", "agentDefinitionKey", 0, AGENT)
        key(agentRepo, "getKnowledgeBaseDefinitionModelByKeyAndTenant", 0, KNOWLEDGE_BASE)

        // ---------------------------------------------------------------- TEMPLATE
        val tmplService = "com.flowable.template.api.TemplateService"
        key(tmplService, "processTemplate", 0, TEMPLATE)
        // getActionTemplateVariations(titleTemplateKey, messageTemplateKey) — both are template keys
        key(tmplService, "getActionTemplateVariations", 0, TEMPLATE)
        key(tmplService, "getActionTemplateVariations", 1, TEMPLATE)
        key("com.flowable.template.api.runtime.TemplateProcessingBuilder", "templateKey", 0, TEMPLATE)
        key("com.flowable.template.api.repository.TemplateRepositoryService", "getLatestTemplateDefinitionModelByKey", 0, TEMPLATE)
        key("com.flowable.template.api.repository.TemplateRepositoryService", "getTemplateDefinitionModelToJson", 0, TEMPLATE)
        key("com.flowable.template.api.repository.TemplateDefinitionQuery", "key", 0, TEMPLATE)

        // ---------------------------------------------------------------- SECURITY POLICY
        key("com.flowable.policy.api.repository.PolicyRepositoryService", "getPolicyModelByKey", 0, SECURITY_POLICY)
        key("com.flowable.policy.api.repository.PolicyRepositoryService", "getPolicyDefinitionByKey", 0, SECURITY_POLICY)

        // ---------------------------------------------------------------- PLATFORM REPOSITORY (multi-model)
        val platRepo = "com.flowable.platform.api.repository.PlatformRepositoryService"
        key(platRepo, "getQueryDefinitionModelByKey", 0, QUERY)
        key(platRepo, "getQueryDefinitionByKey", 0, QUERY)
        key(platRepo, "getVariableExtractorDefinitionModelByKey", 0, VARIABLE_EXTRACTOR)
        key(platRepo, "getVariableExtractorDefinitionByKey", 0, VARIABLE_EXTRACTOR)
        key(platRepo, "getSequenceDefinitionByKey", 0, SEQUENCE)
        key(platRepo, "getSequenceDefinitionModelByKey", 0, SEQUENCE)
        key(platRepo, "getSlaDefinitionByKey", 0, SLA)
        key(platRepo, "getSlaDefinitionModelByKey", 0, SLA)
        key(platRepo, "getDashboardComponentDefinitionModelByKey", 0, DASHBOARD_COMPONENT)
        key(platRepo, "getDashboardComponentDefinitionByKey", 0, DASHBOARD_COMPONENT)
        key(platRepo, "getDataDictionaryModelByKey", 0, DATA_DICTIONARY)
        key(platRepo, "getDataDictionaryDefinitionByKey", 0, DATA_DICTIONARY)
        // Platform definition query builders (each has key(String))
        key("com.flowable.platform.api.repository.QueryDefinitionQuery", "key", 0, QUERY)
        key("com.flowable.platform.api.repository.VariableExtractorDefinitionQuery", "key", 0, VARIABLE_EXTRACTOR)
        key("com.flowable.platform.api.repository.SequenceDefinitionQuery", "key", 0, SEQUENCE)
        key("com.flowable.platform.api.sequence.SequenceGenerator", "definitionKey", 0, SEQUENCE)
        key("com.flowable.platform.api.repository.SlaDefinitionQuery", "key", 0, SLA)
        key("com.flowable.platform.api.repository.DashboardComponentDefinitionQuery", "key", 0, DASHBOARD_COMPONENT)
        key("com.flowable.platform.api.repository.datadictionary.DataDictionaryDefinitionQuery", "key", 0, DATA_DICTIONARY)
        // Work definition (unified bpmn+cmmn) — suggest both process and case keys
        key("com.flowable.platform.api.work.WorkInstanceInfoQuery", "definitionKey", 0, listOf(PROCESS, CASE))

        // ---------------------------------------------------------------- PAGE (core-app)
        key("com.flowable.core.app.api.PageDefinitionQuery", "key", 0, PAGE)

        // ---------------------------------------------------------------- OPERATION positions (cascade)
        operation(doQuery, "operation", keyMethod = "definitionKey")
        operation(doBuilder, "operation", keyMethod = "definitionKey")
        operation(doMod, "operation", keyMethod = "definitionKey")
        operation(doDel, "operation", keyMethod = "definitionKey")
        // delete(operationKey) takes the operation key directly as a terminal argument, bypassing
        // the fluent .operation(...) builder call — same candidate set, different call shape.
        operation(doDel, "delete", keyMethod = "definitionKey")
        operation(svcInvoke, "operationKey", keyMethod = "serviceKey", keyIsService = true)

        // ---------------------------------------------------------------- VALUE-FIELD positions (cascade)
        value(doQuery, "value", keyMethod = "definitionKey", operationMethod = "operation")
        value(doBuilder, "value", keyMethod = "definitionKey", operationMethod = "operation")
        value(doMod, "value", keyMethod = "definitionKey", operationMethod = "operation")
        value(doMod, "originalValue", keyMethod = "definitionKey", operationMethod = "operation")
        value(doDel, "value", keyMethod = "definitionKey", operationMethod = "operation")

        // ---------------------------------------------------------------- MESSAGE / SIGNAL names
        val runtime = "org.flowable.engine.RuntimeService"
        vocab(runtime, "startProcessInstanceByMessage", 0, Vocabulary.MESSAGE)
        vocab(runtime, "startProcessInstanceByMessageAndTenantId", 0, Vocabulary.MESSAGE)
        vocab(runtime, "messageEventReceived", 0, Vocabulary.MESSAGE)
        vocab(runtime, "messageEventReceivedAsync", 0, Vocabulary.MESSAGE)
        vocab("org.flowable.engine.runtime.ProcessInstanceBuilder", "messageName", 0, Vocabulary.MESSAGE)
        vocab(runtime, "signalEventReceived", 0, Vocabulary.SIGNAL)
        vocab(runtime, "signalEventReceivedAsync", 0, Vocabulary.SIGNAL)
        vocab(runtime, "signalEventReceivedWithTenantId", 0, Vocabulary.SIGNAL)
        vocab(runtime, "signalEventReceivedAsyncWithTenantId", 0, Vocabulary.SIGNAL)

        // ---------------------------------------------------------------- VARIABLE names (arg index 1)
        // getVariable(executionId, variableName) etc. — the id is arg 0, the variable name is arg 1.
        val taskService = "org.flowable.engine.TaskService"
        val cmmnRuntime = "org.flowable.cmmn.api.CmmnRuntimeService"
        val cmmnTaskService = "org.flowable.cmmn.api.CmmnTaskService"
        for (host in listOf(runtime, taskService, cmmnRuntime, cmmnTaskService)) {
            for (m in listOf("getVariable", "getVariableLocal", "setVariable", "setVariableLocal",
                             "hasVariable", "hasVariableLocal", "removeVariable", "removeVariableLocal")) {
                vocab(host, m, 1, Vocabulary.VARIABLE)
            }
        }

        // ---------------------------------------------------------------- FORM OUTCOMES
        // completeTaskWithForm(taskId, formDefinitionId, outcome, …) — the outcome literal is arg 2;
        // offered as the project-wide union of all form outcome values.
        vocab(taskService, "completeTaskWithForm", 2, Vocabulary.OUTCOME)
        vocab(cmmnTaskService, "completeTaskWithForm", 2, Vocabulary.OUTCOME)

        // ---------------------------------------------------------------- TASK-DEFINITION KEY / ACTIVITY ID
        // Scoped to the sibling processDefinitionKey / caseDefinitionKey in the same query chain when
        // present (→ only that model's task/activity ids), else the project-wide union.
        val procCaseScope = listOf("processDefinitionKey", "caseDefinitionKey")
        val procScope = listOf("processDefinitionKey")
        val caseScope = listOf("caseDefinitionKey")
        vocab("org.flowable.task.api.TaskInfoQuery", "taskDefinitionKey", 0, Vocabulary.USER_TASK, procCaseScope, listOf(PROCESS, CASE))
        vocab("org.flowable.task.api.TaskInfoQuery", "taskDefinitionKeyLike", 0, Vocabulary.USER_TASK, procCaseScope, listOf(PROCESS, CASE))
        vocab("org.flowable.engine.runtime.ExecutionQuery", "activityId", 0, Vocabulary.ACTIVITY, procScope, listOf(PROCESS))
        vocab("org.flowable.engine.history.HistoricActivityInstanceQuery", "activityId", 0, Vocabulary.ACTIVITY, procScope, listOf(PROCESS))
        // CMMN plan-item ids reuse the same ACTIVITY vocabulary (ModelMemberExtractor collects
        // plan-item local names for ModelType.CASE too). CaseInstanceQuery carries a sibling
        // caseDefinitionKey to scope by; PlanItemInstanceQuery/HistoricPlanItemInstanceQuery only
        // carry runtime ids, so those fall back to the project-wide union.
        vocab("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "activePlanItemDefinitionId", 0, Vocabulary.ACTIVITY, caseScope, listOf(CASE))
        vocab("org.flowable.cmmn.api.runtime.PlanItemInstanceQuery", "planItemDefinitionId", 0, Vocabulary.ACTIVITY)
        vocab("org.flowable.cmmn.api.history.HistoricPlanItemInstanceQuery", "planItemInstanceDefinitionId", 0, Vocabulary.ACTIVITY)

        // ---------------------------------------------------------------- VARIABLE-VALUE FILTERS
        // variableValueEquals(name, value) and its Equals/NotEquals/GreaterThan/LessThan/Like
        // siblings exist (with process-/case-/task- prefixed variants) across nearly every runtime
        // and historic query builder. All reuse Vocabulary.VARIABLE; scoped to the sibling
        // processDefinitionKey/caseDefinitionKey when present, else the project-wide union.
        val eqFamily = listOf(
            "variableValueEquals", "variableValueEqualsIgnoreCase",
            "variableValueNotEquals", "variableValueNotEqualsIgnoreCase",
            "variableValueGreaterThan", "variableValueGreaterThanOrEqual",
            "variableValueLessThan", "variableValueLessThanOrEqual",
            "variableValueLike", "variableValueLikeIgnoreCase",
        )
        val eqFamilyNoCompare = listOf(
            "variableValueEquals", "variableValueNotEquals",
            "variableValueLike", "variableValueLikeIgnoreCase",
        )
        for (m in eqFamily) {
            val cap = m.replaceFirstChar { it.uppercase() }
            vocab("org.flowable.engine.runtime.ProcessInstanceQuery", m, 0, Vocabulary.VARIABLE, procScope, listOf(PROCESS))
            vocab("org.flowable.engine.runtime.ExecutionQuery", m, 0, Vocabulary.VARIABLE, procScope, listOf(PROCESS))
            vocab("org.flowable.engine.runtime.ExecutionQuery", "process$cap", 0, Vocabulary.VARIABLE, procScope, listOf(PROCESS))
            vocab("org.flowable.engine.history.HistoricProcessInstanceQuery", m, 0, Vocabulary.VARIABLE, procScope, listOf(PROCESS))
            vocab("org.flowable.engine.history.HistoricProcessInstanceQuery", "local$cap", 0, Vocabulary.VARIABLE, procScope, listOf(PROCESS))
            vocab("org.flowable.cmmn.api.runtime.CaseInstanceQuery", m, 0, Vocabulary.VARIABLE, caseScope, listOf(CASE))
            vocab("org.flowable.cmmn.api.history.HistoricCaseInstanceQuery", m, 0, Vocabulary.VARIABLE, caseScope, listOf(CASE))
            vocab("org.flowable.task.api.TaskInfoQuery", "task$cap", 0, Vocabulary.VARIABLE, procCaseScope, listOf(PROCESS, CASE))
            vocab("org.flowable.task.api.TaskInfoQuery", "process$cap", 0, Vocabulary.VARIABLE, procScope, listOf(PROCESS))
            vocab("org.flowable.task.api.TaskInfoQuery", "case$cap", 0, Vocabulary.VARIABLE, caseScope, listOf(CASE))
        }
        // No definitionKey sibling exists on these (scoped by runtime processInstanceId/executionId/
        // taskId/caseInstanceId instead) — always the project-wide union.
        for (fqn in listOf(
            "org.flowable.variable.api.history.HistoricVariableInstanceQuery",
            "org.flowable.cmmn.api.history.HistoricVariableInstanceQuery",
            "org.flowable.variable.api.runtime.VariableInstanceQuery",
            "org.flowable.cmmn.api.runtime.VariableInstanceQuery",
        )) {
            vocab(fqn, "variableName", 0, Vocabulary.VARIABLE)
            vocab(fqn, "variableNameLike", 0, Vocabulary.VARIABLE)
            for (m in eqFamilyNoCompare) vocab(fqn, m, 0, Vocabulary.VARIABLE)
        }

        // ---------------------------------------------------------------- MEMBER positions (cascade)
        // DMN: variable(name, value) offers the decision's input/output variables, resolved from
        // the sibling decisionKey(...).
        member("org.flowable.dmn.api.ExecuteDecisionBuilder", "variable", 0,
            keyMethod = "decisionKey", memberKind = MemberKind.DECISION_VARIABLE)
        // Master data: variableValueEquals/...Like(name, value) offers the definition's field
        // names, resolved from the sibling definitionKey(...) via the same .data fieldMappings
        // already used for Liquibase coverage / bean generation.
        for (m in listOf("variableValueEquals", "variableValueEqualsIgnoreCase", "variableValueLike", "variableValueLikeIgnoreCase")) {
            member(mdQuery, m, 0, keyMethod = "definitionKey", memberKind = MemberKind.MASTER_DATA_FIELD)
        }
    }

    private val byMethodName: Map<String, List<ApiSite>> = sites.groupBy { it.methodName }

    fun sitesForMethod(methodName: String): List<ApiSite> = byMethodName[methodName].orEmpty()

    // ---- builder helpers ----

    private fun MutableList<ApiSite>.key(fqn: String, method: String, argIndex: Int, type: ModelType) =
        add(KeySite(fqn, method, argIndex, listOf(type)))

    private fun MutableList<ApiSite>.key(fqn: String, method: String, argIndex: Int, types: List<ModelType>) =
        add(KeySite(fqn, method, argIndex, types))

    private fun MutableList<ApiSite>.operation(fqn: String, method: String, keyMethod: String, keyIsService: Boolean = false) =
        add(OperationSite(fqn, method, 0, keyMethod, keyIsService))

    private fun MutableList<ApiSite>.value(fqn: String, method: String, keyMethod: String, operationMethod: String, keyIsService: Boolean = false) =
        add(ValueSite(fqn, method, 0, keyMethod, operationMethod, keyIsService))

    private fun MutableList<ApiSite>.vocab(
        fqn: String,
        method: String,
        argIndex: Int,
        vocabulary: Vocabulary,
        scopeKeyMethods: List<String> = emptyList(),
        scopeTypes: List<ModelType> = emptyList(),
    ) = add(VocabularySite(fqn, method, argIndex, vocabulary, scopeKeyMethods, scopeTypes))

    private fun MutableList<ApiSite>.member(fqn: String, method: String, argIndex: Int, keyMethod: String, memberKind: MemberKind, keyArgIndex: Int = 0) =
        add(MemberSite(fqn, method, argIndex, keyMethod, keyArgIndex, memberKind))
}
