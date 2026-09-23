package com.flowable.atlas.diagram

import com.flowable.atlas.model.ModelType

/**
 * A model drawn as a picture — a BPMN/CMMN/DMN diagram from its layout, a form or page as a wireframe of
 * its grid, a decision table from its rules — with the place of every element in it.
 *
 * [hotspots] are the drawn elements in the SVG's own user space (the [viewBox] coordinates): what makes a
 * click on the picture land on an element, in the explorer (through the `data-el` attribute every drawn
 * element carries) and in the IDE's preview (through [hotspotAt]).
 */
data class Picture(val svg: String, val kind: Kind, val viewBox: Box, val hotspots: List<Hotspot>) {

    enum class Kind(val id: String) { DIAGRAM("diagram"), WIREFRAME("wireframe"), DECISION_TABLE("decisionTable") }

    data class Box(val x: Double, val y: Double, val width: Double, val height: Double)

    data class Hotspot(val id: String, val x: Double, val y: Double, val width: Double, val height: Double) {
        fun contains(px: Double, py: Double): Boolean = px >= x && px <= x + width && py >= y && py <= y + height
    }

    /** The innermost hotspot containing a point in viewBox coordinates — a task inside a lane is the task,
     *  a field inside a panel the field. Null when the point hits no element. */
    fun hotspotAt(x: Double, y: Double): Hotspot? = hotspots.filter { it.contains(x, y) }.minByOrNull { it.width * it.height }
}

/**
 * The one entry point for drawing a Flowable model — used by the explorer payload, the diagrams folder of
 * the generated artifacts and the IntelliJ plugin's gutter icon and preview, so all three show the same
 * picture:
 *  - a form or page → its wireframe ([FormSvgRenderer]);
 *  - a process, case or decision → its diagram, from the layout the file carries ([DiagramRenderer]);
 *  - a decision in XML without a layout → its decision table ([DmnTableSvgRenderer]).
 */
object ModelPicture {

    /** The model types that can have a picture. */
    val TYPES: Set<ModelType> = setOf(ModelType.PROCESS, ModelType.CASE, ModelType.DECISION, ModelType.FORM, ModelType.PAGE)

    /** The picture of a model given its raw [bytes] and [fileName] (which picks the XML vs JSON reader), or
     *  null when there is none: another type, a process without a layout, a file that is not a model. */
    fun render(bytes: ByteArray, fileName: String, type: ModelType): Picture? = when (type) {
        ModelType.FORM, ModelType.PAGE -> FormSvgRenderer.picture(bytes)
        ModelType.PROCESS, ModelType.CASE, ModelType.DECISION ->
            DiagramRenderer.geometry(bytes, fileName, type)?.let(DiagramSvgRenderer::picture)
                ?: if (type == ModelType.DECISION && ModelType.isXmlModel(fileName)) DmnTableSvgRenderer.picture(bytes) else null
        else -> null
    }

    /** The [ModelType] of a graph node type that can have a picture, or null. */
    fun typeOfNode(nodeType: String?): ModelType? = when (nodeType) {
        "process" -> ModelType.PROCESS
        "case" -> ModelType.CASE
        "decision" -> ModelType.DECISION
        "form" -> ModelType.FORM
        "page" -> ModelType.PAGE
        else -> null
    }
}
