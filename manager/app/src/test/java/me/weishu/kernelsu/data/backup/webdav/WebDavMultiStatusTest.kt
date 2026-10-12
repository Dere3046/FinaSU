package me.weishu.kernelsu.data.backup.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavMultiStatusTest {

    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/FinaSU/boot/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/FinaSU/boot/boot-stock-abc.img</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>67108864</D:getcontentlength>
                <D:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun `parses collections and files`() {
        val resources = WebDavMultiStatus.parse(sample.byteInputStream())

        assertEquals(2, resources.size)
        assertTrue(resources[0].isCollection)
        assertEquals("/dav/FinaSU/boot/", resources[0].href)
        assertFalse(resources[1].isCollection)
        assertEquals(67_108_864L, resources[1].contentLength)
        assertNotNull(resources[1].lastModifiedEpochMs)
    }

    @Test
    fun `ignores responses without a 200 propstat`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <multistatus xmlns="DAV:">
              <response>
                <href>/dav/secret</href>
                <propstat>
                  <prop><resourcetype/></prop>
                  <status>HTTP/1.1 403 Forbidden</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()

        // No successful propstat: the resource is skipped rather than reported with no properties.
        assertTrue(WebDavMultiStatus.parse(xml.byteInputStream()).isEmpty())
    }
}

class WebDavHrefTest {

    @Test
    fun `strips scheme host query and decodes escapes`() {
        assertEquals("/dav/a b/c.img", WebDavHref.toPath("https://host.example/dav/a%20b/c.img?x=1"))
    }

    @Test
    fun `keeps a plain path`() {
        assertEquals("/dav/FinaSU/boot/x.img", WebDavHref.toPath("/dav/FinaSU/boot/x.img"))
    }

    @Test
    fun `relativize strips the base path`() {
        assertEquals("boot/x.img", WebDavHref.relativize("/dav/FinaSU", "/dav/FinaSU/boot/x.img"))
    }

    @Test
    fun `relativize treats the base itself as empty`() {
        assertEquals("", WebDavHref.relativize("/dav/FinaSU/", "/dav/FinaSU"))
    }

    @Test
    fun `relativize rejects siblings outside the base`() {
        assertNull(WebDavHref.relativize("/dav/FinaSU", "/dav/FinaSUOther/x.img"))
    }

    @Test
    fun `relativize handles a root base`() {
        assertEquals("boot/x.img", WebDavHref.relativize("/", "/boot/x.img"))
    }
}
