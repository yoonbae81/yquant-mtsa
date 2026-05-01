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
                name: "조윤배"
                password: "dbsqo8492@"

            accounts:
              DC:
                number: "123"
        """.trimIndent()

        val config = ConfigManager.parseLoginCertConfig(yaml)

        requireNotNull(config)
        assertEquals("조윤배", config.name)
        assertEquals("dbsqo8492@", config.password)
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
}
