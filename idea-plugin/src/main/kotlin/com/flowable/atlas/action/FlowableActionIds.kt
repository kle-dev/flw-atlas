package com.flowable.atlas.action

import com.intellij.openapi.actionSystem.ActionManager

/** Action IDs registered in plugin.xml — referenced from code (hub toolbar, cross-invocation). */
object FlowableActionIds {
    const val OPEN_ATLAS_HUB = "Flowable.OpenAtlasHub"
    const val OPEN_ATLAS_EXPLORER = "Flowable.OpenAtlasExplorer"
    const val OPEN_EXPRESSION_PLAYGROUND = "Flowable.OpenExpressionPlayground"
    const val SEARCH_MODELS = "Flowable.SearchModels"
    const val FIND_IN_MODELS = "Flowable.FindInModels"
    const val GENERATE_ATLAS_EXPLORER = "Flowable.GenerateAtlasExplorer"
    const val GENERATE_MODEL_CONSTANTS = "Flowable.GenerateModelConstants"
    const val GENERATE_LIQUIBASE_FROM_DATA_OBJECT = "Flowable.GenerateLiquibaseFromDataObject"
    const val GENERATE_LIQUIBASE_FROM_APPS = "Flowable.GenerateLiquibaseFromApps"
    const val GENERATE_DTO_FROM_DATA_OBJECT = "Flowable.GenerateDtoFromDataObject"
    const val GENERATE_DTO_FROM_APPS = "Flowable.GenerateDtoFromApps"
    const val PULL_FROM_DESIGN = "Flowable.PullFromDesign"
    const val MANAGE_ENVIRONMENTS = "Flowable.ManageEnvironments"
    const val SWITCH_DESIGN_ENVIRONMENT = "Flowable.SwitchDesignEnvironment"
    const val SWITCH_WORK_ENVIRONMENT = "Flowable.SwitchWorkEnvironment"
    const val REBUILD_MODEL_INDEX = "Flowable.RebuildModelIndex"
    const val REGENERATE_ATLAS_EXPLORER = "Flowable.RegenerateAtlasExplorer"
    const val COPY_MODEL_KEY = "Flowable.CopyModelKey"

    /**
     * The registered action's menu text, for a button or link that does the same thing elsewhere. One
     * verb had three spellings across the menu, the Hub and an editor banner; reading the text from the
     * action means the bundle is the only place it is written.
     */
    fun text(id: String): String = ActionManager.getInstance().getAction(id)?.templateText ?: id
}
