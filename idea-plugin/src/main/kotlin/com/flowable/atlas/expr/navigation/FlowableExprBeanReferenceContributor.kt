package com.flowable.atlas.expr.navigation

import com.flowable.atlas.expr.BackendGrounding
import com.flowable.atlas.expr.ExpressionDialect
import com.flowable.atlas.expr.catalog.FlowableExpressionCatalog
import com.flowable.atlas.expr.lang.FlowableExprFile
import com.flowable.atlas.usage.SpringBeans
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext

/**
 * `${orderService.process(order)}` — Ctrl+click on `orderService` opens the class the Spring bean name
 * denotes, and Ctrl+Q shows that class's documentation; inside an injected expression in a model and in
 * the playground alike. The root resolves to the classes that declare a bean of that name in the project
 * ([SpringBeans] — a stereotype, a `@Bean` method's return type, a Spring Data repository), so `list`,
 * `date` or `process` light up no JDK type, and a variable `order` no class `Order`.
 *
 * Every backend root that is not a catalogued engine root gets a reference; one that is a process
 * variable resolves to nothing. The reference is soft for that reason: the grounding inspection is the
 * place that says a root is unknown, not a red underline here.
 *
 * The expression PSI is one leaf under the file, and neither exposes provider references by default;
 * [FlowableExprFile.getReferences] consults the providers, so this hangs off the file with
 * fragment-relative ranges.
 */
class FlowableExprBeanReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(FlowableExprFile::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val file = element as? FlowableExprFile ?: return PsiReference.EMPTY_ARRAY
                    val roots = BackendGrounding.rootReferences(file.text)
                    if (roots.isEmpty()) return PsiReference.EMPTY_ARRAY
                    val engineRoots = FlowableExpressionCatalog.rootNames(ExpressionDialect.BACKEND)
                    return roots
                        .filter { it.name.length > 1 && it.name !in engineRoots }
                        .map { BeanReference(file, TextRange(it.start, it.end), it.name) }
                        .toTypedArray()
                }
            },
        )
    }

    private class BeanReference(file: FlowableExprFile, range: TextRange, private val name: String) :
        PsiReferenceBase.Poly<FlowableExprFile>(file, range, true) {

        override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
            val project = element.project
            // a declared bean only: a variable `order` beside a plain class `Order` names no class, and
            // `@Service("other")` answers to `other`, not to its class's decapitalised name
            val classes = SpringBeans.classesNamed(name, project)
            return PsiElementResolveResult.createResults(classes)
        }

        override fun getVariants(): Array<Any> = emptyArray()

        // Renaming the class renames the bean: Spring's default name is the decapitalised simple name.
        override fun handleElementRename(newElementName: String): PsiElement =
            super.handleElementRename(newElementName.replaceFirstChar { it.lowercaseChar() })
    }
}
