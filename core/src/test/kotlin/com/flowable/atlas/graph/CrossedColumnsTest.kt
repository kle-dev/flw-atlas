package com.flowable.atlas.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CrossedColumns] — what it reports, and (the half that decides whether anyone trusts it) what it
 * stays quiet about.
 *
 * The silence cases are the point of the fixtures here: a `.service` model whose fields are named
 * after abbreviated or prefixed columns is the *normal* case in a real project (`approverApproval` ↔
 * `APPR_APPROVAL_`), and a check that reported those would be turned off within a day. Only a field
 * named after a column that actually exists elsewhere in the same table is evidence.
 */
class CrossedColumnsTest {

    private fun col(name: String, columnName: String) =
        linkedMapOf<String, Any?>("name" to name, "columnName" to columnName, "type" to "string")

    /** One `db` service, optionally with the coverage rows [LiquibaseCoverage] would have attached. */
    private fun service(
        vararg columns: Map<String, Any?>,
        table: String? = "app_person",
        liquibaseColumns: List<Pair<String, String>> = emptyList(),   // column name to its table
    ): MutableMap<String, Any?> {
        val s = linkedMapOf<String, Any?>("key" to "personService", "tableName" to table, "columns" to columns.toList())
        if (liquibaseColumns.isNotEmpty()) {
            s["schemaCoverage"] = linkedMapOf<String, Any?>("rows" to liquibaseColumns.map { (name, t) ->
                linkedMapOf<String, Any?>("sql" to name, "table" to t, "inLiquibase" to true)
            })
        }
        return s
    }

    @Suppress("UNCHECKED_CAST")
    private fun crossingsOf(service: MutableMap<String, Any?>): List<Map<String, Any?>> {
        val result = linkedMapOf<String, Any?>("services" to mutableListOf(service))
        CrossedColumns.apply(result)
        return (service["crossedColumns"] as? List<Map<String, Any?>>).orEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun pairs(group: Map<String, Any?>): List<Pair<String?, String?>> =
        (group["mappings"] as List<Map<String, Any?>>).map { it["field"] as? String to it["column"] as? String }

    @Test
    fun twoFieldsHoldingEachOthersColumnAreOneSwap() {
        val groups = crossingsOf(service(
            col("id", "id_"),
            col("userName", "first_name_"),
            col("firstName", "user_name_"),
        ))
        assertEquals(1, groups.size)
        assertEquals("swapped", groups[0]["kind"])
        // In mapping order, so the message reads the way the model is written.
        assertEquals(listOf("userName" to "first_name_", "firstName" to "user_name_"), pairs(groups[0]))
    }

    @Test
    fun aThreeWayRotationIsOneFindingInChainOrder() {
        val groups = crossingsOf(service(
            col("alpha", "beta_"),
            col("beta", "gamma_"),
            col("gamma", "alpha_"),
        ))
        assertEquals(1, groups.size)
        assertEquals("rotated", groups[0]["kind"])
        // Each field paired with the column of the *next* one: the chain a reader can follow.
        assertEquals(listOf("alpha" to "beta_", "beta" to "gamma_", "gamma" to "alpha_"), pairs(groups[0]))
    }

    /** The false-positive guard. None of these has another column for the field name to point at. */
    @Test
    fun abbreviatedPrefixedAndMatchingColumnsAreSilent() {
        assertEquals(emptyList<Map<String, Any?>>(), crossingsOf(service(
            col("id", "ID_"),                                   // same name, two conventions
            col("approverApproval", "APPR_APPROVAL_"),          // abbreviated
            col("customerName", "NAME_"),                       // prefix dropped
            col("thirdPartyRestricted", "THIRD_PARTY_RESTR_"),  // truncated
            table = "app_thing",
        )))
    }

    @Test
    fun aFieldNamedAfterAnUnmappedColumnOfTheSameTableWarns() {
        val groups = crossingsOf(service(
            col("id", "id_"),
            col("customerName", "name_"),
            liquibaseColumns = listOf("id_" to "app_person", "name_" to "app_person", "customer_name_" to "app_person"),
        ))
        assertEquals(1, groups.size)
        assertEquals("crossed", groups[0]["kind"])
        assertEquals("customer_name_", groups[0]["expected"])
        assertNull("no field maps customer_name_, so there is no other field to name", groups[0]["otherField"])
    }

    @Test
    fun aOneDirectionalCrossNamesTheFieldThatMapsTheOtherColumn() {
        val groups = crossingsOf(service(
            col("customerName", "name_"),
            col("legacyName", "customer_name_"),
        ))
        assertEquals(1, groups.size)
        assertEquals("crossed", groups[0]["kind"])
        assertEquals(listOf("customerName" to "name_"), pairs(groups[0]))
        assertEquals("customer_name_", groups[0]["expected"])
        assertEquals("legacyName", groups[0]["otherField"])
    }

    /**
     * The coverage pass falls back to *every* column of a changelog when it cannot match the service's
     * table, so a column of another table can appear in the rows. It says nothing about this mapping.
     */
    @Test
    fun aColumnOfAnotherTableIsNoEvidence() {
        assertEquals(emptyList<Map<String, Any?>>(), crossingsOf(service(
            col("customerName", "name_"),
            liquibaseColumns = listOf("name_" to "app_person", "customer_name_" to "other_table"),
        )))
    }

    @Test
    fun aServiceWithoutColumnMappingsIsUntouched() {
        val s = linkedMapOf<String, Any?>("key" to "restService", "columns" to emptyList<Any?>())
        val result = linkedMapOf<String, Any?>("services" to mutableListOf(s))
        CrossedColumns.apply(result)
        assertNull("nothing to say about a service that maps no column", s["crossedColumns"])
    }
}
