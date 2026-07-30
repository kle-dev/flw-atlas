package com.flowable.atlas.script

/**
 * GENERATED — the public method surfaces of the Flowable **platform** services that scripts reach
 * as Spring beans (member name → parameter names). In a Work installation the script `beans` map
 * is the whole ApplicationContext (`SpringProcessEngineConfiguration.initBeans()` →
 * `SpringBeanFactoryProxyMap`), so every bean below resolves by its name in BPMN/CMMN scripts and
 * action bots via the engine's `BeansResolverFactory`. Caveat: with
 * `flowable.sandbox.expression.strict-mode=true` (default off) only `@AllowedBeanInStrictMode`
 * beans resolve.
 *
 * Source: the local enterprise checkout `flowable-platform` (2026.2.0-SNAPSHOT, extracted
 * 2026-07-30) plus the OSS base interfaces the platform ones extend (`ContentService`,
 * `IdmIdentityService`, `FormService`, `FormRepositoryService` from `flowable-engine`).
 * Overloads are merged into the richest signature.
 *
 * Regeneration: same throwaway approach as [ScriptServiceApis] — per target a LIST of interface
 * sources (base interfaces merged in), comments stripped, `name(params);` declarations collected,
 * params split with angle-bracket balance, last token kept as the parameter name.
 */
object ScriptPlatformApis {

    val DATA_OBJECT_RUNTIME_SERVICE: Map<String, String> = mapOf(
        "addGroupIdentityLink" to "lookupId, dataObjectDefinitionKey, groupId, identityLinkType",
        "addUserIdentityLink" to "lookupId, dataObjectDefinitionKey, userId, identityLinkType",
        "createDataObjectDeletionBuilder" to "",
        "createDataObjectInstanceEntityQuery" to "",
        "createDataObjectInstanceQuery" to "",
        "createDataObjectModificationBuilder" to "",
        "createDataObjectValueInstanceBuilder" to "",
        "createDataObjectValueInstanceBuilderByDefinitionId" to "dataObjectDefinitionId",
        "createDataObjectValueInstanceBuilderByDefinitionKey" to "dataObjectDefinitionKey",
        "createDataObjectValueInstanceBuilderByDefinitionKeyAndTenantId" to "dataObjectDefinitionKey, tenantId",
        "createMasterDataInstanceBuilder" to "",
        "createMasterDataInstanceImportBuilder" to "",
        "createMasterDataInstanceQuery" to "",
        "createMasterDataInstanceUpdateBuilder" to "masterDataInstanceId",
        "deleteDataObject" to "lookupId, dataObjectDefinitionId",
        "deleteDataObjectVariablesByInstanceId" to "dataInstanceId",
        "deleteGroupIdentityLink" to "lookupId, dataObjectDefinitionKey, groupId, identityLinkType",
        "deleteMasterDataInstanceById" to "instanceId",
        "deleteMasterDataInstanceVariable" to "instanceId, variableName",
        "deleteMasterDataInstancesByDefinitionId" to "dataObjectDefinitionId",
        "deleteUserIdentityLink" to "lookupId, dataObjectDefinitionKey, userId, identityLinkType",
        "findDataObjectValueByDataObjectInstanceId" to "instanceId",
        "findDataObjectValueByLookupIdAndDefinitionId" to "lookupId, dataObjectDefinitionId",
        "findDataObjectValueByLookupIdAndDefinitionKey" to "lookupId, dataObjectDefinitionKey, tenantId",
        "getDataSource" to "dataSourceId",
        "getIdentityLinksForDataObjectInstance" to "lookupId, dataObjectDefinitionKey",
        "getMasterDataInstanceVariables" to "masterDataInstanceId",
        "loadMasterDataInstanceData" to "data, dataObjectDefinitionId",
        "saveMasterDataInstance" to "masterDataInstance",
        "saveMasterDataInstanceVariables" to "masterDataInstanceId, variablesToSet",
    )

    val DATA_OBJECT_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "createDataObjectDefinitionQuery" to "",
        "createDataObjectSchemaDefinitionQuery" to "",
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "deleteDeployment" to "deploymentId, cascade",
        "getDataObjectDefinition" to "dataObjectDefinitionId",
        "getDataObjectDefinitionByKey" to "dataObjectDefinitionKey",
        "getDataObjectDefinitionByKeyAndTenantId" to "dataObjectDefinitionKey, tenantId",
        "getDataObjectModel" to "dataObjectDefinitionId",
        "getDataObjectModelByKey" to "dataObjectDefinitionKey",
        "getDataObjectModelByKeyAndTenantId" to "dataObjectDefinitionKey, tenantId",
        "getDeploymentResourceNames" to "deploymentId",
        "getMasterDataModel" to "dataObjectDefinitionId",
        "getResourceAsStream" to "deploymentId, resourceName",
        "performDataObjectSchemaDefinitionRollback" to "dataObjectSchemaDefinitionId",
        "performDataObjectSchemaDefinitionUpdate" to "dataObjectSchemaDefinitionId",
    )

    val DATA_OBJECT_MANAGEMENT_SERVICE: Map<String, String> = mapOf(
        "createMasterDataChangeTenantIdBuilder" to "fromTenantId, toTenantId",
    )

    val CONTENT_SERVICE: Map<String, String> = mapOf(
        "addGroupIdentityLink" to "contentItemId, userId, identityLinkType",
        "addUserIdentityLink" to "contentItemId, userId, identityLinkType",
        "copyContentItem" to "contentItemId, parentFolderId",
        "createContentItemQuery" to "",
        "createCoreContentItemQuery" to "",
        "createNewVersionContentItem" to "originalContentItemId, name, mimeType, inputStream",
        "createProvisionalRenditionItems" to "contentItemId, documentAgentDefinitionId",
        "deleteContentItem" to "contentItemId",
        "deleteContentItemsByProcessInstanceId" to "processInstanceId",
        "deleteContentItemsByScopeIdAndScopeType" to "scopeId, scopeType",
        "deleteContentItemsByTaskId" to "taskId",
        "deleteContentItemsByVersionParentId" to "versionParentId",
        "deleteGroupIdentityLink" to "contentItemId, userId, identityLinkType",
        "deleteUserIdentityLink" to "contentItemId, userId, identityLinkType",
        "findContentItemsByProcessInstanceId" to "processInstanceId",
        "findContentItemsByScopeIdAndType" to "scopeId, scopeType",
        "findContentItemsByTaskId" to "taskId",
        "getContentItemData" to "contentItemId",
        "moveContentItem" to "contentItemId, newParentFolderId",
        "newContentItem" to "",
        "newCoreContentItem" to "",
        "renameContentItem" to "contentItemId, newName",
        "revertContentItemVersion" to "currentContentItemId, revertToContentItemId",
        "saveContentItem" to "contentItem, inputStream",
        "setContentItemDefinition" to "contentItemId, definitionId",
        "updateContentItemContent" to "contentItemId, inputStream",
    )

    val DOCUMENT_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "convertDocumentDefinitionModelToJson" to "documentDefinitionId",
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "createDocumentDefinitionQuery" to "",
        "deleteDeployment" to "deploymentId",
        "getDeploymentResourceNames" to "deploymentId",
        "getDocumentDefinition" to "documentDefinitionId",
        "getDocumentDefinitionByKey" to "documentDefinitionKey",
        "getDocumentDefinitionByKeyAndTenantId" to "documentDefinitionKey, tenantId",
        "getDocumentDefinitionModel" to "documentDefinitionId",
        "getResourceAsStream" to "deploymentId, resourceName",
        "setDocumentDefinitionCategory" to "documentDefinitionId, category",
    )

    val RENDITION_SERVICE: Map<String, String> = mapOf(
        "createRenditionItemQuery" to "",
        "deleteRenditionItem" to "renditionItemId",
        "deleteRenditionItemsByProcessInstanceId" to "processInstanceId",
        "deleteRenditionItemsByScopeIdAndScopeType" to "scopeId, scopeType",
        "deleteRenditionItemsByTaskId" to "taskId",
        "getRenditionItemData" to "renditionItemId",
        "newRenditionItem" to "",
        "saveRenditionItem" to "renditionItem, inputStream",
    )

    val METADATA_SERVICE: Map<String, String> = mapOf(
        "getMetadataInstance" to "contentItemId, metadataName",
        "getMetadataInstances" to "contentItemId, metadataNames",
        "getMetadataValue" to "contentItemId, metadataName",
        "getMetadataValues" to "contentItemId",
        "removeMetadataValue" to "contentItemId, metadataName",
        "removeMetadataValues" to "contentItemId, metadataNames",
        "setMetadataValue" to "contentItemId, metadataName, metadataValue",
        "setMetadataValues" to "contentItemId, metadataValues",
    )

    val TEMPLATE_SERVICE: Map<String, String> = mapOf(
        "createTemplateContentProcessingBuilder" to "",
        "createTemplateProcessingBuilder" to "",
        "getActionTemplateVariations" to "titleTemplateKey, messageTemplateKey, titleVariationCode, messageVariationCode, payload, parentDeploymentId, tenantId",
        "processTemplate" to "templateKey, null, variant, payload",
    )

    val TEMPLATE_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "createTemplateDefinitionQuery" to "",
        "createTemplateVariationDefinitionQuery" to "",
        "deleteDeployment" to "deploymentId",
        "getDeploymentResourceNames" to "deploymentId",
        "getLatestTemplateDefinitionModelByKey" to "templateDefinitionKey",
        "getLatestTemplateDefinitionModelByKeyAndTenantId" to "templateDefinitionKey, tenantId",
        "getResourceAsStream" to "deploymentId, resourceName",
        "getTemplateDefinitionModel" to "templateDefinitionId",
        "getTemplateDefinitionModelToJson" to "templateDefinitionKey",
        "getTemplateVariationDefinitionModel" to "templateVariationDefinitionId",
        "getTemplateVariationDefinitionModelToJson" to "templateVariationDefinitionId",
    )

    val SEQUENCE_SERVICE: Map<String, String> = mapOf(
        "createSequenceGenerator" to "",
        "createSequenceValueQuery" to "",
        "deleteSequenceValue" to "sequenceId",
        "updateSequenceCurrentValue" to "sequenceId, newValue",
    )

    val PLATFORM_RUNTIME_SERVICE: Map<String, String> = mapOf(
        "createSlaAuditInstanceBuilder" to "",
        "createSlaAuditInstanceQuery" to "",
        "createUpdateSlaAuditInstanceBuilder" to "",
        "createWorkInstanceQuery" to "",
        "deleteSlaAuditInstance" to "id",
        "getSlaAuditInstance" to "auditInstanceId",
        "syncEntityLinks" to "scopeId, scopeType",
    )

    val PLATFORM_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "convertDashboardComponentDefinitionModelToJson" to "definitionId",
        "convertDataDictionaryModelToJson" to "definitionId",
        "convertQueryDefinitionModelToJson" to "queryDefinitionId",
        "convertSequenceDefinitionModelToJson" to "definitionId",
        "convertSlaDefinitionModelToJson" to "definitionId",
        "convertVariableExtractorDefinitionModelToJson" to "variableExtractorDefinitionId",
        "createDashboardComponentDefinitionQuery" to "",
        "createDataDictionaryDefinitionQuery" to "",
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "createPlatformDefinitionQuery" to "",
        "createQueryDefinitionQuery" to "",
        "createSequenceDefinitionQuery" to "",
        "createSlaDefinitionQuery" to "",
        "createVariableExtractorDefinitionQuery" to "",
        "createWorkDefinitionQuery" to "",
        "deleteDeployment" to "deploymentId",
        "getDashboardComponentDefinition" to "definitionId",
        "getDashboardComponentDefinitionByKey" to "definitionKey",
        "getDashboardComponentDefinitionByKeyAndTenantId" to "definitionKey, tenantId",
        "getDashboardComponentDefinitionModel" to "definitionId",
        "getDashboardComponentDefinitionModelByKey" to "definitionKey",
        "getDashboardComponentDefinitionModelByKeyAndTenantId" to "definitionKey, tenantId",
        "getDataDictionaryDefinition" to "definitionId",
        "getDataDictionaryDefinitionByKey" to "definitionKey",
        "getDataDictionaryDefinitionByKeyAndTenantId" to "definitionKey, tenantId",
        "getDataDictionaryModel" to "definitionId",
        "getDataDictionaryModelByKey" to "definitionKey",
        "getDataDictionaryModelByKeyAndTenantId" to "definitionKey, tenantId",
        "getDataDictionaryType" to "definitionKey, typeName, parentDeploymentId, tenantId",
        "getDeploymentResourceNames" to "deploymentId",
        "getQueryDefinition" to "queryDefinitionId",
        "getQueryDefinitionByKey" to "queryDefinitionKey",
        "getQueryDefinitionByKeyAndTenantId" to "queryDefinitionKey, tenantId",
        "getQueryDefinitionModel" to "queryDefinitionId",
        "getQueryDefinitionModelByKey" to "QueryDefinitionKey",
        "getQueryDefinitionModelByKeyAndTenantId" to "QueryDefinitionKey, tenantId",
        "getResourceAsStream" to "deploymentId, resourceName",
        "getSequenceDefinition" to "definitionId",
        "getSequenceDefinitionByKey" to "definitionKey",
        "getSequenceDefinitionByKeyAndTenantId" to "definitionKey, tenantId",
        "getSequenceDefinitionModel" to "definitionId",
        "getSequenceDefinitionModelByKey" to "definitionKey",
        "getSequenceDefinitionModelByKeyAndTenantId" to "definitionKey, tenantId",
        "getSlaDefinition" to "definitionId",
        "getSlaDefinitionByKey" to "definitionKey",
        "getSlaDefinitionByKeyAndTenantId" to "definitionKey, tenantId",
        "getSlaDefinitionModel" to "definitionId",
        "getSlaDefinitionModelByKey" to "definitionKey",
        "getSlaDefinitionModelByKeyAndTenantId" to "definitionKey, tenantId",
        "getVariableExtractorDefinition" to "variableExtractorDefinitionId",
        "getVariableExtractorDefinitionByKey" to "variableExtractorDefinitionKey",
        "getVariableExtractorDefinitionByKeyAndTenantId" to "variableExtractorDefinitionKey, tenantId",
        "getVariableExtractorDefinitionModel" to "variableExtractorDefinitionId",
        "getVariableExtractorDefinitionModelByKey" to "VariableExtractorDefinitionKey",
        "getVariableExtractorDefinitionModelByKeyAndTenantId" to "VariableExtractorDefinitionKey, tenantId",
    )

    val PLATFORM_HISTORY_SERVICE: Map<String, String> = mapOf(
        "createHistoricWorkInstanceQuery" to "",
        "syncHistoricEntityLinks" to "scopeId, scopeType",
    )

    val TRANSLATION_SERVICE: Map<String, String> = mapOf(
        "createTranslation" to "scopeId, scopeType, key, locale, value",
        "createTranslationQuery" to "",
        "createTranslations" to "requests",
        "deleteByScopeIdAndScopeType" to "scopeId, scopeType",
        "deleteByScopeIdsAndScopeType" to "scopeIds, scopeType",
        "findByScopeIdAndScopeType" to "scopeId, scopeType",
        "save" to "scopeId, scopeType, key, locale, value",
        "updateValue" to "translationId, value",
    )

    val COMMENT_SERVICE: Map<String, String> = mapOf(
        "createCommentBuilder" to "",
        "createCommentQuery" to "",
        "deleteComment" to "id",
        "findById" to "id",
        "findByScopeIdAndScopeType" to "scopeId, scopeType",
        "updateComment" to "commentId, content",
    )

    val TENANT_VARIABLE_SERVICE: Map<String, String> = mapOf(
        "createTenantVariableQuery" to "",
        "deleteVariable" to "tenantId, variableName",
        "deleteVariables" to "tenantId, variableNames",
        "getVariableValue" to "tenantId, variableName",
        "getVariables" to "tenantId",
        "setVariable" to "tenantId, variableName, value, isProtected",
        "setVariables" to "tenantId, variables, protectedVariables",
    )

    val ENCRYPTION_SERVICE: Map<String, String> = mapOf(
        "decrypt" to "value",
        "encrypt" to "value",
    )

    val PLATFORM_IDENTITY_SERVICE: Map<String, String> = mapOf(
        "addGroupPrivilegeMapping" to "privilegeId, groupId",
        "addUserPrivilegeMapping" to "privilegeId, userId",
        "checkAccessToken" to "tokenId, tokenValue",
        "checkPassword" to "userId, password",
        "createAccessTokenQuery" to "",
        "createAuthenticationTokenBuilder" to "",
        "createGroupQuery" to "",
        "createMembership" to "userId, groupId",
        "createNativeGroupQuery" to "",
        "createNativeTokenQuery" to "",
        "createNativeUserQuery" to "",
        "createNewAccessTokenBuilder" to "",
        "createNewGroupBuilder" to "groupId",
        "createNewUserBuilder" to "userId",
        "createPlatformGroupQuery" to "",
        "createPlatformIdentityInfoBuilder" to "",
        "createPlatformIdentityInfoQuery" to "",
        "createPlatformUserQuery" to "",
        "createPrivilege" to "privilegeName",
        "createPrivilegeQuery" to "",
        "createProperty" to "name, value",
        "createTokenQuery" to "",
        "createUpdateGroupBuilder" to "groupId",
        "createUpdateUserBuilder" to "userId",
        "createUserQuery" to "",
        "deleteAccessToken" to "tokenId",
        "deleteGroup" to "groupId",
        "deleteGroupPrivilegeMapping" to "privilegeId, groupId",
        "deleteMembership" to "userId, groupId",
        "deletePlatformIdentityInfoByGroupId" to "groupId",
        "deletePlatformIdentityInfoByGroupIdAndTenantId" to "groupId, tenantId",
        "deletePlatformIdentityInfoById" to "id",
        "deletePlatformIdentityInfoByUserId" to "userId",
        "deletePlatformIdentityInfoByUserIdAndTenantId" to "userId, tenantId",
        "deletePrivilege" to "privilegeId",
        "deleteProperty" to "name",
        "deleteToken" to "tokenId",
        "deleteUser" to "userId",
        "deleteUserInfo" to "userId, key",
        "deleteUserPrivilegeMapping" to "privilegeId, userId",
        "findAccessToken" to "tokenId",
        "findIdentityInfoByUserIdAndName" to "userId, name",
        "findPlatformGroupById" to "groupId",
        "findPlatformGroupsForUser" to "userId",
        "findPlatformUserById" to "userId",
        "findPropertyByName" to "name",
        "findUniqueTenantIds" to "",
        "getGroupsWithPrivilege" to "privilegeId",
        "getPlatformUserInfo" to "userId, infoName, infoClass",
        "getPrivilegeMappingsByPrivilegeId" to "privilegeId",
        "getUserInfo" to "userId, key",
        "getUserInfoKeys" to "userId",
        "getUserPicture" to "userId",
        "getUsersWithPrivilege" to "privilegeId",
        "newGroup" to "groupId",
        "newToken" to "id",
        "newUser" to "userId",
        "saveGroup" to "group",
        "saveToken" to "token",
        "saveUser" to "user",
        "setAuthenticatedUserId" to "authenticatedUserId",
        "setPlatformUserInfo" to "userId, infoName, value",
        "setUserDefinitionById" to "userId, userDefinitionId",
        "setUserDefinitionByKey" to "userId, userDefinitionKey, tenantId",
        "setUserInfo" to "userId, key, value",
        "setUserPicture" to "userId, picture",
        "setUserStateAndSubState" to "userId, state, subState, tenantId",
        "updateProperty" to "idmProperty",
        "updateUserPassword" to "user",
        "updateUserPresence" to "userId, tenantId, presence",
    )

    val USER_DEFINITION_SERVICE: Map<String, String> = mapOf(
        "createUserDefinitionQuery" to "",
        "createUserDefinitionRegistrationBuilder" to "",
        "getUserDefinitionByKeyAndTenantId" to "key, tenantId",
        "getUserDefinitionModelById" to "userDefinitionId",
        "getUserDefinitionModelByKey" to "key",
        "getUserDefinitionModelByKeyAndTenantId" to "key, tenantId",
        "getUserDefinitionModelForUser" to "userId",
        "removeUserDefinition" to "userDefinitionId",
    )

    val USER_ACCOUNT_SERVICE: Map<String, String> = mapOf(
        "createNewUserAccountBuilder" to "",
        "createUpdateUserAccountBuilder" to "accountId",
        "createUserAccountQuery" to "",
        "deleteUserAccount" to "accountId",
        "findById" to "userAccountId",
        "findByIds" to "userAccountIds",
        "setStateAndSubState" to "accountId, state, subState",
        "setType" to "accountId, type, subType",
    )

    val ACTION_RUNTIME_SERVICE: Map<String, String> = mapOf(
        "addActionInstanceLink" to "actionInstanceId, scopeId, scopeType",
        "addActionInstanceTypeLink" to "actionInstanceId, type, linkValue",
        "addGroupIdentityLink" to "actionInstanceId, groupId, identityLinkType",
        "addUserIdentityLink" to "actionInstanceId, userId, identityLinkType",
        "bulkDeleteActionInstancesByScopeIdsAndScopeType" to "scopeIds, scopeType",
        "createActionInstanceBuilder" to "",
        "createActionInstanceQuery" to "",
        "createActionLinkQuery" to "",
        "createExecuteActionInstanceBuilder" to "",
        "deleteActionInstance" to "actionInstanceId",
        "deleteActionInstanceTypeLink" to "actionInstanceTypeLinkId",
        "deleteGroupIdentityLink" to "actionInstanceId, groupId, identityLinkType",
        "deleteUserIdentityLink" to "actionInstanceId, userId, identityLinkType",
        "findActionInstancesForScopeIdAndNoSubScopeId" to "scopeType, scopeId, includeDeletedFromCache",
        "findActionInstancesForScopeIdAndSubScopeId" to "scopeType, scopeId, subScopeId, includeDeletedFromCache",
        "findScopedObjectActionData" to "scopedObjectActionQuery",
        "getActionInstanceIdentityLinks" to "actionInstanceId",
        "getActionInstanceTypeLinks" to "actionInstanceId",
        "getFormInfo" to "actionInstanceId",
        "getVariables" to "actionInstanceId",
        "migrateActionTypeLinks" to "",
    )

    val ACTION_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "addActionDefinitionLink" to "actionDefinitionId, type, linkValue",
        "convertActionDefinitionModelToJson" to "actionDefinitionId",
        "createActionDefinitionQuery" to "",
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "deleteActionDefinitionLink" to "actionDefinitionLinkId",
        "deleteDeployment" to "deploymentId",
        "getActionDefinition" to "actionDefinitionId",
        "getActionDefinitionByKey" to "actionDefinitionKey",
        "getActionDefinitionByKeyAndTenantId" to "actionDefinitionKey, tenantId",
        "getActionDefinitionLinks" to "actionDefinitionId",
        "getActionDefinitionModel" to "actionDefinitionId",
        "getActionDefinitionModelByKey" to "actionDefinitionKey",
        "getActionDefinitionModelByKeyAndTenantId" to "actionDefinitionKey, tenantId",
        "getDeploymentResourceNames" to "deploymentId",
        "getResourceAsStream" to "deploymentId, resourceName",
    )

    val SERVICE_REGISTRY_RUNTIME_SERVICE: Map<String, String> = mapOf(
        "createServiceInvocationBuilder" to "",
        "getLookupIdByReferenceKey" to "data, referenceKey, tenantId",
        "getLookupIdByServiceKey" to "data, serviceKey, tenantId",
    )

    val SERVICE_REGISTRY_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "convertServiceDefinitionModelToJson" to "serviceDefinitionId",
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "createServiceDefinitionQuery" to "",
        "deleteDeployment" to "deploymentId",
        "getDeploymentResourceNames" to "deploymentId",
        "getResourceAsStream" to "deploymentId, resourceName",
        "getServiceDefinition" to "serviceDefinitionId",
        "getServiceDefinitionByKey" to "serviceDefinitionKey",
        "getServiceDefinitionByKeyAndParentDeploymentId" to "serviceDefinitionKey, parentDeploymentId",
        "getServiceDefinitionByKeyAndParentDeploymentIdAndTenantId" to "serviceDefinitionKey, parentDeploymentId, tenantId",
        "getServiceDefinitionByKeyAndTenantId" to "serviceDefinitionKey, tenantId",
        "getServiceDefinitionModel" to "serviceDefinitionId",
        "getServiceDefinitionModelByKey" to "serviceDefinitionKey",
        "getServiceDefinitionModelByKeyAndParentDeploymentId" to "serviceDefinitionKey, parentDeploymentId",
        "getServiceDefinitionModelByKeyAndParentDeploymentIdAndTenantId" to "serviceDefinitionKey, parentDeploymentId, tenantId",
        "getServiceDefinitionModelByKeyAndTenantId" to "serviceDefinitionKey, tenantId",
        "getServiceDefinitionModelByReferenceKeyAndParentDeploymentIdAndTenantId" to "referenceKey, parentDeploymentId, tenantId",
        "getServiceDefinitionModelByReferenceKeyAndTenantId" to "referenceKey, tenantId",
    )

    val AUDIT_SERVICE: Map<String, String> = mapOf(
        "bulkDeleteAuditInstancesByScopeIdsAndScopeType" to "scopeIds, scopeType",
        "createAuditInstanceBuilder" to "",
        "createAuditInstanceQuery" to "",
        "deleteAuditInstance" to "auditInstanceId",
        "deleteAuditInstancesByScopeIdAndScopeType" to "scopeId, scopeType",
    )

    val NOTIFICATION_SERVICE: Map<String, String> = mapOf(
        "sendNotification" to "userId, notification, messageProvider",
    )

    val PLATFORM_FORM_SERVICE: Map<String, String> = mapOf(
        "createFormInstance" to "variables, formInfo, taskId, processInstanceId, processDefinitionId, tenantId, outcome",
        "createFormInstanceQuery" to "",
        "createFormInstanceWithScopeId" to "variables, formInfo, taskId, scopeId, scopeType, scopeDefinitionId, tenantId, outcome",
        "deleteFormInstance" to "formInstanceId",
        "deleteFormInstancesByFormDefinition" to "formDefinitionId",
        "deleteFormInstancesByProcessDefinition" to "processDefinitionId",
        "deleteFormInstancesByScopeDefinition" to "scopeDefinitionId",
        "filterFormValues" to "elementId, elementType, scopeDefinitionId, scopeType, formValues",
        "getFormInstanceModelById" to "formDefinitionId, taskId, processInstanceId, variables, tenantId, fallbackToDefaultTenant",
        "getFormInstanceModelByKey" to "formDefinitionKey, taskId, processInstanceId, variables, tenantId, fallbackToDefaultTenant",
        "getFormInstanceModelByKeyAndParentDeploymentId" to "formDefinitionKey, parentDeploymentId, taskId, processInstanceId, variables, tenantId, fallbackToDefaultTenant",
        "getFormInstanceModelByKeyAndParentDeploymentIdAndScopeId" to "formDefinitionKey, parentDeploymentId, scopeId, scopeType, variables, tenantId, fallbackToDefaultTenant",
        "getFormInstanceModelByKeyAndScopeId" to "formDefinitionKey, scopeId, scopeType, variables, tenantId, fallbackToDefaultTenant",
        "getFormInstanceValues" to "formInstanceId",
        "getFormModelWithVariablesById" to "formDefinitionId, taskId, variables, tenantId, fallbackToDefaultTenant",
        "getFormModelWithVariablesByKey" to "formDefinitionKey, taskId, variables, tenantId, fallbackToDefaultTenant",
        "getFormModelWithVariablesByKeyAndParentDeploymentId" to "formDefinitionKey, parentDeploymentId, taskId, variables, tenantId, fallbackToDefaultTenant",
        "getVariablesFromFormSubmission" to "elementId, elementType, scopeId, scopeDefinitionId, scopeType, formInfo, values, outcome",
        "saveFormInstance" to "variables, formInfo, taskId, processInstanceId, processDefinitionId, tenantId, outcome",
        "saveFormInstanceByFormDefinitionId" to "variables, formDefinitionId, taskId, processInstanceId, processDefinitionId, tenantId, outcome",
        "saveFormInstanceWithScopeId" to "variables, formInfo, taskId, scopeId, scopeType, scopeDefinitionId, tenantId, outcome",
        "validateFormFields" to "elementId, elementType, scopeId, scopeDefinitionId, scopeType, formInfo, values",
    )

    val PLATFORM_FORM_REPOSITORY_SERVICE: Map<String, String> = mapOf(
        "changeDeploymentParentDeploymentId" to "deploymentId, newParentDeploymentId",
        "createDeployment" to "",
        "createDeploymentQuery" to "",
        "createFormCustomComponentDefinitionQuery" to "",
        "createFormDefinitionQuery" to "",
        "createNativeDeploymentQuery" to "",
        "createNativeFormDefinitionQuery" to "",
        "deleteDeployment" to "deploymentId, cascade",
        "getDeploymentResourceNames" to "deploymentId",
        "getFormDefinition" to "formDefinitionId",
        "getFormDefinitionResource" to "formDefinitionId",
        "getFormModelById" to "formDefinitionId",
        "getFormModelByKey" to "formDefinitionKey, tenantId, fallbackToDefaultTenant",
        "getFormModelByKeyAndParentDeploymentId" to "formDefinitionKey, parentDeploymentId, tenantId, fallbackToDefaultTenant",
        "getResourceAsStream" to "deploymentId, resourceName",
        "setDeploymentCategory" to "deploymentId, category",
        "setDeploymentTenantId" to "deploymentId, newTenantId",
        "setFormDefinitionCategory" to "formDefinitionId, category",
    )}
