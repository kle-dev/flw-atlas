package com.flowable.atlas.action

/** Action IDs registered in plugin.xml — referenced from code (hub toolbar, cross-invocation). */
object FlowableActionIds {
    const val OPEN_ATLAS_HUB = "Flowable.OpenAtlasHub"
    const val OPEN_ATLAS_EXPLORER = "Flowable.OpenAtlasExplorer"
    const val OPEN_EXPRESSION_PLAYGROUND = "Flowable.OpenExpressionPlayground"
    const val GENERATE_ATLAS_EXPLORER = "Flowable.GenerateAtlasExplorer"
    const val GENERATE_MODEL_CONSTANTS = "Flowable.GenerateModelConstants"
    const val GENERATE_LIQUIBASE_FROM_DATA_OBJECT = "Flowable.GenerateLiquibaseFromDataObject"
    const val GENERATE_LIQUIBASE_FROM_APPS = "Flowable.GenerateLiquibaseFromApps"
    const val PULL_FROM_DESIGN = "Flowable.PullFromDesign"
    const val CONFIGURE_DESIGN_CONNECTION = "Flowable.ConfigureDesignConnection"
    const val REBUILD_MODEL_INDEX = "Flowable.RebuildModelIndex"
}
