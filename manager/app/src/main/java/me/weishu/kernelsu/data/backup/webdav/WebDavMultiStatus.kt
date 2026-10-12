package me.weishu.kernelsu.data.backup.webdav

import java.io.InputStream
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One resource reported by a `PROPFIND Depth: 1` response. */
data class WebDavResource(
    val href: String,
    val isCollection: Boolean,
    val contentLength: Long,
    val lastModifiedEpochMs: Long?,
)

/**
 * Parser for the `multistatus` document returned by `PROPFIND`.
 *
 * The parser is namespace-aware and matches elements by local name, so it works regardless of the
 * namespace prefix a server chooses. External entities are disabled to keep a hostile server from
 * turning a listing into an XXE vector.
 */
object WebDavMultiStatus {

    private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    /** Parses [xml] into the resources it describes, ignoring malformed `response` nodes. */
    fun parse(xml: InputStream): List<WebDavResource> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(xml)
        val responses = document.getElementsByTagNameNS("*", "response")
        val resources = ArrayList<WebDavResource>(responses.length)
        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = response.childText("href") ?: continue
            val prop = response.successfulProp() ?: continue
            resources += WebDavResource(
                href = href,
                isCollection = prop.firstChildElement("resourcetype")
                    ?.hasChildElement("collection") == true,
                contentLength = prop.childText("getcontentlength")?.toLongOrNull() ?: 0L,
                lastModifiedEpochMs = prop.childText("getlastmodified")?.let(::parseHttpDate),
            )
        }
        return resources
    }

    private fun Element.successfulProp(): Element? {
        val propstats = childElements("propstat")
        propstats.firstOrNull { it.statusCode() == 200 }
            ?.let { return it.firstChildElement("prop") }
        // Fall back to the first propstat only when no status is reported at all; a propstat that
        // explicitly failed (e.g. 403) must not be surfaced as if it succeeded.
        if (propstats.none { it.firstChildElement("status") != null }) {
            return propstats.firstOrNull()?.firstChildElement("prop")
        }
        return null
    }

    private fun Element.statusCode(): Int? =
        childText("status")?.split(' ')?.firstNotNullOfOrNull { it.toIntOrNull() }

    private fun parseHttpDate(value: String): Long? =
        runCatching { ZonedDateTime.parse(value, HTTP_DATE).toInstant().toEpochMilli() }.getOrNull()

    private fun Element.childElements(localName: String): List<Element> {
        val result = ArrayList<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element && node.localName == localName) result += node
        }
        return result
    }

    private fun Element.firstChildElement(localName: String): Element? =
        childElements(localName).firstOrNull()

    private fun Element.hasChildElement(localName: String): Boolean =
        firstChildElement(localName) != null

    private fun Element.childText(localName: String): String? =
        firstChildElement(localName)?.textContent?.takeIf { it.isNotBlank() }
}

/**
 * Pure helpers for turning a DAV `href` into a storage-relative path.
 *
 * No IO is performed; percent-encoding is decoded so names with spaces or non-ASCII characters map
 * back to the original file name.
 */
object WebDavHref {

    /** Decodes a DAV href into an absolute path, dropping scheme, authority, query and fragment. */
    fun toPath(href: String): String {
        val withoutQuery = href.substringBefore('#').substringBefore('?')
        val path = if ("://" in withoutQuery) {
            "/" + withoutQuery.substringAfter("://").substringAfter('/', "")
        } else {
            withoutQuery
        }
        return percentDecode(path.ifEmpty { "/" })
    }

    /**
     * Returns the storage-relative path of [href] below [basePath] (the path component of the
     * backend base URL).
     *
     * The directory itself yields an empty string; a resource outside [basePath] yields `null`.
     */
    fun relativize(basePath: String, href: String): String? {
        val base = "/" + basePath.trim('/')
        val path = toPath(href)
        if (path == base) return ""
        val prefix = if (base == "/") "/" else "$base/"
        if (!path.startsWith(prefix)) return null
        return path.removePrefix(prefix)
    }

    private fun percentDecode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
