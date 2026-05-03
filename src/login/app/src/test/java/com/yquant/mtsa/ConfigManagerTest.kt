package com.yquant.mtsa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigManagerTest {

    @Test
    fun parseLoginCertConfig_readsTopLevelLoginCertBlock() {
        val yaml = """
            login:
              cert:
                name: "홍길동"
                password: "cert-secret"

            accounts:
              DC:
                number: "123"
        """.trimIndent()

        val config = ConfigManager.parseLoginCertConfig(yaml)

        requireNotNull(config)
        assertEquals("홍길동", config.name)
        assertEquals("cert-secret", config.password)
    }

    @Test
    fun parseLoginCertConfig_returnsNullWhenCertBlockMissing() {
        val yaml = """
            login:
              other:
                value: true
        """.trimIndent()

        val config = ConfigManager.parseLoginCertConfig(yaml)

        assertNull(config)
    }

    @Test
    fun resolveLoginCertConfig_prefersSavedPasswordFromAppSettings() {
        val parsedConfig = LoginCertConfig(name = "홍길동", password = "yaml-secret")

        val config = ConfigManager.resolveLoginCertConfig(parsedConfig, "saved-secret")

        requireNotNull(config)
        assertEquals("홍길동", config.name)
        assertEquals("saved-secret", config.password)
    }

    @Test
    fun resolveLoginCertConfig_usesSavedPasswordWithoutYamlConfig() {
        val config = ConfigManager.resolveLoginCertConfig(parsedConfig = null, savedCertPassword = "saved-secret")

        requireNotNull(config)
        assertEquals("", config.name)
        assertEquals("saved-secret", config.password)
    }
}
