package com.flowable.atlas.parsing

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
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

    private fun hardenedFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // A DOCTYPE is refused outright. The three features below only cover *external* references —
        // an internal DTD subset still expands, which is the billion-laughs shape. Model files never
        // carry a DOCTYPE, so refusing one costs nothing and closes the entity-expansion door that
        // this object's contract ("DTDs and external entities are disabled") already claims is shut.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }

    // One builder per thread, not one shared factory. Neither DocumentBuilderFactory nor
    // DocumentBuilder is specified as thread-safe, and both the CLI's parallel walk and the IDE's
    // index scan parse from more than one thread — a shared factory was a latent race that happened
    // to work only because the bundled Xerces implementation tolerates it.
    private val BUILDER = ThreadLocal.withInitial { hardenedFactory().newDocumentBuilder() }

    /**
     * The JDK's default handling, minus the printing. Without a handler the default one writes
     * `[Fatal Error] :2:3: …` to stderr before the exception is thrown — even under `-q`, naming the line
     * without the file, while the diagnostic named the file without the line. The caller reports the
     * [SAXParseException] with both. A recoverable error is passed over as the default handler passed over
     * it, after printing `[Error]`: throwing there would make a file that has always parsed a parse issue.
     */
    private val SILENT = object : ErrorHandler {
        override fun warning(e: SAXParseException) {}
        override fun error(e: SAXParseException) {}
        override fun fatalError(e: SAXParseException) { throw e }
    }

    /** Parse raw XML bytes and return the document element as an [El], or throw on malformed XML — a
     *  [SAXParseException], whose `lineNumber` says where. */
    fun parse(data: ByteArray): El {
        val builder = BUILDER.get()
        builder.reset()
        // After reset(), on every parse: reset() restores the handler the builder was constructed with,
        // which is none — set once in the thread-local's initializer, it lasted until the first reset.
        builder.setErrorHandler(SILENT)
        val doc: Document = builder.parse(ByteArrayInputStream(data))
        return El(doc.documentElement)
    }

    /** The line [e] — or what it wraps — says the XML broke on, or null when it is no parse error with one. */
    fun lineOf(e: Throwable): Int? =
        generateSequence(e) { it.cause }.take(8).filterIsInstance<SAXParseException>().firstOrNull()
            ?.lineNumber?.takeIf { it > 0 }

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

        /**
         * ElementTree `.text`: the text directly after the start tag (before the first child element).
         * The DOM splits that run into several nodes — whitespace before a CDATA section, a body split
         * into two CDATA sections, a comment in between — so every text and CDATA node up to the first
         * element is joined; comments and processing instructions are skipped, as ElementTree drops them.
         */
        val text: String?
            get() {
                var n = e.firstChild ?: return null
                var out: StringBuilder? = null
                while (true) {
                    when (n.nodeType) {
                        Node.ELEMENT_NODE -> break
                        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> (out ?: StringBuilder().also { out = it }).append(n.nodeValue)
                    }
                    n = n.nextSibling ?: break
                }
                return out?.toString()
            }

        /** Every text and CDATA node directly under this element, joined — the body of a Liquibase `<sql>`
         *  that carries a `<comment>` child before its statements, which [text] stops at. */
        val ownText: String
            get() {
                val out = StringBuilder()
                val kids = e.childNodes
                for (i in 0 until kids.length) {
                    val n = kids.item(i)
                    if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) out.append(n.nodeValue)
                }
                return out.toString()
            }

        /** Attributes by local name, in document order. */
        val attributes: Map<String, String>
            get() {
                val out = LinkedHashMap<String, String>()
                val attrs = e.attributes
                for (i in 0 until attrs.length) {
                    val a = attrs.item(i)
                    out[local(a.nodeName)] = a.nodeValue
                }
                return out
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
