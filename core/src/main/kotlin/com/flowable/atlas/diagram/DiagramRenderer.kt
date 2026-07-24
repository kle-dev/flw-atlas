package com.flowable.atlas.diagram

import com.flowable.atlas.model.ModelType

/**
 * The single entry point for turning a Flowable model into a diagram SVG — used both by the Atlas
 * generation pipeline ([DiagramArtifacts], which emits the SVGs as artifacts) and by the IntelliJ
 * plugin's diagram gutter marker (which renders on click). It reads the model's *diagram interchange*
 * layout — from deployment-XML (`bpmndi`/`cmmndi`/`dmndi`, via [XmlDiExtractor]) or from a Flowable
 * Design workspace JSON (ORYX `childShapes`, via [OryxJsonDiExtractor]) — and paints it with
 * [DiagramSvgRenderer].
 *
 * This exists because Flowable Design's newer export no longer ships a rendered `<model>.svg` next to
 * each model; Atlas now renders the diagram itself from the layout the model file already carries.
 */
object DiagramRenderer {

    /**
     * Render the diagram of a model given its raw [bytes] and [fileName] (used to pick the XML vs. JSON
     * DI source) and its [type]. Returns null when the model has no drawable diagram: a non-diagram
     * type (only process/case/decision have one), no DI/layout in the file, or a parse failure — the
     * caller then simply has no diagram (no artifact / no gutter marker).
     */
    fun renderSvg(bytes: ByteArray, fileName: String, type: ModelType): String? {
        val notation = notationOf(type) ?: return null
        val geometry = if (ModelType.isXmlModel(fileName)) {
            XmlDiExtractor.extract(bytes, notation)
        } else {
            OryxJsonDiExtractor.extract(bytes, notation)
        }
        return DiagramSvgRenderer.render(geometry)
    }

    private fun notationOf(type: ModelType): DiagramGeometry.Notation? = when (type) {
        ModelType.PROCESS -> DiagramGeometry.Notation.BPMN
        ModelType.CASE -> DiagramGeometry.Notation.CMMN
        ModelType.DECISION -> DiagramGeometry.Notation.DMN
        else -> null
    }
}
