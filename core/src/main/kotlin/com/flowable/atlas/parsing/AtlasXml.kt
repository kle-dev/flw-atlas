package com.flowable.atlas.parsing

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A tiny namespace-agnostic DOM wrapper that mirrors the slice of Python `xml.etree.ElementTree`
 * the Atlas parsers use (`parse_xml`/`_strip_ns`/`text_of`/`child_text` + `iter`/`find`/`findall`).
 *
 * Namespaces are ignored by comparing local names only (the Python side strips `{uri}` prefixes via
 * `_strip_ns`); here we parse namespace-unaware and drop any `prefix:` from tag/attribute names, which
 * yields the same local-name view. DTDs and external entities are disabled (untrusted model files).
 */
object AtlasXml {

    private val FACTORY: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    /** Parse raw XML bytes and return the document element as an [El], or throw on malformed XML. */
    fun parse(data: ByteArray): El {
        val doc: Document = FACTORY.newDocumentBuilder().parse(ByteArrayInputStream(data))
        return El(doc.documentElement)
    }

    private fun local(name: String): String = name.substringAfterLast(':')

    /** An element view exposing ElementTree-style navigation by local name. */
    class El(private val e: Element) {

        /** Local tag name (namespace prefix stripped). */
        val tag: String get() = local(e.tagName)

        /** Attribute value by local name, or null if absent. */
        fun attr(name: String): String? {
            if (e.hasAttribute(name)) return e.getAttribute(name)
            // fall back to a namespaced attribute whose local part matches (e.g. flowable:type)
            val attrs = e.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i)
                if (local(a.nodeName) == name) return a.nodeValue
            }
            return null
        }

        /** ElementTree `.text`: the text directly after the start tag (before the first child). */
        val text: String?
            get() {
                val first = e.firstChild ?: return null
                return if (first.nodeType == Node.TEXT_NODE || first.nodeType == Node.CDATA_SECTION_NODE)
                    first.nodeValue else null
            }

        /** Direct child elements, in document order. */
        val children: List<El>
            get() {
                val out = ArrayList<El>()
                val kids = e.childNodes
                for (i in 0 until kids.length) {
                    val n = kids.item(i)
                    if (n.nodeType == Node.ELEMENT_NODE) out.add(El(n as Element))
                }
                return out
            }

        /** ElementTree `findall(tag)`: direct children with the given local name. */
        fun findChildren(tag: String): List<El> = children.filter { it.tag == tag }

        /** ElementTree `find(tag)`: first direct child with the given local name, or null. */
        fun findChild(tag: String): El? = children.firstOrNull { it.tag == tag }

        /** ElementTree `find(".//tag")`: first descendant (excluding self) with the local name. */
        fun findDescendant(tag: String): El? {
            for (c in children) {
                if (c.tag == tag) return c
                c.findDescendant(tag)?.let { return it }
            }
            return null
        }

        /** ElementTree `iter(tag)`: self + all descendants with the given local name, pre-order. */
        fun iter(tag: String): List<El> {
            val out = ArrayList<El>()
            fun walk(el: El) {
                if (el.tag == tag) out.add(el)
                for (c in el.children) walk(c)
            }
            walk(this)
            return out
        }

        /** ElementTree `text_of`: stripped `.text` of the first matching descendant, or null. */
        fun textOfDescendant(tag: String): String? = findDescendant(tag)?.text?.trim()?.ifEmpty { null }

        /** ElementTree `child_text`: stripped `.text` of the first matching direct child, or null. */
        fun childText(tag: String): String? = findChild(tag)?.text?.trim()?.ifEmpty { null }
    }
}
