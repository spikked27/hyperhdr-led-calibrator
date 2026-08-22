package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SsdpDiscoveryTest {
    @Test fun parsesNativeHyperHdrResponse() {
        val text = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age = 1800\r\n" +
            "LOCATION: http://192.168.1.36:8090/\r\n" +
            "ST: upnp:rootdevice\r\n" +
            "USN: uuid:abc123\r\n" +
            "HYPERHDR-FBS-PORT: 19400\r\n" +
            "HYPERHDR-JSS-PORT: 19444\r\n" +
            "HYPERHDR-NAME: Living Room\r\n\r\n"
        val s=SsdpDiscovery.parseResponse(text,"192.168.1.36")!!
        assertEquals("Living Room",s.name)
        assertEquals("192.168.1.36",s.host)
        assertEquals(19444,s.jsonPort)
    }

    @Test fun headerNamesAreCaseInsensitive() {
        val text="location: http://10.0.0.8:8090/\r\nhyperhdr-jss-port: 19444\r\nhyperhdr-name: Test\r\n"
        val s=SsdpDiscovery.parseResponse(text,"10.0.0.8")!!
        assertEquals("Test",s.name)
    }

    @Test fun fallsBackToUdpSenderWhenLocationMalformed() {
        val text="LOCATION: not a url\r\nHYPERHDR-JSS-PORT: 19444\r\nHYPERHDR-NAME: Test\r\n"
        val s=SsdpDiscovery.parseResponse(text,"10.0.0.9")!!
        assertEquals("10.0.0.9",s.host)
    }

    @Test fun rejectsUnrelatedSsdpResponse() {
        assertNull(SsdpDiscovery.parseResponse("HTTP/1.1 200 OK\r\nSERVER: printer\r\n","1.2.3.4"))
    }
}
