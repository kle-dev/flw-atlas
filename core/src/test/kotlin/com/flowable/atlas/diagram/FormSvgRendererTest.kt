package com.flowable.atlas.diagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A form/page renders as a wireframe of its twelve-column grid — [FormLayout] keeps the positions the
 * graph's flat walk drops, [FormSvgRenderer] paints them. DEMO-* names — repo public.
 */
class FormSvgRendererTest {

    private fun bytes(path: String): ByteArray =
        File(javaClass.classLoader.getResource(path)!!.toURI()).readBytes()

    private val onboarding by lazy { bytes("formlayout/DEMO-onboarding.form") }
    private val svg by lazy { FormSvgRenderer.renderSvg(onboarding)!! }

    @Test
    fun layoutKeepsRowsSizesAndContainers() {
        val layout = FormLayout.parse(onboarding)!!
        assertEquals("Onboarding & review", layout.title)
        assertEquals(listOf(6, 6), layout.rows[0].cells.map { it.size })
        assertTrue(layout.rows[0].cells[0].required)

        val panel = layout.rows[1].cells.single()
        val inner = panel.sections.single().layout.rows.single().cells
        assertEquals(listOf("startDate", "remote", "notes"), inner.map { it.id })
        assertEquals("{{country == 'CH'}}", inner[1].visible)
        assertEquals(false, inner[2].enabled)
        assertEquals("a caption that exists only localised still names the field", "Notizen", inner[2].label)

        assertEquals("DEMO-F002", layout.rows[2].cells.single().subform)
        assertEquals(listOf("Customer", "Contract"), layout.rows[3].cells.single().sections.map { it.label })
        assertEquals(listOf("ID", "Amount"), layout.rows[4].cells.single().columns)
        assertEquals("a button is named by its text", "Check <address>", layout.rows[5].cells[1].label)
        assertNull("a component shown by default states no condition", layout.rows[0].cells[1].visible)
    }

    @Test
    fun rendersEveryComponentWithItsIdAndConditions() {
        assertTrue(svg.startsWith("<svg"))
        for (expected in listOf(
            "Onboarding &amp; review",
            "First name", "firstName", "Country",
            "Details", "Start date", "Remote",
            "visible if {{country == &#39;CH&#39;}}",
            "Notizen", "disabled",
            "↳ subform DEMO-F002",
            "▸ Customer", "▸ Contract", "Customer name",
            "Orders", ">ID<", ">Amount<",
            "Internal", "hidden",
            "Check &lt;address&gt;",
        )) {
            assertTrue("expected \"$expected\" in the wireframe", svg.contains(expected))
        }
        assertTrue("a required field carries a star", svg.contains("> *</tspan>"))
        assertTrue("a hidden component is greyed out", svg.contains(""" opacity="0.45">"""))
    }

    @Test
    fun everyComponentIsAClickableElementWithAHotspot() {
        val pic = FormSvgRenderer.picture(onboarding)!!
        assertEquals(Picture.Kind.WIREFRAME, pic.kind)
        // the same contract as a diagram shape: the element id on a focusable group
        assertTrue(svg.contains("""<g data-el="firstName" tabindex="0" role="button""""))
        val ids = pic.hotspots.map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("firstName", "country", "startDate", "remote", "notes")))
        // a field inside a panel wins the click over the panel around it
        val remote = pic.hotspots.single { it.id == "remote" }
        assertEquals("remote", pic.hotspotAt(remote.x + remote.width / 2, remote.y + remote.height / 2)!!.id)
        assertNull("the margin is no component", pic.hotspotAt(2.0, 2.0))
    }

    /**
     * Every form of a project is embedded in its explorer page, so a wireframe's bytes count: the font, the
     * 12px size and the text colour are written once on a group, not on every label.
     */
    @Test
    fun staysSmall() {
        val perComponent = svg.length / FormSvgRenderer.picture(onboarding)!!.hotspots.size
        assertTrue("$perComponent characters per component", perComponent < 600)
        assertFalse("no label repeats the font list", Regex("""<text [^>]*font-family="'Segoe UI'""").containsMatchIn(svg))
    }

    @Test
    fun sixPlusSixSitSideBySide() {
        // The second half-width field starts halfway across the grid, on the same line as the first.
        val xs = Regex("""<text x="([\d.]+)" y="([\d.]+)"[^>]*>(First name|Country)""").findAll(svg)
            .associate { it.groupValues[3] to (it.groupValues[1].toDouble() to it.groupValues[2].toDouble()) }
        assertEquals(xs["First name"]!!.second, xs["Country"]!!.second, 0.0)
        assertEquals(20.0 + (920.0 + 12.0) / 2, xs["Country"]!!.first, 0.01)
    }

    @Test
    fun readsTheShapesAFormArrivesIn() {
        // A hand-written body gives a row as a bare list of cells, sharing the twelve columns.
        val bare = """{"rows":[[{"id":"a","type":"text","label":"A"},{"id":"b","type":"text","label":"B"}]]}"""
        assertEquals(listOf(6, 6), FormLayout.parse(bare.toByteArray())!!.rows.single().cells.map { it.size })
        // A Design workspace export wraps the body as an escaped string.
        val wrapped = """{"name":"Wrapped","editorJson":${quote(bare)}}"""
        assertEquals("Wrapped", FormLayout.parse(wrapped.toByteArray())!!.title)
        // An empty form still renders, saying so.
        assertTrue(FormSvgRenderer.renderSvg("""{"rows":[]}""".toByteArray())!!.contains("No components"))
    }

    @Test
    fun notAFormIsNull() {
        assertNull(FormSvgRenderer.renderSvg("<definitions/>".toByteArray()))
        assertNull(FormSvgRenderer.renderSvg("""{"key":"x"}""".toByteArray()))
    }

    @Test
    fun neverEmitsRawMarkupAndIsDeterministic() {
        assertFalse(svg.contains("Check <address>"))
        assertEquals(svg, FormSvgRenderer.renderSvg(onboarding))
    }

    /** The form DEMO-onboarding's `addressForm` embeds — and one that embeds itself. */
    private val address = """{"metadata":{"key":"DEMO-F002","name":"Address"},"rows":[{"cols":[
        |{"id":"street","type":"text","label":"Street","size":8},{"id":"zip","type":"text","label":"ZIP","size":4}]}]}""".trimMargin()
    private val selfEmbedding = """{"metadata":{"key":"DEMO-F002"},"rows":[{"cols":[
        |{"id":"again","type":"subform","label":"Again","extraSettings":{"formRef":"DEMO-F002"}}]}]}""".trimMargin()

    @Test
    fun aResolvedSubformIsDrawnWithTheFormItEmbeds() {
        val pic = FormSvgRenderer.picture(onboarding) { key -> if (key == "DEMO-F002") address.toByteArray() else null }!!
        assertTrue(pic.svg.contains("Subform · Address"))
        assertTrue("the embedded form's fields are drawn", pic.svg.contains("Street") && pic.svg.contains("ZIP"))
        assertFalse("the placeholder is gone", pic.svg.contains("↳ subform DEMO-F002"))
        // the subform is this form's element and opens the other form; its parts are that form's
        val sub = pic.hotspots.single { it.id == "addressForm" }
        assertEquals("form:DEMO-F002", sub.ref)
        assertTrue(pic.svg.contains("""data-el="addressForm" tabindex="0" role="button" data-ref="form:DEMO-F002"""))
        assertTrue(pic.hotspots.none { it.id == "street" || it.id == "zip" })
        assertFalse(pic.svg.contains("""data-el="street"""))
        // a click on the embedded field lands on the subform
        val onStreet = Regex("""<text x="([\d.]+)" y="([\d.]+)"[^>]*>Street""").find(pic.svg)!!.groupValues
        assertEquals("addressForm", pic.hotspotAt(onStreet[1].toDouble() + 2, onStreet[2].toDouble() - 4)!!.id)
        // and it grew to fit: taller than the placeholder the unresolved one draws
        val placeholder = FormSvgRenderer.picture(onboarding)!!.hotspots.single { it.id == "addressForm" }
        assertTrue(sub.height > placeholder.height)
        assertEquals("unresolved, it still says which form it opens", "form:DEMO-F002", placeholder.ref)
    }

    @Test
    fun aSubformThatEmbedsItselfStops() {
        val bytes = selfEmbedding.toByteArray()
        val pic = FormSvgRenderer.picture(bytes) { bytes }!!
        // drawn once inside itself — the key is already on the way down — then the placeholder
        assertEquals(1, Regex("Subform · Again").findAll(pic.svg).count())
        assertTrue(pic.svg.contains("↳ subform DEMO-F002"))
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
