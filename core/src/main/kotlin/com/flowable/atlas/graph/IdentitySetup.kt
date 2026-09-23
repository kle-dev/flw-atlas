package com.flowable.atlas.graph

import com.flowable.atlas.model.MiniJson
import java.io.File

/**
 * The platform's identity files a developer hand-places in the project: user definitions (a
 * `.user.json` under `com/flowable/users/custom/` — what kind of user, which forms create, show and edit
 * one, which groups a user of that kind joins) and tenant setups (the JSON under
 * `com/flowable/tenant-setup/` — the groups and users a tenant starts with, each of a user definition).
 *
 * Relationships only, drawn after the graph is built: a user definition to the forms and groups it
 * names, a tenant setup to the user definitions and groups it sets up, and a master-data definition to
 * the files under `com/flowable/master-data/` that load its rows. A form or user definition the
 * project does not contain is not reported: the platform ships its own (`F01_userInitFormDefault`,
 * `user-default`), and nothing here can tell those from a typo.
 */
internal object IdentitySetup {

    /** A user-definition, tenant-setup or master-data instance file, by its path relative to the project. */
    fun isIdentityFile(relPath: String): Boolean {
        val low = relPath.lowercase()
        if (!low.endsWith(".json")) return false
        return low.endsWith(".user.json") || low.contains("/tenant-setup/") || low.substringAfterLast('/').contains("tenant-setup") ||
            isMasterDataRows(low)
    }

    /** `com/flowable/master-data/custom/x.data.json`: the rows a master-data definition is loaded with. */
    private fun isMasterDataRows(low: String) = low.endsWith(".data.json") && low.contains("master-data/")

    private val FORM_RELS = linkedMapOf("init" to "user-init-form", "view" to "user-view-form", "edit" to "user-edit-form")

    @Suppress("UNCHECKED_CAST")
    fun apply(result: Map<String, Any?>, files: List<File>, relOf: (File) -> String) {
        val graph = result["graph"] as? MutableMap<String, Any?> ?: return
        val nodes = graph["nodes"] as? MutableList<Any?> ?: return
        val edges = graph["edges"] as? MutableList<Any?> ?: return
        val ids = nodes.mapNotNullTo(HashSet()) { (it as? Map<*, *>)?.get("id") as? String }
        fun addNode(id: String, type: String, key: String, label: String, file: String?, data: Map<String, Any?>) {
            if (ids.add(id)) nodes.add(linkedMapOf("id" to id, "type" to type, "label" to label, "key" to key, "file" to file, "data" to data))
        }
        fun addEdge(s: String, t: String, rel: String) {
            if (s in ids && t in ids) edges.add(linkedMapOf("s" to s, "t" to t, "rel" to rel))
        }
        fun group(g: String) = "group:$g".also { if (it !in ids) addNode(it, "group", g, g, null, linkedMapOf()) }

        val parsed = files.mapNotNull { f ->
            if (f.length() > Atlas.MAX_MODEL_BYTES) return@mapNotNull null
            val json = runCatching { MiniJson.parse(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return@mapNotNull null
            Triple(f, relOf(f), json)
        }
        // User definitions first, so a tenant setup's users can point at them.
        for ((_, rel, json) in parsed.filter { it.second.lowercase().endsWith(".user.json") }) {
            val defs = (json as? List<*>) ?: listOf(json)
            for (d in defs) {
                val def = d as? Map<String, Any?> ?: continue
                val key = def["key"] as? String ?: continue
                val forms = (def["forms"] as? Map<String, Any?>).orEmpty().filterValues { it is String && it.isNotBlank() }
                val member = strings(def["memberGroups"])
                val lookup = strings(def["lookupGroups"])
                val id = "userDefinition:$key"
                addNode(id, "userDefinition", key, def["name"] as? String ?: key, rel, linkedMapOf(
                    "name" to def["name"], "description" to def["description"],
                    "userType" to def["initialUserType"], "userSubType" to def["initialUserSubType"],
                    "forms" to forms, "memberGroups" to member, "lookupGroups" to lookup,
                ))
                for ((slot, rel2) in FORM_RELS) (forms[slot] as? String)?.let { addEdge(id, "form:$it", rel2) }
                for (g in member) addEdge(id, group(g), "member-of")
                for (g in lookup) addEdge(id, group(g), "looks-up")
            }
        }
        // Master-data rows: which file loads a definition's entries, and how many. Rows of a definition the
        // project does not contain are left alone — the platform ships master data of its own.
        for ((_, rel, json) in parsed.filter { isMasterDataRows(it.second.lowercase()) }) {
            val rows = json as? Map<String, Any?> ?: continue
            val key = rows["dataObjectDefinitionKey"] as? String ?: continue
            val node = nodes.firstOrNull { (it as? Map<*, *>)?.get("id") == "masterData:$key" } as? MutableMap<String, Any?> ?: continue
            val data = node["data"] as? MutableMap<String, Any?> ?: continue
            val loaded = (data["loadedFrom"] as? List<Map<String, Any?>>).orEmpty()
            data["loadedFrom"] = loaded + linkedMapOf("file" to rel, "rows" to (rows["masterData"] as? List<*>)?.size)
        }
        for ((f, rel, json) in parsed.filter { !it.second.lowercase().endsWith(".user.json") && !isMasterDataRows(it.second.lowercase()) }) {
            val setup = json as? Map<String, Any?> ?: continue
            val users = (setup["users"] as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }
            val groups = (setup["groups"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("key") as? String }
            if (users.isEmpty() && groups.isEmpty()) continue
            val key = f.name.removeSuffix(".json")
            val id = "tenantSetup:$key"
            // How many users of each definition, never who: a setup carries logins, e-mail addresses and
            // at times passwords, and none of that belongs in a page a team passes around.
            val perDefinition = users.groupingBy { it["userDefinitionKey"] as? String ?: "" }.eachCount().filterKeys { it.isNotEmpty() }
            addNode(id, "tenantSetup", key, setup["name"] as? String ?: key, rel, linkedMapOf(
                "name" to setup["name"], "groups" to groups, "userCount" to users.size, "usersPerDefinition" to perDefinition,
            ))
            for (g in groups) addEdge(id, group(g), "defines-group")
            for (udk in users.mapNotNull { it["userDefinitionKey"] as? String }.distinct()) addEdge(id, "userDefinition:$udk", "sets-up-users-of")
        }
    }

    private fun strings(v: Any?): List<String> = (v as? List<*>).orEmpty().filterIsInstance<String>().filter { it.isNotBlank() }
}
