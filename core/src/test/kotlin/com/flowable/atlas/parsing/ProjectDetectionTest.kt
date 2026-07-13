package com.flowable.atlas.parsing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Heuristic project-folder detection for the multi-project (monorepo) case. Granularity is the
 * first-level folder under the root, so build submodules inside one project are grouped, not split.
 * Fixtures are built in a temp directory because detection is purely name/extension based — file
 * contents never matter.
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
    fun `each top-level project folder is a separate sub-project`() {
        val root = tempRoot(
            "order-app/src/main/resources/processes/order.bpmn20.xml",
            "billing-app/models/review.cmmn.xml",
        )
        assertEquals(listOf("billing-app", "order-app"), paths(root))
    }

    @Test
    fun `a project folder split into build submodules is ONE sub-project, not its submodules`() {
        val root = tempRoot(
            "myproject/work/pom.xml",
            "myproject/work/src/main/resources/app/myapp.app",
            "myproject/work/src/main/resources/processes/order.bpmn20.xml",
            "myproject/models/pom.xml",
            "myproject/models/review.cmmn.xml",
        )
        assertEquals(listOf("myproject"), paths(root))
    }

    @Test
    fun `a top-level folder without Flowable models is not a sub-project`() {
        val root = tempRoot(
            "order-app/order.bpmn20.xml",
            "docs/readme.md",                         // no models
            "shared-lib/src/main/java/Foo.java",      // Java only, no models
        )
        assertEquals(listOf("order-app"), paths(root))
    }

    @Test
    fun `models nested arbitrarily deep still resolve to their top-level project folder`() {
        val root = tempRoot(
            "svc/a/b/c/deep.dmn.xml",
        )
        assertEquals(listOf("svc"), paths(root))
    }

    @Test
    fun `design-export monorepo uses the top-level folders`() {
        val root = tempRoot(
            "app-one/app-one.app",
            "app-one/bpmn-models/p.bpmn20.xml",
            "app-two/app-two.app",
            "app-two/cmmn-models/c.cmmn.xml",
        )
        assertEquals(listOf("app-one", "app-two"), paths(root))
    }

    @Test
    fun `a single Gradle multi-module project (root settings file) is not split into its modules`() {
        val root = tempRoot(
            "settings.gradle.kts",                    // root Gradle build → the root IS one project
            "module-a/a.bpmn20.xml",
            "module-b/b.cmmn.xml",
            "module-c/c.form",
        )
        assertEquals(emptyList<String>(), paths(root))
    }

    @Test
    fun `a single Maven multi-module project (root pom) is not split into its modules`() {
        val root = tempRoot(
            "pom.xml",                                // root aggregator → the root IS one project
            "work/order.bpmn20.xml",
            "models/review.cmmn.xml",
        )
        assertEquals(emptyList<String>(), paths(root))
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
            "svc/src/main/resources/order.bpmn20.xml",
            "svc/target/classes/order.bpmn20.xml",    // build output — must be ignored
        )
        val detected = ProjectDetection.detect(root)
        assertEquals(listOf("svc"), detected.map { it.relPath })
        assertEquals(1, detected.single().modelCount)   // only the source model, not the copy under target/
    }

    @Test
    fun `counts aggregate a whole project folder and distinguish apps`() {
        val root = tempRoot(
            "svc/work/svc.app",
            "svc/work/a.bpmn20.xml",
            "svc/models/b.cmmn.xml",
        )
        val sp = ProjectDetection.detect(root).single()
        assertEquals("svc", sp.relPath)
        assertEquals(1, sp.appCount)
        assertEquals(3, sp.modelCount)   // app + bpmn + cmmn, across both submodules
    }
}
