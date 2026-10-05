package dev.datell.followertracker.ui

import org.junit.Assert.*
import org.junit.Test

class LoginUserAgentTest {
    @Test fun currentEngineAndDeviceArePreservedForLogin() {
        val default = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/TEST; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.6943.137 Mobile Safari/537.36"
        val result = loginUserAgent(default)
        assertFalse(result.contains("; wv"))
        assertFalse(result.contains("Version/4.0"))
        assertTrue(result.contains("Android 16; Pixel 9 Build/TEST"))
        assertTrue(result.contains("Chrome/133.0.6943.137"))
    }
    @Test fun anAlreadyCompatibleAgentIsUnchanged() {
        val agent = "Mozilla/5.0 Chrome/145.0.0.0 Mobile Safari/537.36"
        assertEquals(agent, loginUserAgent(agent))
        assertEquals(agent, loginUserAgent(loginUserAgent(agent)))
    }
    @Test fun pageRepresentationKeepsTheInstalledChromeVersionWithoutChangingLogin() {
        val agent = "Mozilla/5.0 (Linux; Android 16) Chrome/145.0.1234.56 Mobile Safari/537.36"
        val desktop = checkNotNull(facebookPageUserAgent(agent))
        assertTrue(desktop.contains("Chrome/145.0.1234.56"))
        assertFalse(desktop.contains("Mobile"))
        assertFalse(desktop.contains("Android"))
        assertEquals(agent, loginUserAgent(agent))
        assertNull(facebookPageUserAgent("Mozilla/5.0 Safari/537.36"))
    }
}
