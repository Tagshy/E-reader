package com.leitura.app

import org.junit.Assert.*
import org.junit.Test

class UpdateUrlTest {
    @Test fun acceptsOnlyThisRepositoriesHttpsReleaseAssets() {
        assertTrue(trustedReleaseUrl("https://github.com/Tagshy/E-reader/releases/download/v2.1.0/Leitura.apk"))
        listOf(
            "http://github.com/Tagshy/E-reader/releases/download/v2/Leitura.apk",
            "https://github.com.attacker.test/Tagshy/E-reader/releases/download/v2/Leitura.apk",
            "https://github.com/other/repo/releases/download/v2/Leitura.apk",
            "https://attacker@github.com/Tagshy/E-reader/releases/download/v2/Leitura.apk",
            "https://github.com/Tagshy/E-reader/releases/download/../Leitura.apk",
            "https://github.com/Tagshy/E-reader/releases/download/%2e%2e/Leitura.apk",
            "https://github.com:443/Tagshy/E-reader/releases/download/v2/Leitura.apk",
            "https://github.com/Tagshy/E-reader/releases/download/v2/Leitura.apk?redirect=evil"
        ).forEach { assertFalse(it, trustedReleaseUrl(it)) }
    }
}
