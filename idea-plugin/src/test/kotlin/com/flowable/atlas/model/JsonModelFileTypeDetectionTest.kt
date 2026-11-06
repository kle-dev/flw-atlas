package com.flowable.atlas.model

import com.intellij.json.JsonFileType
import com.intellij.openapi.util.io.ByteArraySequence
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A model extension that means something else outside Flowable is JSON only when the file holds a JSON
 * object: the mapping is IDE-wide, and a name-based one made a Helm `_helpers.tpl` or a systemd unit in
 * any project a JSON file full of errors. DEMO-* names: the repo is public.
 */
class JsonModelFileTypeDetectionTest : BasePlatformTestCase() {

    fun testAHelmTemplateIsNotJson() {
        val helm = myFixture.addFileToProject(
            "chart/templates/_helpers.tpl",
            "{{/* Expand the name of the chart. */}}\n{{- define \"demo.name\" -}}{{- end -}}\n",
        )
        assertNotSame(JsonFileType.INSTANCE, helm.virtualFile.fileType)
    }

    fun testATemplateModelIsJson() {
        val model = myFixture.addFileToProject("models/DEMO-T1.tpl", "﻿  {\n  \"key\": \"DEMO-T1\" }")
        assertSame(JsonFileType.INSTANCE, model.virtualFile.fileType)
    }

    fun testASystemdUnitIsNotJson() {
        val unit = myFixture.addFileToProject("deploy/DEMO.service", "[Unit]\nDescription=Demo\n")
        assertNotSame(JsonFileType.INSTANCE, unit.virtualFile.fileType)
    }

    fun testADataObjectIsJson() {
        val model = myFixture.addFileToProject("models/DEMO-D1.data", """{"key":"DEMO-D1","name":"Demo"}""")
        assertSame(JsonFileType.INSTANCE, model.virtualFile.fileType)
    }

    fun testAnUnambiguousExtensionIsJsonByName() {
        val form = myFixture.addFileToProject("models/DEMO-F1.form", "")
        assertSame("even empty", JsonFileType.INSTANCE, form.virtualFile.fileType)
    }

    /** A model opened out of an app export goes through the same detection as a loose one. */
    fun testAnArchiveEntryIsDetected() {
        val dir = FileUtil.createTempDirectory("atlas-json-detect", null)
        try {
            val zip = File(dir, "DEMO-app.bar")
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("DEMO-P1.page"))
                out.write("""{"key":"DEMO-P1","name":"Demo page"}""".toByteArray())
                out.closeEntry()
            }
            val local = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(zip)!!
            val entry = JarFileSystem.getInstance().getJarRootForLocalFile(local)!!.findChild("DEMO-P1.page")!!
            assertSame(JsonFileType.INSTANCE, entry.fileType)
        } finally {
            FileUtil.delete(dir)
        }
    }

    fun testTheStartOfAJsonObject() {
        fun json(s: String) = FlowableJsonModelTypeDetector.looksLikeJsonObject(ByteArraySequence(s.toByteArray()))
        assertTrue(json("{}"))
        assertTrue(json("\n\t{\r\n  \"key\": 1}"))
        assertFalse("a Helm action", json("{{- define \"x\" -}}"))
        assertFalse("a Helm comment", json("{{/* c */}}"))
        assertFalse(json("{ key: 1 }"))
        assertFalse(json("[Unit]"))
        assertFalse(json(""))
        assertFalse(json("   {"))
    }

    /** The descriptor's name-mapped list and the detector's must not drift: a model extension in neither
     *  would open as an unassociated binary file again. */
    fun testEveryJsonModelExtensionIsMappedOrDetected() {
        val descriptor = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        val mapped = Regex("""<fileType name="JSON"\s+extensions="([^"]+)"""").find(descriptor)!!.groupValues[1]
            .split(';').map { ".$it" }.toSet()
        assertEquals(FlowableJsonModelTypeDetector.NAME_MAPPED, mapped)
        val json = ModelType.EXTENSIONS.map { it.first }.filterNot { ModelType.isXmlModel("x$it") }.toSet()
        assertEquals(json, mapped + FlowableJsonModelTypeDetector.CONTENT_DETECTED)
        assertTrue(FlowableJsonModelTypeDetector.CONTENT_DETECTED.none { it in mapped })
    }
}
