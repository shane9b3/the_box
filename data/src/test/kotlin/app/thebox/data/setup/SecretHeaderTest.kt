package app.thebox.data.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-L1 (M5 code review 2026-10-16): auth-like headers stay with the login, never in a backup's clear columns. */
class SecretHeaderTest {
    @Test
    fun anyAuthTokenKeyOrSessionHeaderIsSecret() {
        for (h in listOf("Authorization", "Proxy-Authorization", "Cookie", "X-Api-Key", "X-Auth-Token", "Session-Id", "x-access-token")) {
            assertTrue(h, PlaylistSettingsRepository.isSecretHeader(h))
        }
        for (h in listOf("User-Agent", "Referer", "Accept-Language", "X-Forwarded-For")) assertFalse(h, PlaylistSettingsRepository.isSecretHeader(h))
    }
}
