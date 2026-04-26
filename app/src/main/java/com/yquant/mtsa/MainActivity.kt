package com.yquant.mtsa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var goTo7201Button: Button
    private lateinit var balanceButton: Button
    private lateinit var switchAccountButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var balanceDataTextView: TextView

    private var balanceReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        goTo7201Button = findViewById(R.id.goTo7201Button)
        balanceButton = findViewById(R.id.balanceButton)
        switchAccountButton = findViewById(R.id.switchAccountButton)
        statusTextView = findViewById(R.id.statusTextView)
        balanceDataTextView = findViewById(R.id.balanceDataTextView)

        goTo7201Button.setOnClickListener {
            navigateTo7201AfterDelay()
        }

        balanceButton.setOnClickListener {
            getBalanceAfterDelay()
        }

        switchAccountButton.setOnClickListener {
            switchAccountAfterDelay()
        }

        // 접근성 서비스 활성화 확인
        checkAccessibilityService()

        // Balance 데이터 수신 리시버 등록
        registerBalanceReceiver()
    }

    private fun navigateTo7201AfterDelay() {
        // 접근성 서비스가 활성화되어 있는지 확인
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 버튼 비활성화
        goTo7201Button.isEnabled = false
        statusTextView.text = "5초 후에 7201 화면으로 이동합니다..."

        // 5초 후에 접근성 서비스에 명령 전송
        CoroutineScope(Dispatchers.Main).launch {
            for (i in 5 downTo 1) {
                statusTextView.text = "${i}초 후에 7201 화면으로 이동합니다..."
                delay(1000)
            }

            // 접근성 서비스에 명령 보내기
            sendCommandToAccessibilityService("NAVIGATE_TO_7201")

            statusTextView.text = "명령을 전송했습니다. MTS 앱을 확인하세요."

            // 3초 후에 버튼 다시 활성화
            delay(3000)
            goTo7201Button.isEnabled = true
            statusTextView.text = ""
        }
    }

    private fun sendCommandToAccessibilityService(command: String) {
        // 브로드캐스트로 명령 전송
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

    private fun switchAccountAfterDelay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        switchAccountButton.isEnabled = false
        balanceDataTextView.visibility = android.view.View.GONE
        statusTextView.text = "계좌 변경 중입니다..."

        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)

            sendCommandToAccessibilityService("SWITCH_ACCOUNT")

            statusTextView.text = "계좌를 변경하고 있습니다..."

            delay(5000)

            switchAccountButton.isEnabled = true
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

    override fun onDestroy() {
        super.onDestroy()
        balanceReceiver?.let { unregisterReceiver(it) }
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
