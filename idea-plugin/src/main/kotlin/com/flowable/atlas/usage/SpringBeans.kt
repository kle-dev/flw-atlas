package com.flowable.atlas.usage

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * The Spring beans the project declares, by name — what a model's `${bean…}` can actually reach. A bean is
 * a class with a stereotype (`@Service`, `@Component`, `@Controller`, `@Configuration`, `@Named`, …,
 * named explicitly or after the class), the type a `@Bean` method returns (named after the method or the
 * annotation), or a Spring Data repository interface. The same rules as the `:core` resolver, so the
 * gutter, Find Usages and navigation agree with the explorer.
 *
 * Matching a model name against class names instead (`orderService` → any class `OrderService`) linked a
 * variable `order` to a POJO `Order`, ignored `@Service("other")` and never found a `@Bean` method's bean.
 * Annotations are read by their written short name, so this works without Spring on the classpath.
 */
object SpringBeans {

    private val STEREOTYPES = setOf("Component", "Service", "Repository", "Named", "Configuration", "Controller", "RestController")

    /** Every bean name → the classes declaring a bean of that name, cached until PSI changes. Empty while indexing. */
    fun byName(project: Project): Map<String, List<PsiClass>> {
        if (DumbService.isDumb(project)) return emptyMap()
        return CachedValuesManager.getManager(project).getCachedValue(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val out = LinkedHashMap<String, MutableList<PsiClass>>()
            fun add(name: String?, cls: PsiClass?) {
                if (name.isNullOrBlank() || cls == null) return
                val list = out.getOrPut(name) { ArrayList() }
                if (cls !in list) list.add(cls)
            }
            for (stereotype in STEREOTYPES) {
                for (ann in JavaAnnotationIndex.getInstance().getAnnotations(stereotype, project, scope)) {
                    val cls = ann.parent?.parent as? PsiClass ?: continue
                    add(explicitName(ann) ?: decap(cls.name), cls)
                }
            }
            for (ann in JavaAnnotationIndex.getInstance().getAnnotations("Bean", project, scope)) {
                val method = ann.parent?.parent as? PsiMethod ?: continue
                val type = (method.returnType as? PsiClassType)?.resolve() ?: continue
                add(explicitName(ann) ?: method.name, type)
            }
            CachedValueProvider.Result.create(out as Map<String, List<PsiClass>>, PsiModificationTracker.getInstance(project))
        }
    }

    /** The bean names a model can reach [cls] by — its own, and those of the project classes that extend or
     *  implement it (a bean of `PaymentServiceImpl` is a `PaymentService` too). */
    fun namesOf(cls: PsiClass): Set<String> {
        val project = cls.project
        val all = byName(project)
        if (all.isEmpty()) return emptySet()
        val types = LinkedHashSet<PsiClass>().apply {
            add(cls)
            ClassInheritorsSearch.search(cls, GlobalSearchScope.projectScope(project), true).findAll().forEach { add(it) }
        }
        val names = LinkedHashSet(all.filterValues { beans -> beans.any { it in types } }.keys)
        for (t in types) if (isRepository(t)) decap(t.name)?.let { names.add(it) }
        return names
    }

    /** The project classes a model's bean name [name] reaches: a declared bean of that name, or the Spring
     *  Data repository interface it is named after. Nothing for a name no bean has — a variable. */
    fun classesNamed(name: String, project: Project): List<PsiClass> {
        byName(project)[name]?.let { return it }
        if (DumbService.isDumb(project)) return emptyList()
        val cap = name.replaceFirstChar { it.uppercaseChar() }
        return PsiShortNamesCache.getInstance(project).getClassesByName(cap, GlobalSearchScope.projectScope(project))
            .filter(::isRepository)
    }

    /** A Spring Data repository: an interface extending one whose name ends in `Repository`. */
    private fun isRepository(cls: PsiClass): Boolean =
        cls.isInterface && cls.extendsListTypes.any { it.className.endsWith("Repository") }

    private fun explicitName(ann: PsiAnnotation): String? {
        val v = ann.parameterList.attributes.firstOrNull { it.name == null || it.name == "value" || it.name == "name" }?.value
        return (v as? PsiLiteralExpression)?.value as? String
    }

    private fun decap(name: String?): String? = name?.replaceFirstChar { it.lowercaseChar() }
}
