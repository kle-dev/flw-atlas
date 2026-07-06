package com.flowable.keys.completion

import com.flowable.keys.model.ModelType
import com.flowable.keys.model.ModelType.ACTION
import com.flowable.keys.model.ModelType.AGENT
import com.flowable.keys.model.ModelType.CASE
import com.flowable.keys.model.ModelType.CHANNEL
import com.flowable.keys.model.ModelType.DASHBOARD_COMPONENT
import com.flowable.keys.model.ModelType.DATA_DICTIONARY
import com.flowable.keys.model.ModelType.DATA_OBJECT
import com.flowable.keys.model.ModelType.DECISION
import com.flowable.keys.model.ModelType.EVENT
import com.flowable.keys.model.ModelType.FORM
import com.flowable.keys.model.ModelType.KNOWLEDGE_BASE
import com.flowable.keys.model.ModelType.MASTER_DATA
import com.flowable.keys.model.ModelType.PAGE
import com.flowable.keys.model.ModelType.PROCESS
import com.flowable.keys.model.ModelType.QUERY
import com.flowable.keys.model.ModelType.SECURITY_POLICY
import com.flowable.keys.model.ModelType.SEQUENCE
import com.flowable.keys.model.ModelType.SERVICE
import com.flowable.keys.model.ModelType.SLA
import com.flowable.keys.model.ModelType.TEMPLATE
import com.flowable.keys.model.ModelType.VARIABLE_EXTRACTOR

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
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processDefinitionKeyLikeIgnoreCase", 0, PROCESS)
        // processInstanceBusinessKey(businessKey, processDefinitionKey) — key is arg 1
        key("org.flowable.engine.runtime.ProcessInstanceQuery", "processInstanceBusinessKey", 1, PROCESS)
        key("org.flowable.engine.runtime.ExecutionQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.repository.ProcessDefinitionQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.engine.repository.ProcessDefinitionQuery", "processDefinitionKeyLike", 0, PROCESS)
        key("org.flowable.engine.history.HistoricProcessInstanceQuery", "processDefinitionKey", 0, PROCESS)
        // TaskInfoQuery is the base of TaskQuery + HistoricTaskInstanceQuery
        key("org.flowable.task.api.TaskInfoQuery", "processDefinitionKey", 0, PROCESS)
        key("org.flowable.task.api.TaskInfoQuery", "processDefinitionKeyLike", 0, PROCESS)

        // ---------------------------------------------------------------- CASE (cmmn)
        key("org.flowable.cmmn.api.runtime.CaseInstanceBuilder", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "caseDefinitionKeyLike", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceQuery", "caseDefinitionKeyLikeIgnoreCase", 0, CASE)
        key("org.flowable.cmmn.api.runtime.CaseInstanceStartEventSubscriptionBuilder", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.repository.CaseDefinitionQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.cmmn.api.history.HistoricCaseInstanceQuery", "caseDefinitionKey", 0, CASE)
        key("org.flowable.task.api.TaskInfoQuery", "caseDefinitionKey", 0, CASE)

        // ---------------------------------------------------------------- DECISION (dmn)
        key("org.flowable.dmn.api.ExecuteDecisionBuilder", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnDecisionQuery", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnHistoricDecisionExecutionQuery", "decisionKey", 0, DECISION)
        key("org.flowable.dmn.api.DmnDeploymentQuery", "decisionKey", 0, DECISION)

        // ---------------------------------------------------------------- FORM
        key("org.flowable.form.api.FormRepositoryService", "getFormModelByKey", 0, FORM)
        key("org.flowable.form.api.FormRepositoryService", "getFormModelByKeyAndParentDeploymentId", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormModelWithVariablesByKey", 0, FORM)
        key("org.flowable.form.api.FormService", "getFormInstanceModelByKey", 0, FORM)
        key("org.flowable.form.api.FormDefinitionQuery", "formDefinitionKey", 0, FORM)
        key("org.flowable.task.api.TaskInfoQuery", "taskFormKey", 0, FORM)

        // ---------------------------------------------------------------- EVENT + CHANNEL
        key("org.flowable.eventregistry.api.EventRepositoryService", "getEventModelByKey", 0, EVENT)
        key("org.flowable.eventregistry.api.EventDefinitionQuery", "eventDefinitionKey", 0, EVENT)
        key("org.flowable.eventregistry.api.EventRepositoryService", "getChannelModelByKey", 0, CHANNEL)
        key("org.flowable.eventregistry.api.ChannelDefinitionQuery", "channelDefinitionKey", 0, CHANNEL)

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
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceQuery", "definitionKey", 0, MASTER_DATA)
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceQuery", "key", 0, MASTER_DATA)
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceBuilder", "dataObjectDefinitionKey", 0, listOf(MASTER_DATA, DATA_OBJECT))
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceBuilder", "key", 0, MASTER_DATA)
        key("com.flowable.dataobject.api.runtime.MasterDataInstanceImportBuilder", "dataObjectDefinitionKey", 0, listOf(MASTER_DATA, DATA_OBJECT))
        key("com.flowable.dataobject.api.repository.DataObjectDeploymentQuery", "dataObjectDefinitionKey", 0, DATA_OBJECT)

        // ---------------------------------------------------------------- SERVICE REGISTRY
        val svcInvoke = "com.flowable.serviceregistry.api.runtime.ServiceInvocationBuilder"
        key("com.flowable.serviceregistry.api.runtime.ServiceRegistryRuntimeService", "getLookupIdByServiceKey", 1, SERVICE)
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
        operation(svcInvoke, "operationKey", keyMethod = "serviceKey", keyIsService = true)

        // ---------------------------------------------------------------- VALUE-FIELD positions (cascade)
        value(doQuery, "value", keyMethod = "definitionKey", operationMethod = "operation")
        value(doBuilder, "value", keyMethod = "definitionKey", operationMethod = "operation")
        value(doMod, "value", keyMethod = "definitionKey", operationMethod = "operation")
        value(doDel, "value", keyMethod = "definitionKey", operationMethod = "operation")

        // ---------------------------------------------------------------- MESSAGE / SIGNAL names
        val runtime = "org.flowable.engine.RuntimeService"
        vocab(runtime, "startProcessInstanceByMessage", 0, Vocabulary.MESSAGE)
        vocab(runtime, "startProcessInstanceByMessageAndTenantId", 0, Vocabulary.MESSAGE)
        vocab(runtime, "messageEventReceived", 0, Vocabulary.MESSAGE)
        vocab("org.flowable.engine.runtime.ProcessInstanceBuilder", "messageName", 0, Vocabulary.MESSAGE)
        vocab(runtime, "signalEventReceived", 0, Vocabulary.SIGNAL)

        // ---------------------------------------------------------------- VARIABLE names (arg index 1)
        // getVariable(executionId, variableName) etc. — the id is arg 0, the variable name is arg 1.
        val taskService = "org.flowable.engine.TaskService"
        val cmmnRuntime = "org.flowable.cmmn.api.CmmnRuntimeService"
        for (host in listOf(runtime, taskService, cmmnRuntime)) {
            for (m in listOf("getVariable", "getVariableLocal", "setVariable", "setVariableLocal",
                             "hasVariable", "hasVariableLocal", "removeVariable", "removeVariableLocal")) {
                vocab(host, m, 1, Vocabulary.VARIABLE)
            }
        }

        // ---------------------------------------------------------------- TASK-DEFINITION KEY / ACTIVITY ID
        vocab("org.flowable.task.api.TaskInfoQuery", "taskDefinitionKey", 0, Vocabulary.USER_TASK)
        vocab("org.flowable.task.api.TaskInfoQuery", "taskDefinitionKeyLike", 0, Vocabulary.USER_TASK)
        vocab("org.flowable.engine.runtime.ExecutionQuery", "activityId", 0, Vocabulary.ACTIVITY)
        vocab("org.flowable.engine.history.HistoricActivityInstanceQuery", "activityId", 0, Vocabulary.ACTIVITY)

        // ---------------------------------------------------------------- MEMBER positions (cascade)
        // DMN: variable(name, value) offers the decision's input/output variables, resolved from
        // the sibling decisionKey(...).
        member("org.flowable.dmn.api.ExecuteDecisionBuilder", "variable", 0,
            keyMethod = "decisionKey", memberKind = MemberKind.DECISION_VARIABLE)
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

    private fun MutableList<ApiSite>.vocab(fqn: String, method: String, argIndex: Int, vocabulary: Vocabulary) =
        add(VocabularySite(fqn, method, argIndex, vocabulary))

    private fun MutableList<ApiSite>.member(fqn: String, method: String, argIndex: Int, keyMethod: String, memberKind: MemberKind, keyArgIndex: Int = 0) =
        add(MemberSite(fqn, method, argIndex, keyMethod, keyArgIndex, memberKind))
}
