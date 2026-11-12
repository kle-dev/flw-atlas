package com.flowable.atlas.graph

/**
 * What a type name written in a Java or Kotlin source means, resolved the way the compiler resolves it as
 * far as a text scan can: an explicit import, then the file's own package, then a wildcard import. Matching
 * the simple name against every project class instead tied `org.flowable.task.api.Task` to the project's own
 * `com.example.model.Task`, and a `@Bean` returning a library type to a project class of that name.
 */
internal object JavaTypes {

    /** The project class [name] denotes in the source parsed as [jc], or null when it denotes none — an
     *  import of a library type, or no project class of that name where the file can see one. [known] is
     *  the set of project FQNs. */
    fun resolve(name: String, jc: Map<String, Any?>, known: Set<String>): String? {
        if ('.' in name) return name.takeIf { it in known }
        (jc["imports"] as? Map<*, *>)?.get(name)?.let { imported -> return (imported as String).takeIf { it in known } }
        val pkg = jc["package"] as? String ?: ""
        val same = if (pkg.isEmpty()) name else "$pkg.$name"
        if (same in known) return same
        return (jc["wildcardImports"] as? List<*>).orEmpty().map { "$it.$name" }.singleOrNull { it in known }
    }
}
