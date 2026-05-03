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
    private lateinit var orderButton: Button
    private lateinit var selectDcAccountButton: Button
    private lateinit var selectIrpAccountButton: Button
    private lateinit var passwordInputButton: Button
    private lateinit var balanceButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var statusTextView: TextView
    private lateinit var balanceDataTextView: TextView

    private var balanceReceiver: BroadcastReceiver? = null
    private var loginStatusReceiver: BroadcastReceiver? = null
    private var loginTimeoutJob: Job? = null
    private var loginCheckTimeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginCheckButton = findViewById(R.id.loginCheckButton)
        loginButton = findViewById(R.id.loginButton)
        orderButton = findViewById(R.id.orderButton)
        selectDcAccountButton = findViewById(R.id.selectDcAccountButton)
        selectIrpAccountButton = findViewById(R.id.selectIrpAccountButton)
        passwordInputButton = findViewById(R.id.passwordInputButton)
        balanceButton = findViewById(R.id.balanceButton)
        settingsButton = findViewById(R.id.settingsButton)
        statusTextView = findViewById(R.id.statusTextView)
        balanceDataTextView = findViewById(R.id.balanceDataTextView)

        loginCheckButton.setOnClickListener {
            checkLoginStatusAfterDelay()
        }

        loginButton.setOnClickListener {
            loginAfterDelay()
        }

        orderButton.setOnClickListener {
            navigateToRetirementOrderAfterDelay()
        }

        selectDcAccountButton.setOnClickListener {
            navigateToRetirementOrderAndSelectAccountAfterDelay("DC")
        }

        selectIrpAccountButton.setOnClickListener {
            navigateToRetirementOrderAndSelectAccountAfterDelay("IRP")
        }

        passwordInputButton.setOnClickListener {
            probeAccountPasswordInputAfterDelay()
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
        balanceDataTextView.visibility = android.view.View.GONE
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

            sendCommandToAccessibilityService("NAVIGATE_TO_RETIREMENT_ORDER")

            statusTextView.text = "명령을 전송했습니다. MTS 앱을 확인하세요."

            delay(3000)
            orderButton.isEnabled = true
            statusTextView.text = ""
        }
    }

    private fun navigateToRetirementOrderAndSelectAccountAfterDelay(accountType: String) {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setAccountSelectionButtonsEnabled(false)
        statusTextView.text = "5초 후에 퇴직연금 주문 화면으로 이동한 뒤 $accountType 계좌를 선택합니다..."

        CoroutineScope(Dispatchers.Main).launch {
            for (i in 5 downTo 1) {
                statusTextView.text = "${i}초 후에 퇴직연금 주문 화면으로 이동한 뒤 $accountType 계좌를 선택합니다..."
                delay(1000)
            }

            sendCommandToAccessibilityService("SELECT_RETIREMENT_ORDER_ACCOUNT", accountType)

            statusTextView.text = "$accountType 계좌 선택 명령을 전송했습니다. MTS 앱을 확인하세요."

            delay(3000)
            setAccountSelectionButtonsEnabled(true)
            statusTextView.text = ""
        }
    }

    private fun setAccountSelectionButtonsEnabled(enabled: Boolean) {
        selectDcAccountButton.isEnabled = enabled
        selectIrpAccountButton.isEnabled = enabled
    }

    private fun probeAccountPasswordInputAfterDelay() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "접근성 서비스가 활성화되지 않았습니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        passwordInputButton.isEnabled = false
        balanceDataTextView.visibility = android.view.View.GONE
        statusTextView.text = "5초 후에 비밀번호 팝업을 열고 저장된 비밀번호를 입력합니다..."
        statusTextView.setTextColor(getColor(android.R.color.black))

        CoroutineScope(Dispatchers.Main).launch {
            for (i in 5 downTo 1) {
                statusTextView.text = "${i}초 후에 비밀번호 팝업을 열고 저장된 비밀번호를 입력합니다..."
                delay(1000)
            }

            sendCommandToAccessibilityService("ENTER_ACCOUNT_PASSWORD")
            statusTextView.text = "계좌 비밀번호 입력 명령을 전송했습니다. MTS 앱을 확인하세요."

            delay(25_000)
            passwordInputButton.isEnabled = true
        }
    }

    private fun sendCommandToAccessibilityService(command: String, accountType: String? = null) {
        val intent = Intent("com.yquant.mtsa.ACTION_NAVIGATE")
        intent.putExtra("command", command)
        accountType?.let { intent.putExtra("account_type", it) }
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
                    passwordInputButton.isEnabled = true
                } else {
                    statusTextView.text = "잔고 조회 실패"
                    passwordInputButton.isEnabled = true
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
