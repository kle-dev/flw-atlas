package com.flowable.atlas.completion

import com.flowable.atlas.icons.AtlasIcons
import com.flowable.atlas.index.ModelEntry
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * One icon vocabulary for every completion item the plugin offers.
 *
 * The expression and script contributors were fully iconed with the platform's `Nodes.*` set while model
 * keys, operations, value fields and Liquibase columns had none — two aesthetics in one plugin, and a
 * popup where the eye could not tell a process key from a variable name. A model key carries its type's
 * icon (the explorer's glyph); everything else borrows the platform icon a Java developer already reads
 * that way: a method for an operation, a parameter for its input, a variable, a constant, a column.
 */
internal object AtlasLookups {

    /** A model key: type icon, the type as grey type text, the name as a tail when it differs. */
    fun modelKey(entry: ModelEntry): LookupElementBuilder {
        var b = LookupElementBuilder.create(entry.key)
            .withIcon(AtlasIcons.forType(entry.type))
            .withTypeText(entry.type.display, true)
        if (entry.name != entry.key) b = b.withTailText("  ${entry.name}", true)
        return b.withLookupStrings(KeyLookup.searchTokens(entry.key, entry.name))
    }

    /** A service or data-object operation. */
    val OPERATION: Icon = AllIcons.Nodes.Method
    /** An operation's input parameter — what `value("…", …)` names. */
    val PARAMETER: Icon = AllIcons.Nodes.Parameter
    /** A payload field, a master-data field, an event parameter. */
    val FIELD: Icon = AllIcons.Nodes.Field
    val COLUMN: Icon = AllIcons.Nodes.DataColumn
    val TABLE: Icon = AllIcons.Nodes.DataTables
    val COLUMN_TYPE: Icon = AllIcons.Nodes.Type
    /** A Spring bean scraped from the project's expressions — what the script contributor uses too. */
    val BEAN: Icon = AllIcons.Nodes.Plugin
    /** A name the engine resolves at runtime, like a root object. */
    val REFERENCED: Icon = AllIcons.Nodes.Tag

    fun vocabularyIcon(vocabulary: Vocabulary): Icon = when (vocabulary) {
        Vocabulary.VARIABLE -> AllIcons.Nodes.Variable
        Vocabulary.USER_TASK, Vocabulary.ACTIVITY -> AllIcons.Nodes.Tag          // an element id
        Vocabulary.MESSAGE, Vocabulary.SIGNAL -> AllIcons.Nodes.Constant         // a named definition
        Vocabulary.OUTCOME -> AllIcons.Nodes.Property
    }

    fun memberIcon(kind: MemberKind): Icon = when (kind) {
        MemberKind.DECISION_VARIABLE -> AllIcons.Nodes.Variable
        MemberKind.EVENT_PAYLOAD, MemberKind.MASTER_DATA_FIELD -> FIELD
    }
}
