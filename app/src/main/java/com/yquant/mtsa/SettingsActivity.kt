package com.yquant.mtsa

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var certPasswordEditText: EditText
    private lateinit var irpPasswordEditText: EditText
    private lateinit var dcPasswordEditText: EditText
    private lateinit var saveSettingsButton: Button
    private lateinit var settingsStatusTextView: TextView
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "환경설정"

        certPasswordEditText = findViewById(R.id.certPasswordEditText)
        irpPasswordEditText = findViewById(R.id.irpPasswordEditText)
        dcPasswordEditText = findViewById(R.id.dcPasswordEditText)
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
        certPasswordEditText.setText(settings.certPassword)
        irpPasswordEditText.setText(settings.irpAccountPassword)
        dcPasswordEditText.setText(settings.dcAccountPassword)

        settingsStatusTextView.text = if (
            settings.certPassword.isBlank() &&
            settings.irpAccountPassword.isBlank() &&
            settings.dcAccountPassword.isBlank()
        ) {
            "저장된 비밀번호가 없습니다."
        } else {
            "저장된 비밀번호를 불러왔습니다."
        }
    }

    private fun saveCredentialSettings() {
        val certPassword = certPasswordEditText.text.toString()
        val irpPassword = irpPasswordEditText.text.toString()
        val dcPassword = dcPasswordEditText.text.toString()

        if (!isValidAccountPassword(irpPassword)) {
            showSettingsError("IRP 계좌 비밀번호는 숫자 4자리로 입력해주세요.")
            return
        }

        if (!isValidAccountPassword(dcPassword)) {
            showSettingsError("DC 계좌 비밀번호는 숫자 4자리로 입력해주세요.")
            return
        }

        val saved = configManager.saveCredentialSettings(
            CredentialSettings(
                certPassword = certPassword,
                irpAccountPassword = irpPassword,
                dcAccountPassword = dcPassword,
            )
        )

        if (saved) {
            settingsStatusTextView.text = "환경설정을 저장했습니다."
            settingsStatusTextView.setTextColor(getColor(android.R.color.holo_green_dark))
            Toast.makeText(this, "환경설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
        } else {
            showSettingsError("환경설정 저장에 실패했습니다.")
        }
    }

    private fun isValidAccountPassword(password: String): Boolean {
        return password.isBlank() || password.matches(Regex("\\d{4}"))
    }

    private fun showSettingsError(message: String) {
        settingsStatusTextView.text = message
        settingsStatusTextView.setTextColor(getColor(android.R.color.holo_red_dark))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
