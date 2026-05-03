package com.yquant.mtsa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var loginButton: Button
    private lateinit var loginCheckButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var statusTextView: TextView

    private var loginStatusReceiver: BroadcastReceiver? = null
    private var loginTimeoutJob: Job? = null
    private var loginCheckTimeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginCheckButton = findViewById(R.id.loginCheckButton)
        loginButton = findViewById(R.id.loginButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusTextView = findViewById(R.id.statusTextView)

        loginCheckButton.setOnClickListener {
            checkLoginStatusAfterDelay()
        }

        loginButton.setOnClickListener {
            loginAfterDelay()
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        checkAccessibilityService()
        registerLoginStatusReceiver()
    }

    private fun checkLoginStatusAfterDelay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        loginCheckButton.isEnabled = false
        loginCheckTimeoutJob?.cancel()
        statusTextView.text = "로그인 여부를 확인하는 중입니다..."
        statusTextView.setTextColor(getColor(android.R.color.black))

        loginCheckTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(45_000)
            loginCheckButton.isEnabled = true
            statusTextView.text = "로그인 여부 확인 응답이 지연되고 있습니다. 필요하면 다시 시도하세요."
            statusTextView.setTextColor(getColor(android.R.color.holo_red_dark))
        }

        CoroutineScope(Dispatchers.Main).launch {
            delay(500)
            sendCommandToAccessibilityService("CHECK_LOGIN_STATUS")
            statusTextView.text = "로그인 여부 확인 명령을 전송했습니다. MTS 앱을 확인하세요."
        }
    }

    private fun loginAfterDelay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        loginButton.isEnabled = false
        loginTimeoutJob?.cancel()
        statusTextView.text = "공동인증서 로그인 준비 중입니다..."
        statusTextView.setTextColor(getColor(android.R.color.black))

        loginTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(120_000)
            loginButton.isEnabled = true
            statusTextView.text = "로그인 응답이 지연되고 있습니다. 필요하면 다시 시도하세요."
            statusTextView.setTextColor(getColor(android.R.color.holo_red_dark))
        }

        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            sendCommandToAccessibilityService("LOGIN")
            statusTextView.text = "로그인 명령을 전송했습니다. MTS 앱을 확인하세요."
        }
    }

    private fun sendCommandToAccessibilityService(command: String) {
        val intent = Intent("com.yquant.mtsa.ACTION_NAVIGATE")
        intent.putExtra("command", command)
        sendBroadcast(intent)
    }

    private fun checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            statusTextView.text = "⚠️ 접근성 서비스를 활성화해주세요"
            statusTextView.setTextColor(getColor(android.R.color.holo_red_dark))
        } else {
            statusTextView.text = "✓ 접근성 서비스가 활성화되었습니다"
            statusTextView.setTextColor(getColor(android.R.color.holo_green_dark))
        }
    }

    private fun registerLoginStatusReceiver() {
        loginStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getStringExtra("status") ?: return
                val success = intent.getBooleanExtra("success", false)
                val finished = intent.getBooleanExtra("finished", success)

                statusTextView.text = status
                statusTextView.setTextColor(
                    getColor(
                        when {
                            success -> android.R.color.holo_green_dark
                            finished -> android.R.color.holo_red_dark
                            else -> android.R.color.black
                        }
                    )
                )
                if (finished) {
                    loginTimeoutJob?.cancel()
                    loginTimeoutJob = null
                    loginCheckTimeoutJob?.cancel()
                    loginCheckTimeoutJob = null
                    loginCheckButton.isEnabled = true
                    loginButton.isEnabled = true
                }
            }
        }

        val filter = IntentFilter("com.yquant.mtsa.ACTION_LOGIN_STATUS")
        registerReceiver(loginStatusReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        loginTimeoutJob?.cancel()
        loginCheckTimeoutJob?.cancel()
        loginStatusReceiver?.let { unregisterReceiver(it) }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "${packageName}/${packageName}.NeoSmartAccessibilityService"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(expectedServiceName) == true
    }
}
