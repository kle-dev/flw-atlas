package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Heuristic sub-project detection for the multi-project (monorepo) case. Fixtures are built in a temp
 * directory because detection is purely name/extension based — file contents never matter.
 */
class ProjectDetectionTest {

    private fun tempRoot(vararg relPaths: String): File {
        val root = Files.createTempDirectory("atlas-detect-test").toFile()
        for (rel in relPaths) {
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText("")
        }
        return root
    }

    private fun paths(root: File): List<String> = ProjectDetection.detect(root).map { it.relPath }

    @Test
    fun `two build modules with models are separate sub-projects`() {
        val root = tempRoot(
            "service-a/pom.xml",
            "service-a/src/main/resources/app/service-a.app",
            "service-a/src/main/resources/processes/order.bpmn20.xml",
            "service-b/build.gradle.kts",
            "service-b/models/review.cmmn.xml",
        )
        assertEquals(listOf("service-a", "service-b"), paths(root))
    }

    @Test
    fun `a build module without any model is not a sub-project`() {
        val root = tempRoot(
            "service-a/pom.xml",
            "service-a/order.bpmn20.xml",
            "shared-lib/pom.xml",           // library module, no Flowable models
            "shared-lib/src/main/java/Foo.java",
        )
        assertEquals(listOf("service-a"), paths(root))
    }

    @Test
    fun `models nested in a module resolve to the module root, not the leaf folder`() {
        val root = tempRoot(
            "svc/build.gradle",
            "svc/a/b/c/deep.dmn.xml",
        )
        assertEquals(listOf("svc"), paths(root))
    }

    @Test
    fun `aggregator pom with no direct models is excluded, its children are kept`() {
        val root = tempRoot(
            "pom.xml",                       // repo-root aggregator, no models of its own
            "services/pom.xml",              // intermediate aggregator, no models of its own
            "services/orders/pom.xml",
            "services/orders/order.bpmn20.xml",
            "services/billing/pom.xml",
            "services/billing/bill.bpmn20.xml",
        )
        assertEquals(listOf("services/billing", "services/orders"), paths(root))
    }

    @Test
    fun `design-export monorepo without build files uses first-level folders`() {
        val root = tempRoot(
            "app-one/app-one.app",
            "app-one/bpmn-models/p.bpmn20.xml",
            "app-two/app-two.app",
            "app-two/cmmn-models/c.cmmn.xml",
        )
        assertEquals(listOf("app-one", "app-two"), paths(root))
    }

    @Test
    fun `models at the root itself are the whole-project case, not a sub-project`() {
        val root = tempRoot(
            "order.bpmn20.xml",
            "customer.form",
        )
        assertEquals(emptyList<String>(), paths(root))
    }

    @Test
    fun `build-output directories are pruned from detection`() {
        val root = tempRoot(
            "svc/pom.xml",
            "svc/src/main/resources/order.bpmn20.xml",
            "svc/target/classes/order.bpmn20.xml",   // build output — must be ignored
        )
        val detected = ProjectDetection.detect(root)
        assertEquals(listOf("svc"), detected.map { it.relPath })
        assertEquals(1, detected.single().modelCount)   // only the source model, not the copy under target/
    }

    @Test
    fun `counts distinguish apps from other models`() {
        val root = tempRoot(
            "svc/pom.xml",
            "svc/svc.app",
            "svc/a.bpmn20.xml",
            "svc/b.cmmn.xml",
        )
        val sp = ProjectDetection.detect(root).single()
        assertEquals(1, sp.appCount)
        assertEquals(3, sp.modelCount)   // app + bpmn + cmmn
    }
}
