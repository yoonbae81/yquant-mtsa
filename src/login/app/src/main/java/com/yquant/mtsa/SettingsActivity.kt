package com.yquant.mtsa

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var certPasswordEditText: EditText
    private lateinit var saveSettingsButton: Button
    private lateinit var settingsStatusTextView: TextView
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "공동인증서 로그인 설정"

        certPasswordEditText = findViewById(R.id.certPasswordEditText)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        settingsStatusTextView = findViewById(R.id.settingsStatusTextView)
        configManager = ConfigManager(this)

        loadCredentialSettings()

        saveSettingsButton.setOnClickListener {
            saveCredentialSettings()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadCredentialSettings() {
        val settings = configManager.loadCredentialSettings()
        certPasswordEditText.text?.clear()

        settingsStatusTextView.text = if (settings.certPassword.isBlank()) {
            "저장된 비밀번호가 없습니다."
        } else {
            "공동인증서 비밀번호가 Android Keystore에 저장되어 있습니다."
        }
    }

    private fun saveCredentialSettings() {
        val certPassword = certPasswordEditText.text.toString()
        if (certPassword.isBlank()) {
            showSettingsError("공동인증서 비밀번호를 입력해주세요.")
            return
        }

        val saved = configManager.saveCredentialSettings(
            CredentialSettings(
                certPassword = certPassword,
            )
        )

        if (saved) {
            settingsStatusTextView.text = "공동인증서 비밀번호를 저장했습니다."
            settingsStatusTextView.setTextColor(getColor(android.R.color.holo_green_dark))
            Toast.makeText(this, "공동인증서 비밀번호를 저장했습니다.", Toast.LENGTH_SHORT).show()
        } else {
            showSettingsError("비밀번호 저장에 실패했습니다.")
        }
    }

    private fun showSettingsError(message: String) {
        settingsStatusTextView.text = message
        settingsStatusTextView.setTextColor(getColor(android.R.color.holo_red_dark))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
