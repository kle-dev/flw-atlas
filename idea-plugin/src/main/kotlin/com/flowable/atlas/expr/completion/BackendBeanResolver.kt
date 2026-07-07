package com.flowable.atlas.expr.completion

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Resolves a backend expression receiver identifier (a Spring bean / typed object, e.g. `orderService`
 * in `${orderService.process(x)}`) to Java class(es) in the project and lists their callable members —
 * so completion after `.` can offer the bean's real methods and getter-derived properties.
 *
 * Resolution is by short name: `orderService` → a class named `OrderService` (the Spring default bean
 * name is the decapitalised simple class name), plus the identifier as-is. This is a pure PSI query
 * (no Spring-plugin dependency) and covers the common case; process *variables* are untyped in Flowable
 * so they intentionally resolve to nothing.
 */
object BackendBeanResolver {

    enum class Kind { METHOD, PROPERTY }

    /** A member offered after `receiver.`: a [Kind.METHOD] (`process`) or a getter-derived [Kind.PROPERTY] (`total`). */
    data class Member(val name: String, val kind: Kind, val typeText: String, val paramText: String?)

    /**
     * Classes the receiver identifier could denote (by capitalised / verbatim short name). [scope]
     * defaults to the whole project + libraries (for member completion); pass [GlobalSearchScope.projectScope]
     * when deciding whether an identifier is a *project bean* (so `list`/`date`/`process` don't match JDK types).
     */
    fun resolveClasses(receiver: String, project: Project, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<PsiClass> {
        if (receiver.isBlank()) return emptyList()
        val names = linkedSetOf(receiver.replaceFirstChar { it.uppercaseChar() }, receiver)
        val cache = PsiShortNamesCache.getInstance(project)
        return names.flatMap { cache.getClassesByName(it, scope).toList() }.distinct()
    }

    /** Public instance members of [cls] (methods once per name; getters also exposed as properties). */
    fun members(cls: PsiClass): List<Member> {
        val out = LinkedHashMap<String, Member>()
        for (m in cls.allMethods) {
            if (!m.hasModifierProperty(PsiModifier.PUBLIC)) continue
            if (m.hasModifierProperty(PsiModifier.STATIC)) continue
            if (m.isConstructor) continue
            if (m.containingClass?.qualifiedName == "java.lang.Object") continue
            val ret = m.returnType?.presentableText ?: "void"
            val params = m.parameterList.parameters.joinToString(", ") { it.type.presentableText }
            out.putIfAbsent("M:${m.name}", Member(m.name, Kind.METHOD, ret, "($params)"))
            propertyName(m)?.let { prop -> out.putIfAbsent("P:$prop", Member(prop, Kind.PROPERTY, ret, null)) }
        }
        return out.values.toList()
    }

    /** All members across every class the receiver could denote (deduplicated). */
    fun membersOf(receiver: String, project: Project): List<Member> {
        val seen = LinkedHashMap<String, Member>()
        for (cls in resolveClasses(receiver, project)) {
            for (m in members(cls)) seen.putIfAbsent("${m.kind}:${m.name}", m)
        }
        return seen.values.toList()
    }

    /** `getTotal`/`isActive` (no params) → `total`/`active`; else null. */
    private fun propertyName(m: PsiMethod): String? {
        if (m.parameterList.parametersCount != 0) return null
        val n = m.name
        val base = when {
            n.length > 3 && n.startsWith("get") && n[3].isUpperCase() -> n.substring(3)
            n.length > 2 && n.startsWith("is") && n[2].isUpperCase() && m.returnType?.presentableText in setOf("boolean", "Boolean") -> n.substring(2)
            else -> return null
        }
        return base.replaceFirstChar { it.lowercaseChar() }
    }
}
