package com.flowable.atlas.usage

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner

/**
 * Recognises a Spring REST endpoint in Java PSI — the live counterpart to the `:core` text heuristic
 * in `JavaParser` (`@GetMapping`/`@PostMapping`/… on a handler method, with the class-level
 * `@RequestMapping` prepended as a base path). Used to link an endpoint to the Flowable models whose
 * HTTP service tasks call its URL (see [FlowableEndpointUsageLineMarkerProvider]).
 *
 * Annotations are matched by their written **short name** (not a resolved [PsiClass]), so detection
 * still works when Spring Web is not on the module classpath — mirroring how [BotPsi] reads the
 * `implements` names and how the `:core` parser scans the source text.
 */
object EndpointPsi {

    /** A resolved handler endpoint: the full request [path] (base + method mapping) and the HTTP [verb]. */
    data class Endpoint(val path: String, val verb: String)

    private val METHOD_MAPPINGS = mapOf(
        "GetMapping" to "GET", "PostMapping" to "POST", "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE", "PatchMapping" to "PATCH", "RequestMapping" to "ANY",
    )

    /** The endpoint [method] handles, or null when it carries no `@*Mapping` annotation. */
    fun endpointOf(method: PsiMethod): Endpoint? {
        val mapping = mappingAnnotation(method) ?: return null
        val verb = METHOD_MAPPINGS[mapping.shortName] ?: "ANY"
        val base = method.containingClass?.let { requestMappingAnnotation(it)?.let(::pathValue) }.orEmpty()
        val path = pathValue(mapping.annotation).orEmpty()
        return Endpoint(joinPath(base, path), verb)
    }

    /** True when [method]'s name identifier is a REST handler — the gutter-marker fast-path guard. */
    fun isEndpointMethod(method: PsiMethod): Boolean = mappingAnnotation(method) != null

    private data class Mapping(val annotation: PsiAnnotation, val shortName: String)

    private fun mappingAnnotation(owner: PsiModifierListOwner): Mapping? {
        for (ann in owner.annotations) {
            val short = shortName(ann) ?: continue
            if (short in METHOD_MAPPINGS) return Mapping(ann, short)
        }
        return null
    }

    private fun requestMappingAnnotation(cls: PsiClass): PsiAnnotation? =
        cls.annotations.firstOrNull { shortName(it) == "RequestMapping" }

    /** The annotation's simple name, resolving the FQN when Spring is on the classpath, else the
     *  written reference name (so `@GetMapping` is recognised even without the library present). */
    private fun shortName(ann: PsiAnnotation): String? =
        (ann.qualifiedName ?: ann.nameReferenceElement?.referenceName)?.substringAfterLast('.')

    /** The first string literal of the annotation's `value`/`path` attribute, or null (constant/SpEL → skip). */
    private fun pathValue(ann: PsiAnnotation): String? {
        val value = ann.parameterList.attributes
            .firstOrNull { it.name == null || it.name == "value" || it.name == "path" }
            ?.value
        return literalString(value)
    }

    private fun literalString(value: PsiAnnotationMemberValue?): String? = when (value) {
        is PsiLiteralExpression -> value.value as? String
        is PsiArrayInitializerMemberValue -> value.initializers.firstNotNullOfOrNull { literalString(it) }
        else -> null
    }

    /** Joins a class base path and a method path exactly as `JavaParser` builds `endpoints[].path`. */
    private fun joinPath(base: String, path: String): String =
        "/" + "$base/$path".split("/").filter { it.isNotEmpty() }.joinToString("/")
}
