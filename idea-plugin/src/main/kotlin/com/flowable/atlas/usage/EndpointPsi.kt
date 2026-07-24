package com.flowable.atlas.usage

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner

/**
 * Recognises Spring REST endpoints in Java PSI — the live counterpart to the `:core` text heuristic in
 * `JavaParser` (`@GetMapping`/`@PostMapping`/… on a handler method, with the class-level
 * `@RequestMapping` prepended as a base path). Used to link an endpoint to the Flowable models whose
 * HTTP service tasks call its URL (see [FlowableEndpointUsageLineMarkerProvider]).
 *
 * Annotations are matched by their written **short name** (not a resolved [PsiClass]), so detection
 * still works when Spring Web is not on the module classpath — mirroring how [BotPsi] reads the
 * `implements` names. Path attributes are resolved through the PSI constant evaluator, so a base or
 * mapping given as a **constant** (`@RequestMapping(ApiPaths.CUSTOMERS)`) or a **concatenation**
 * (`"/api/" + V`) resolves too, not only string literals; `${...}`/`{{...}}` placeholders survive
 * verbatim and are matched as wildcards by [JavaParser.matchRest].
 */
object EndpointPsi {

    /** A resolved handler endpoint: the full request [path] (base + method mapping) and the HTTP [verb]. */
    data class Endpoint(val path: String, val verb: String)

    private val METHOD_MAPPINGS = mapOf(
        "GetMapping" to "GET", "PostMapping" to "POST", "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE", "PatchMapping" to "PATCH", "RequestMapping" to "ANY",
    )
    private val REQUEST_METHOD = Regex("""RequestMethod\.(\w+)""")

    /**
     * Every endpoint [method] handles — the cross-product of the class base path(s) and the method
     * mapping path(s), so annotation arrays (`@GetMapping({"/a","/b"})`) yield one entry each. Empty
     * when the method carries no `@*Mapping`.
     */
    fun endpointsOf(method: PsiMethod): List<Endpoint> {
        val mapping = mappingAnnotation(method) ?: return emptyList()
        val verb = verbOf(mapping)
        val bases = classBasePaths(method.containingClass).ifEmpty { listOf("") }
        val paths = pathValues(mapping.annotation).ifEmpty { listOf("") }
        val out = LinkedHashSet<Endpoint>()
        for (base in bases) for (path in paths) out.add(Endpoint(joinPath(base, path), verb))
        return out.toList()
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

    /** The HTTP verb: the mapping's own verb, refined from `method = RequestMethod.X` for `@RequestMapping`. */
    private fun verbOf(mapping: Mapping): String {
        if (mapping.shortName != "RequestMapping") return METHOD_MAPPINGS.getValue(mapping.shortName)
        val methodAttr = mapping.annotation.parameterList.attributes.firstOrNull { it.name == "method" }?.value
        return methodAttr?.text?.let { REQUEST_METHOD.find(it)?.groupValues?.get(1) } ?: "ANY"
    }

    /** The class-level base path(s): a direct `@RequestMapping`, else one bundled by a meta-annotation. */
    private fun classBasePaths(cls: PsiClass?): List<String> {
        cls ?: return emptyList()
        val ann = requestMappingAnnotation(cls) ?: metaRequestMapping(cls) ?: return emptyList()
        return pathValues(ann)
    }

    private fun requestMappingAnnotation(cls: PsiClass): PsiAnnotation? =
        cls.annotations.firstOrNull { shortName(it) == "RequestMapping" }

    /** `@RequestMapping` bundled on one of the class's own annotations (a composed `@XxxController`). */
    private fun metaRequestMapping(cls: PsiClass): PsiAnnotation? {
        for (ann in cls.annotations) {
            val meta = ann.resolveAnnotationType()?.let { requestMappingAnnotation(it) }
            if (meta != null) return meta
        }
        return null
    }

    /** The annotation's simple name, resolving the FQN when Spring is on the classpath, else the
     *  written reference name (so `@GetMapping` is recognised even without the library present). */
    private fun shortName(ann: PsiAnnotation): String? =
        (ann.qualifiedName ?: ann.nameReferenceElement?.referenceName)?.substringAfterLast('.')

    /** The `value`/`path` attribute resolved to string(s) — every element of an array, each through the
     *  constant evaluator (literals, constant refs, concatenations); non-constant entries are dropped. */
    private fun pathValues(ann: PsiAnnotation): List<String> {
        val value = ann.parameterList.attributes
            .firstOrNull { it.name == null || it.name == "value" || it.name == "path" }
            ?.value ?: return emptyList()
        return stringValues(value, ann.project)
    }

    private fun stringValues(value: PsiAnnotationMemberValue, project: Project): List<String> = when (value) {
        is PsiArrayInitializerMemberValue -> value.initializers.flatMap { stringValues(it, project) }
        is PsiExpression -> listOfNotNull(
            JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(value) as? String,
        )
        else -> emptyList()
    }

    /** Joins a class base path and a method path exactly as `JavaParser` builds `endpoints[].path`. */
    private fun joinPath(base: String, path: String): String =
        "/" + "$base/$path".split("/").filter { it.isNotEmpty() }.joinToString("/")
}
