package com.spikked27.hyperhdrcalibrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HyperHdrAdminAuthTest {
    @Test
    fun passwordLoginRequestMatchesHyperHdrAuthorizeSchema() {
        val request = HyperHdrClient.authorizePasswordRequest("password123")
        assertEquals("authorize", request.getString("command"))
        assertEquals("login", request.getString("subcommand"))
        assertEquals("password123", request.getString("password"))
        assertFalse(request.has("token"))
    }

    @Test
    fun userTokenLoginRequestMatchesHyperHdrAuthorizeSchema() {
        val token = "t".repeat(40)
        val request = HyperHdrClient.authorizeTokenRequest(token)
        assertEquals("authorize", request.getString("command"))
        assertEquals("login", request.getString("subcommand"))
        assertEquals(token, request.getString("token"))
        assertFalse(request.has("password"))
    }

    @Test
    fun passwordAndUserTokenLengthGuardsMatchHyperHdr() {
        assertFailsWith<IllegalArgumentException> { HyperHdrClient.authorizePasswordRequest("short") }
        assertFailsWith<IllegalArgumentException> { HyperHdrClient.authorizeTokenRequest("short-token") }
    }

    @Test
    fun credentialStoreDropsPasswordAfterTokenExchange() {
        HyperHdrAdminCredentialStore.clear()
        HyperHdrAdminCredentialStore.setPassword("password123")
        assertTrue(HyperHdrAdminCredentialStore.hasCredential())
        assertEquals("password123", HyperHdrAdminCredentialStore.passwordForExchange())

        val token = "x".repeat(40)
        HyperHdrAdminCredentialStore.acceptUserToken(token)
        assertEquals(null, HyperHdrAdminCredentialStore.passwordForExchange())
        assertEquals(token, HyperHdrAdminCredentialStore.token())
        HyperHdrAdminCredentialStore.clear()
    }
}
