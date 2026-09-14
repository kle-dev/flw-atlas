package com.flowable.atlas.schema

import com.intellij.javaee.ResourceRegistrar
import com.intellij.javaee.StandardResourceProvider

/**
 * The BPMN, CMMN and DMN schemas, bundled and mapped to the namespaces the model files declare.
 *
 * Without this a `.bpmn` / `.cmmn` / `.dmn` opens with an unresolved namespace and no tag or attribute
 * completion at all: the IDE ships none of these schemas itself. With it, the model XML is edited like
 * any other schema-backed XML — completion for elements and attributes, and validation that says which
 * attribute is missing rather than leaving it to the deploy.
 *
 * ## What is registered, and what is deliberately ignored
 * The formats' own namespaces, Flowable's BPMN extension namespace, and the `schemaLocation` URLs older
 * exporters write (a file naming a location we do not know reports an unresolved resource even when the
 * namespace resolves). DMN is registered at 1.1, 1.2 and 1.3, because a decision table does not get
 * rewritten because the spec moved on.
 *
 * [IGNORED] holds the Flowable namespaces that have no schema anywhere — Flowable publishes no CMMN or
 * DMN extension schema, and Design's own `http://flowable.org/design` has never had one. Registering them
 * as *ignored* silences "URI is not registered" without pretending to validate them. That costs nothing:
 * the BPMN, CMMN and DMN base types all end in `anyAttribute namespace="##other" processContents="lax"`,
 * so an attribute from an undescribed namespace is skipped rather than rejected, and everything we do
 * describe is still checked.
 *
 * ## Why a provider and not the `standardResource` bean
 * The declarative bean would express the mapping without code, and every one of its entries came back as
 * `resourcePath must not be null` in this IDE build. The platform registers its own resources through
 * this interface; so do we, and the mapping stays in one readable table.
 *
 * Provenance of the files, the one that is modified, and the recipe for refreshing them from a newer
 * engine: `idea-plugin/README.md`.
 */
internal class FlowableSchemas : StandardResourceProvider {

    override fun registerResources(registrar: ResourceRegistrar) {
        for ((url, path) in SCHEMAS) registrar.addStdResource(url, path, javaClass.classLoader)
        for (namespace in IGNORED) registrar.addIgnoredResource(namespace)
    }

    companion object {
        /**
         * Namespace (or `schemaLocation`) to the bundled file that describes it.
         *
         * Each format keeps its own directory, and that is not tidiness: `DC.xsd` and `DI.xsd` exist
         * three times with different target namespaces, and a relative `<xsd:include>` resolves against
         * the directory of the file that names it.
         */
        val SCHEMAS: List<Pair<String, String>> = listOf(
            // BPMN 2.0
            "http://www.omg.org/spec/BPMN/20100524/MODEL" to "schemas/bpmn/BPMN20.xsd",
            "http://www.omg.org/spec/BPMN/20100524/DI" to "schemas/bpmn/BPMNDI.xsd",
            "http://www.omg.org/spec/DD/20100524/DC" to "schemas/bpmn/DC.xsd",
            "http://www.omg.org/spec/DD/20100524/DI" to "schemas/bpmn/DI.xsd",
            "http://flowable.org/bpmn" to "schemas/bpmn/flowable-bpmn-extensions.xsd",
            // The schemaLocation OMG's own exports name.
            "http://www.omg.org/spec/BPMN/2.0/20100501/BPMN20.xsd" to "schemas/bpmn/BPMN20.xsd",
            "http://www.omg.org/spec/BPMN/2.0/20100501/BPMNDI.xsd" to "schemas/bpmn/BPMNDI.xsd",
            "http://www.omg.org/spec/BPMN/2.0/20100501/DC.xsd" to "schemas/bpmn/DC.xsd",
            "http://www.omg.org/spec/BPMN/2.0/20100501/DI.xsd" to "schemas/bpmn/DI.xsd",
            // CMMN 1.1
            "http://www.omg.org/spec/CMMN/20151109/MODEL" to "schemas/cmmn/CMMN11.xsd",
            "http://www.omg.org/spec/CMMN/20151109/CMMNDI" to "schemas/cmmn/CMMNDI11.xsd",
            "http://www.omg.org/spec/CMMN/20151109/DC" to "schemas/cmmn/DC.xsd",
            "http://www.omg.org/spec/CMMN/20151109/DI" to "schemas/cmmn/DI.xsd",
            // DMN 1.3 is what Design writes; 1.2 and 1.1 are what older decision tables still carry.
            "https://www.omg.org/spec/DMN/20191111/MODEL/" to "schemas/dmn/DMN13.xsd",
            "https://www.omg.org/spec/DMN/20191111/DMNDI/" to "schemas/dmn/DMNDI13.xsd",
            "http://www.omg.org/spec/DMN/20180521/MODEL/" to "schemas/dmn/DMN12.xsd",
            "http://www.omg.org/spec/DMN/20180521/DMNDI/" to "schemas/dmn/DMNDI12.xsd",
            "http://www.omg.org/spec/DMN/20180521/DC/" to "schemas/dmn/DC.xsd",
            "http://www.omg.org/spec/DMN/20180521/DI/" to "schemas/dmn/DI.xsd",
            "http://www.omg.org/spec/DMN/20151101" to "schemas/dmn/dmn.xsd",
        )

        /** Flowable namespaces nobody publishes a schema for. */
        val IGNORED: List<String> = listOf(
            "http://flowable.org/cmmn",
            "http://flowable.org/dmn",
            "http://flowable.org/design",
            "http://flowable.org/modeler",
        )
    }
}
