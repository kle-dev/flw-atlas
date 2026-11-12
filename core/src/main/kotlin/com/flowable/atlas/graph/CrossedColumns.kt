package com.flowable.atlas.graph

import com.flowable.atlas.model.Dyn

/**
 * Column mappings that point at the wrong column.
 *
 * A `.service` model pairs a logical field with a physical column — `userName` ↔ `USER_NAME_`. Nothing
 * validates that pairing: the column exists, the field exists, every name is spelled correctly, so
 * [LiquibaseCoverage] reports the chain as fully mapped. Two fields whose columns were entered the
 * other way round —
 *
 * ```
 * {"name": "userName",  "columnName": "FIRST_NAME_"}
 * {"name": "firstName", "columnName": "USER_NAME_"}
 * ```
 *
 * — therefore produce no finding anywhere, while at runtime every read and every write silently uses
 * the other field's column. It is the worst kind of defect Atlas can see and used to stay quiet about:
 * invisible in the models, invisible in the schema, and visible in production as wrong data.
 *
 * The evidence is that the *names already say* what the pairing should be. A field mapped to a column
 * of a different name is ordinary (`customerName` ↔ `NAME_`); a field mapped to a different column
 * while the column its own name points at exists in the same table is not. Two flavours of that:
 *
 *  - **swapped / rotated** — the mappings form a closed cycle: `userName` takes `firstName`'s column
 *    and `firstName` takes `userName`'s. The field names and the column names are the same set, paired
 *    wrongly, which no naming convention explains. Reported as an error.
 *  - **crossed** — one direction only: the field's own column exists in the table (mapped by another
 *    field, or by none) and the field maps something else. Usually the same mistake, occasionally a
 *    deliberate mapping onto a legacy column, so it is reported as a warning.
 *
 * Names are compared the way the rest of the schema pass compares them (case- and separator-blind, see
 * [loose]), because `userName` and `USER_NAME_` are the same name in two conventions.
 *
 * Runs after [LiquibaseCoverage], whose `schemaCoverage` rows are where the table's *unmapped* columns
 * come from; the cycle half needs nothing but the `.service` model itself.
 */
object CrossedColumns {

    /** Attaches `crossedColumns` to every service that has one. Mutates [result] in place. */
    fun apply(result: MutableMap<String, Any?>) {
        for (s in Dyn.mutableMaps(result["services"])) {
            val found = analyze(s)
            if (found.isNotEmpty()) s["crossedColumns"] = found
        }
    }

    private val NON_ALNUM_RE = Regex("[^a-z0-9]")

    /** Same normalisation as `_loose` in the Liquibase pass: `userName`, `USER_NAME_` → `username`. */
    private fun loose(s: String?): String = NON_ALNUM_RE.replace((s ?: "").lowercase(), "")

    /** One `columnMappings[]` entry, with both names pre-normalised. */
    private class Mapping(val field: String, val column: String) {
        val lf = loose(field)
        val lc = loose(column)

        /** The field and its column are the same name in two conventions — nothing to say about it. */
        val aligned get() = lf == lc
    }

    /**
     * The crossings of one service, cycles first, each group `{kind, mappings[{field, column}]}` plus
     * `expected`/`otherField` for a one-directional cross.
     */
    private fun analyze(s: Map<String, Any?>): List<Map<String, Any?>> {
        val mappings = Dyn.maps(s["columns"]).mapNotNull { c ->
            val field = (c["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val column = (c["columnName"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Mapping(field, column)
        }
        if (mappings.size < 2) return emptyList()

        // loose name -> the mapping that owns it. First wins: two fields on one column (or one name
        // twice) is a different defect, and picking either of them keeps this pass deterministic.
        val byField = LinkedHashMap<String, Int>()
        val byColumn = LinkedHashMap<String, Int>()
        for ((i, m) in mappings.withIndex()) {
            byField.putIfAbsent(m.lf, i)
            byColumn.putIfAbsent(m.lc, i)
        }

        // `chain[i]` = the mapping whose *field* is named like the column *i* maps — so a walk reads as
        // "alpha takes beta's column, beta takes gamma's, gamma takes alpha's". A closed walk is a
        // permutation of one name set: the pairing is rotated, nothing is missing.
        val chain = IntArray(mappings.size) { i -> byField[mappings[i].lc]?.takeIf { it != i } ?: -1 }

        val groups = ArrayList<Map<String, Any?>>()
        val inCycle = HashSet<Int>()
        for (cycle in cycles(chain)) {
            if (cycle.any { mappings[it].aligned }) continue    // an aligned mapping only loops onto itself
            inCycle.addAll(cycle)
            groups.add(linkedMapOf(
                "kind" to if (cycle.size == 2) "swapped" else "rotated",
                "mappings" to cycle.map { pair(mappings[it]) },
            ))
        }

        // The table's own columns, for the one-directional case: a field can be named after a column no
        // mapping mentions at all. Only rows of *this* service's table count — the coverage pass falls
        // back to every column of the changelog when it cannot match the table, and a column of some
        // other table says nothing about this mapping.
        val table = (s["tableName"] as? String)?.uppercase()?.takeIf { it.isNotBlank() }
        val schemaColumns = LinkedHashMap<String, String>()
        if (table != null) {
            for (r in Dyn.maps(Dyn.map(s["schemaCoverage"])["rows"])) {
                if (r["inLiquibase"] != true) continue
                if ((r["table"] as? String)?.uppercase() != table) continue
                val sql = (r["sql"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                schemaColumns.putIfAbsent(loose(sql), sql)
            }
        }

        for ((i, m) in mappings.withIndex()) {
            if (m.aligned || i in inCycle) continue
            // The other direction of the same question: who owns the column *this* field is named after.
            val other = byColumn[m.lf]?.takeIf { it != i } ?: -1
            val expected = if (other >= 0) mappings[other].column else schemaColumns[m.lf] ?: continue
            val g = linkedMapOf<String, Any?>(
                "kind" to "crossed",
                "mappings" to listOf(pair(m)),
                "expected" to expected,
            )
            if (other >= 0) g["otherField"] = mappings[other].field
            groups.add(g)
        }
        return groups
    }

    private fun pair(m: Mapping): Map<String, Any?> = linkedMapOf("field" to m.field, "column" to m.column)

    /**
     * Every cycle of the functional graph [next] (`-1` = no successor), each returned once, starting at
     * the member the walk reached first. Self-loops are not cycles here — a mapping whose field and
     * column are the same name points at itself and means nothing.
     */
    private fun cycles(next: IntArray): List<List<Int>> {
        val UNSEEN = 0; val WALKING = 1; val DONE = 2
        val state = IntArray(next.size)
        val found = ArrayList<List<Int>>()
        for (start in next.indices) {
            if (state[start] != UNSEEN) continue
            val path = ArrayList<Int>()
            var cur = start
            while (cur != -1 && state[cur] == UNSEEN) {
                state[cur] = WALKING
                path.add(cur)
                cur = next[cur]
            }
            // Landed on a node of *this* walk: everything from it onwards is the cycle.
            if (cur != -1 && state[cur] == WALKING) {
                val at = path.indexOf(cur)
                if (path.size - at >= 2) found.add(path.subList(at, path.size).toList())
            }
            for (i in path) state[i] = DONE
        }
        return found
    }
}
