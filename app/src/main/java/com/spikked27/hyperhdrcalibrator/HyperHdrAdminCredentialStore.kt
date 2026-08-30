package com.spikked27.hyperhdrcalibrator

/**
 * Process-memory-only authorization state for Beta 9.4.
 *
 * The launcher holds the admin password only until the selected HyperHDR server exchanges it for
 * HyperHDR's long-lived user token. Nothing here is persisted to disk, preferences, logs, or files.
 */
object HyperHdrAdminCredentialStore {
    private var pendingPassword: String? = null
    private var userToken: String? = null

    @Synchronized
    fun setPassword(password: String) {
        require(password.length >= 8) { "HyperHDR admin password must be at least 8 characters" }
        pendingPassword = password
        userToken = null
    }

    @Synchronized
    fun passwordForExchange(): String? = pendingPassword

    @Synchronized
    fun token(): String? = userToken

    @Synchronized
    fun acceptUserToken(token: String) {
        require(token.length > 36) { "HyperHDR returned an invalid user token" }
        userToken = token
        pendingPassword = null
    }

    @Synchronized
    fun hasCredential(): Boolean = !userToken.isNullOrBlank() || !pendingPassword.isNullOrBlank()

    @Synchronized
    fun clear() {
        pendingPassword = null
        userToken = null
    }
}
