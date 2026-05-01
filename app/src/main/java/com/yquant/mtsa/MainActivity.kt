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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var loginButton: Button
    private lateinit var orderButton: Button
    private lateinit var balanceButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var statusTextView: TextView
    private lateinit var balanceDataTextView: TextView

    private var balanceReceiver: BroadcastReceiver? = null
    private var loginStatusReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginButton = findViewById(R.id.loginButton)
        orderButton = findViewById(R.id.orderButton)
        balanceButton = findViewById(R.id.balanceButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusTextView = findViewById(R.id.statusTextView)
        balanceDataTextView = findViewById(R.id.balanceDataTextView)

        loginButton.setOnClickListener {
            loginAfterDelay()
        }

        orderButton.setOnClickListener {
            navigateToRetirementOrderAfterDelay()
        }

        balanceButton.setOnClickListener {
            getBalanceAfterDelay()
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        checkAccessibilityService()
        registerBalanceReceiver()
        registerLoginStatusReceiver()
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
        statusTextView.text = "공동인증서 로그인 준비 중입니다..."

        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            sendCommandToAccessibilityService("LOGIN")
            statusTextView.text = "로그인 명령을 전송했습니다. MTS 앱을 확인하세요."
        }
    }

    private fun navigateToRetirementOrderAfterDelay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        orderButton.isEnabled = false
        statusTextView.text = "5초 후에 퇴직연금 주문 화면으로 이동합니다..."

        CoroutineScope(Dispatchers.Main).launch {
            for (i in 5 downTo 1) {
                statusTextView.text = "${i}초 후에 퇴직연금 주문 화면으로 이동합니다..."
                delay(1000)
            }

            sendCommandToAccessibilityService("NAVIGATE_TO_7201")

            statusTextView.text = "명령을 전송했습니다. MTS 앱을 확인하세요."

            delay(3000)
            orderButton.isEnabled = true
            statusTextView.text = ""
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

    private fun getBalanceAfterDelay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        balanceButton.isEnabled = false
        balanceDataTextView.visibility = android.view.View.GONE
        statusTextView.text = "잔고 조회 중입니다..."

        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)

            sendCommandToAccessibilityService("GET_BALANCE")

            statusTextView.text = "잔고 데이터를 가져오는 중입니다..."

            delay(5000)

            balanceButton.isEnabled = true
        }
    }

    private fun registerBalanceReceiver() {
        balanceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val balanceData = intent?.getStringExtra("balance_data")
                if (balanceData != null) {
                    statusTextView.text = "잔고 조회 완료"
                    balanceDataTextView.text = balanceData
                    balanceDataTextView.visibility = android.view.View.VISIBLE
                } else {
                    statusTextView.text = "잔고 조회 실패"
                }
            }
        }

        val filter = IntentFilter("com.yquant.mtsa.ACTION_BALANCE_DATA")
        registerReceiver(balanceReceiver, filter)
    }

    private fun registerLoginStatusReceiver() {
        loginStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getStringExtra("status") ?: return
                val success = intent.getBooleanExtra("success", false)

                statusTextView.text = status
                statusTextView.setTextColor(
                    getColor(
                        if (success) android.R.color.holo_green_dark
                        else android.R.color.holo_red_dark
                    )
                )
                loginButton.isEnabled = true
            }
        }

        val filter = IntentFilter("com.yquant.mtsa.ACTION_LOGIN_STATUS")
        registerReceiver(loginStatusReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        balanceReceiver?.let { unregisterReceiver(it) }
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
