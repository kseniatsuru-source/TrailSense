package com.mayushi.trailsense.network

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun `allows tailscale and private cleartext`() {
        assertEquals("http://100.64.1.2:8642", EndpointPolicy.normalizeAndValidate("http://100.64.1.2:8642/"))
        assertEquals("http://mayushi.tailnet.ts.net:8642", EndpointPolicy.normalizeAndValidate("http://mayushi.tailnet.ts.net:8642"))
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects public cleartext`() {
        EndpointPolicy.normalizeAndValidate("http://example.com:8642")
    }
    @Test fun `allows public https`() { assertEquals("https://example.com", EndpointPolicy.normalizeAndValidate("https://example.com")) }
}
