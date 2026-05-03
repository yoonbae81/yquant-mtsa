package com.yquant.mtsa

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class ExtractionRule(
    val label: String,
    val target_text: String,
    val resource_id: String,
    val extraction_method: String,
    val position_offset: Int,
    val regex_pattern: String? = null
)

data class ScreenConfig(
    val screen_name: String,
    val version: String,
    val rules: List<ExtractionRule>
)

data class LoginCertConfig(
    val name: String,
    val password: String
)

data class CredentialSettings(
    val certPassword: String = "",
    val irpAccountPassword: String = "",
    val dcAccountPassword: String = ""
)

class ConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "ConfigManager"
        private const val CONFIG_FILE = "balance_extraction_rules.json"
        private const val PREFS_NAME = "mtsa_config"
        private const val RULES_KEY = "extraction_rules"
        private const val HOLDING_TICKER_MAP_KEY = "holding_ticker_map"
        private const val LOGIN_CONFIG_FILE = "config.yaml"
        private const val CREDENTIAL_CERT_PASSWORD_KEY = "credential_cert_password"
        private const val CREDENTIAL_IRP_PASSWORD_KEY = "credential_irp_password"
        private const val CREDENTIAL_DC_PASSWORD_KEY = "credential_dc_password"

        fun parseLoginCertConfig(content: String): LoginCertConfig? {
            var inLoginSection = false
            var inCertSection = false
            var certName: String? = null
            var certPassword: String? = null

            content.lineSequence().forEach { rawLine ->
                val withoutComment = rawLine.substringBefore("#")
                val trimmed = withoutComment.trimEnd()
                if (trimmed.isBlank()) {
                    return@forEach
                }

                val indent = withoutComment.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val line = trimmed.trimStart()

                when {
                    indent == 0 && line == "login:" -> {
                        inLoginSection = true
                        inCertSection = false
                    }

                    indent == 0 -> {
                        inLoginSection = false
                        inCertSection = false
                    }

                    inLoginSection && indent == 2 && line == "cert:" -> {
                        inCertSection = true
                    }

                    inLoginSection && indent == 2 -> {
                        inCertSection = false
                    }

                    inLoginSection && inCertSection && indent == 4 && line.startsWith("name:") -> {
                        certName = line.substringAfter(':').trim().trim('"', '\'')
                    }

                    inLoginSection && inCertSection && indent == 4 && line.startsWith("password:") -> {
                        certPassword = line.substringAfter(':').trim().trim('"', '\'')
                    }
                }
            }

            if (certName.isNullOrBlank() || certPassword.isNullOrBlank()) {
                return null
            }

            return LoginCertConfig(
                name = certName!!,
                password = certPassword!!,
            )
        }

        fun resolveLoginCertConfig(
            parsedConfig: LoginCertConfig?,
            savedCertPassword: String,
        ): LoginCertConfig? {
            if (savedCertPassword.isBlank()) {
                return parsedConfig
            }

            return LoginCertConfig(
                name = parsedConfig?.name.orEmpty(),
                password = savedCertPassword,
            )
        }
    }

    fun loadRules(): List<ExtractionRule> {
        getFromAssets()?.let { return it }
        getFromPrefs()?.let { return it }
        Log.w(TAG, "Using default rules as fallback")
        return getDefaultRules()
    }

    fun loadLoginCertConfig(): LoginCertConfig? {
        val savedCertPassword = loadCredentialSettings().certPassword
        return try {
            val inputStream = context.assets.open(LOGIN_CONFIG_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()

            val config = parseLoginCertConfig(content)
            if (config == null) {
                Log.e(TAG, "Failed to parse login cert config from config.yaml")
            } else {
                Log.d(TAG, "Loaded cert config for ${config.name}")
            }

            resolveLoginCertConfig(config, savedCertPassword)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load login cert config: ${e.message}")
            resolveLoginCertConfig(parsedConfig = null, savedCertPassword = savedCertPassword)
        }
    }

    fun loadCredentialSettings(): CredentialSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CredentialSettings(
            certPassword = prefs.getString(CREDENTIAL_CERT_PASSWORD_KEY, "").orEmpty(),
            irpAccountPassword = prefs.getString(CREDENTIAL_IRP_PASSWORD_KEY, "").orEmpty(),
            dcAccountPassword = prefs.getString(CREDENTIAL_DC_PASSWORD_KEY, "").orEmpty()
        )
    }

    fun saveCredentialSettings(settings: CredentialSettings): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(CREDENTIAL_CERT_PASSWORD_KEY, settings.certPassword)
                .putString(CREDENTIAL_IRP_PASSWORD_KEY, settings.irpAccountPassword)
                .putString(CREDENTIAL_DC_PASSWORD_KEY, settings.dcAccountPassword)
                .apply()
            Log.d(TAG, "Saved credential settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save credential settings: ${e.message}")
            false
        }
    }

    private fun getFromAssets(): List<ExtractionRule>? {
        return try {
            val inputStream = context.assets.open(CONFIG_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()

            val config = parseConfig(content)
            Log.d(TAG, "Loaded ${config.rules.size} rules from assets")
            config.rules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules from assets: ${e.message}")
            null
        }
    }

    private fun parseConfig(content: String): ScreenConfig {
        val json = JSONObject(content)
        val rulesJson = json.getJSONArray("rules")
        val rules = mutableListOf<ExtractionRule>()

        for (i in 0 until rulesJson.length()) {
            val ruleJson = rulesJson.getJSONObject(i)
            val rule = ExtractionRule(
                label = ruleJson.optString("label", ""),
                target_text = ruleJson.optString("target_text", ""),
                resource_id = ruleJson.optString("resource_id", ""),
                extraction_method = ruleJson.optString("extraction_method", "nearby_text"),
                position_offset = ruleJson.optInt("position_offset", 1),
                regex_pattern = if (ruleJson.has("regex_pattern")) ruleJson.optString("regex_pattern") else null
            )
            rules.add(rule)
        }

        return ScreenConfig(
            screen_name = json.optString("screen_name", ""),
            version = json.optString("version", ""),
            rules = rules
        )
    }

    private fun getFromPrefs(): List<ExtractionRule>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(RULES_KEY, null) ?: return null

            val jsonArray = JSONArray(jsonString)
            val rules = mutableListOf<ExtractionRule>()

            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                val rule = ExtractionRule(
                    label = jsonObj.optString("label", ""),
                    target_text = jsonObj.optString("target_text", ""),
                    resource_id = jsonObj.optString("resource_id", ""),
                    extraction_method = jsonObj.optString("extraction_method", "nearby_text"),
                    position_offset = jsonObj.optInt("position_offset", 1),
                    regex_pattern = if (jsonObj.has("regex_pattern")) jsonObj.optString("regex_pattern") else null
                )
                rules.add(rule)
            }

            Log.d(TAG, "Loaded ${rules.size} rules from preferences")
            rules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules from preferences: ${e.message}")
            null
        }
    }

    fun saveRulesToPrefs(rules: List<ExtractionRule>): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            val jsonArray = JSONArray()
            for (rule in rules) {
                val jsonObj = JSONObject().apply {
                    put("label", rule.label)
                    put("target_text", rule.target_text)
                    put("resource_id", rule.resource_id)
                    put("extraction_method", rule.extraction_method)
                    put("position_offset", rule.position_offset)
                    rule.regex_pattern?.let { put("regex_pattern", it) }
                }
                jsonArray.put(jsonObj)
            }

            editor.putString(RULES_KEY, jsonArray.toString())
            editor.apply()

            Log.d(TAG, "Saved ${rules.size} rules to preferences")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rules to preferences: ${e.message}")
            false
        }
    }

    fun loadHoldingTickerMap(): Map<String, String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(HOLDING_TICKER_MAP_KEY, null) ?: return emptyMap()
            val json = JSONObject(jsonString)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key, ""))
                }
            }.filterValues { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load holding ticker map: ${e.message}")
            emptyMap()
        }
    }

    fun saveHoldingTicker(name: String, ticker: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = JSONObject(prefs.getString(HOLDING_TICKER_MAP_KEY, "{}") ?: "{}")
            current.put(name, ticker)
            prefs.edit().putString(HOLDING_TICKER_MAP_KEY, current.toString()).apply()
            Log.d(TAG, "Saved holding ticker mapping for $name -> $ticker")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save holding ticker map: ${e.message}")
            false
        }
    }

    private fun getDefaultRules(): List<ExtractionRule> {
        return listOf(
            ExtractionRule(
                label = "종목명",
                target_text = "",
                resource_id = "",
                extraction_method = "nearby_text",
                position_offset = 1,
                regex_pattern = ".*"
            ),
            ExtractionRule(
                label = "매도가능",
                target_text = "",
                resource_id = "",
                extraction_method = "nearby_text",
                position_offset = 1,
                regex_pattern = "[\\d,]+"
            ),
            ExtractionRule(
                label = "매입단가",
                target_text = "",
                resource_id = "",
                extraction_method = "nearby_text",
                position_offset = 1,
                regex_pattern = "[\\d,]+"
            )
        )
    }
}
