package com.flowable.atlas.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Presentation

/** Action IDs registered in plugin.xml — referenced from code (hub toolbar, cross-invocation). */
object FlowableActionIds {
    /** The Tools → Flowable Atlas group. The Atlas Hub's ⋮ renders this same group. */
    const val MENU = "Flowable.Menu"

    const val OPEN_ATLAS_HUB = "Flowable.OpenAtlasHub"
    const val OPEN_ATLAS_FINDINGS = "Flowable.OpenAtlasFindings"
    const val OPEN_ATLAS_EXPLORER = "Flowable.OpenAtlasExplorer"
    const val OPEN_ATLAS_PLAYGROUND = "Flowable.OpenAtlasPlayground"
    const val OPEN_MODEL_IN_ATLAS_EXPLORER = "Flowable.OpenModelInAtlasExplorer"
    const val GO_TO_MODEL = "Flowable.GoToModel"
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
    const val COMPARE_MODEL_WITH_ARCHIVE = "Flowable.CompareModelWithArchive"

    /**
     * The registered action's menu text, for a button or link that does the same thing elsewhere. One
     * verb had three spellings across the menu, the Hub and an editor banner; reading the text from the
     * action means the bundle is the only place it is written.
     */
    fun text(id: String): String = ActionManager.getInstance().getAction(id)?.templateText ?: id

    /**
     * The place a button inside an Atlas Hub section reads its text for. No toolbar or menu renders at
     * it: it exists so a button under the *Explorer* title can say *Open* while the menu, Find Action
     * and the Hub toolbar's tooltip say *Open Atlas Explorer*. Not the toolbar's own place — that one
     * shows tooltips, where *Open* alone would not say what opens.
     */
    const val HUB_SECTION = "AtlasHubSection"

    /**
     * The action's text at [place] — its `<override-text place="…">` when the descriptor declares one,
     * its own text otherwise. Still one string per place in the bundle, still read from the action.
     */
    fun text(id: String, place: String): String {
        val action = ActionManager.getInstance().getAction(id) ?: return id
        val presentation = Presentation().apply { copyFrom(action.templatePresentation) }
        action.applyTextOverride(place, presentation)
        return presentation.text ?: action.templateText ?: id
    }
}
