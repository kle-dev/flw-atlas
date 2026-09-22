package com.flowable.atlas.structure

import com.flowable.atlas.model.ModelFiles
import com.flowable.atlas.model.ModelType
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewBuilderProvider
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import javax.swing.Icon

/**
 * The Structure tool window for a model file: a form's or page's components as Design nests them
 * (panels, tabs and their sections), a process's or case's elements by name. A click goes to the
 * component's JSON object or the element's tag, in an archive entry as much as in a loose file.
 *
 * Registered per file type (JSON, XML) and asked before the language's own builder: anything that is
 * not a form, page, process or case gets null and keeps the plain JSON / XML outline. Built on the
 * PSI alone; no index.
 */
class ModelStructureViewProvider : StructureViewBuilderProvider {

    override fun getStructureViewBuilder(fileType: FileType, file: VirtualFile, project: Project): StructureViewBuilder? {
        val type = ModelFiles.typeOf(file) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val root: StructureViewTreeElement = when {
            (type == ModelType.FORM || type == ModelType.PAGE) && psi is JsonFile ->
                (psi.topLevelValue as? JsonObject)?.takeIf { it.findProperty("rows") != null }?.let(::FormNode)
            (type == ModelType.PROCESS || type == ModelType.CASE) && psi is XmlFile ->
                psi.rootTag?.let(::XmlNode)
            else -> null
        } ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = Model(psi, editor, root)
            override fun isRootNodeShown(): Boolean = false
        }
    }

    private class Model(file: PsiFile, editor: Editor?, root: StructureViewTreeElement) :
        StructureViewModelBase(file, editor, root) {
        init {
            withSuitableClasses(JsonObject::class.java, XmlTag::class.java)
        }
    }

    /** One PSI element in the outline: presents itself, navigates to itself, equal by its element. */
    private abstract class Node<T : PsiElement>(protected val psi: T) : StructureViewTreeElement, ItemPresentation {
        protected val element: T? get() = psi.takeIf { it.isValid }

        abstract fun childNodes(): Collection<StructureViewTreeElement>

        override fun getValue(): Any = psi
        override fun getPresentation(): ItemPresentation = this
        override fun getChildren(): Array<TreeElement> = if (psi.isValid) childNodes().toTypedArray() else emptyArray()
        override fun navigate(requestFocus: Boolean) { (psi as? Navigatable)?.navigate(requestFocus) }
        override fun canNavigate(): Boolean = psi.isValid && (psi as? Navigatable)?.canNavigate() == true
        override fun canNavigateToSource(): Boolean = canNavigate()
        override fun equals(other: Any?): Boolean = other is Node<*> && other.psi == psi
        override fun hashCode(): Int = psi.hashCode()
    }

    /** A form/page (the file's top object), a component, or a tab / accordion section. */
    private class FormNode(obj: JsonObject) : Node<JsonObject>(obj) {

        override fun getPresentableText(): String? {
            val obj = element ?: return null
            if (obj.parent is JsonFile) return string(obj.findProperty("metadata")?.value, "name") ?: obj.containingFile.name
            return caption(obj) ?: string(obj, "id")
        }

        /** The id, when the caption is what shows — the name a `{{…}}` or a script uses. */
        override fun getLocationString(): String? {
            val obj = element ?: return null
            if (obj.parent is JsonFile) return null
            val id = string(obj, "id")
            return id?.takeIf { caption(obj) != null && it != caption(obj) }
        }

        override fun getIcon(open: Boolean): Icon =
            if (element?.let { children(it).isNotEmpty() } == true) AllIcons.Nodes.Folder else AllIcons.Nodes.Field

        override fun childNodes(): Collection<StructureViewTreeElement> =
            element?.let { children(it) }.orEmpty().map(::FormNode)

        private companion object {
            /** Components of a grid, row by row: Design's `{cols: [...]}` or a bare list of cells. */
            fun components(rows: JsonValue?): List<JsonObject> = (rows as? JsonArray)?.valueList.orEmpty().flatMap { row ->
                when (row) {
                    is JsonObject -> (row.findProperty("cols")?.value as? JsonArray)?.valueList.orEmpty()
                    is JsonArray -> row.valueList
                    else -> emptyList()
                }
            }.filterIsInstance<JsonObject>()

            /** A form's own grid; a panel's or modal's `layoutDefinition` grid; a tab set's sections. */
            fun children(obj: JsonObject): List<JsonObject> {
                obj.findProperty("rows")?.let { return components(it.value) }
                val es = obj.findProperty("extraSettings")?.value as? JsonObject ?: return emptyList()
                (es.findProperty("layoutDefinition")?.value as? JsonObject)?.let { return components(it.findProperty("rows")?.value) }
                return (es.findProperty("sections")?.value as? JsonArray)?.valueList.orEmpty().filterIsInstance<JsonObject>()
            }

            /** `label`, a button's `extraSettings.text`, else the first localised label. */
            fun caption(obj: JsonObject): String? =
                string(obj, "label")
                    ?: string(obj.findProperty("extraSettings")?.value, "text")
                    ?: (obj.findProperty("i18n")?.value as? JsonObject)?.propertyList.orEmpty()
                        .firstNotNullOfOrNull { string(it.value, "label") }

            fun string(obj: JsonValue?, name: String): String? =
                ((obj as? JsonObject)?.findProperty(name)?.value as? JsonStringLiteral)?.value?.takeIf { it.isNotBlank() }
        }
    }

    /** A BPMN / CMMN element with an id: its name, its kind beside it, its own elements beneath. */
    private class XmlNode(tag: XmlTag) : Node<XmlTag>(tag) {

        override fun getPresentableText(): String? {
            val tag = element ?: return null
            return tag.getAttributeValue("name")?.takeIf { it.isNotBlank() } ?: tag.getAttributeValue("id") ?: tag.localName
        }

        override fun getLocationString(): String? = element?.localName?.takeIf { it != presentableText }

        override fun getIcon(open: Boolean): Icon =
            if (element?.let(::children)?.isNotEmpty() == true) AllIcons.Nodes.Folder else AllIcons.Nodes.Tag

        override fun childNodes(): Collection<StructureViewTreeElement> =
            element?.let(::children).orEmpty().map(::XmlNode)

        private companion object {
            /** Connectors, criteria and plan items say nothing a reader looks for; plan items repeat their definitions. */
            val SKIPPED = setOf(
                "sequenceFlow", "messageFlow", "association", "dataInputAssociation", "dataOutputAssociation",
                "planItem", "sentry", "planItemOnPart", "caseFileItemOnPart", "entryCriterion", "exitCriterion",
                "laneSet", "ioSpecification", "extensionElements",
            )

            fun children(tag: XmlTag): List<XmlTag> = tag.subTags.filter { sub ->
                sub.getAttributeValue("id") != null &&
                    sub.localName !in SKIPPED &&
                    // The diagram interchange: shapes and edges, one per element, all with ids.
                    !sub.namespace.endsWith("DI")
            }
        }
    }
}
