package com.yquant.mtsa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

class MtsaCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()

        if (command !in SUPPORTED_COMMANDS) {
            logResult(
                requestId = requestId,
                command = command,
                success = false,
                state = "ERROR",
                message = "지원하지 않는 command입니다.",
            )
            return
        }

        if (!isAccessibilityServiceEnabled(context)) {
            logResult(
                requestId = requestId,
                command = command,
                success = false,
                state = "ERROR",
                message = "MTSA 접근성 서비스가 활성화되어 있지 않습니다.",
            )
            return
        }

        val serviceIntent = Intent(ACTION_NAVIGATE)
        serviceIntent.setPackage(context.packageName)
        serviceIntent.putExtra(EXTRA_COMMAND, command)
        serviceIntent.putExtra(EXTRA_REQUEST_ID, requestId)
        context.sendBroadcast(serviceIntent)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${context.packageName}.NeoSmartAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabledServices?.contains(expectedServiceName) == true
    }

    private fun logResult(
        requestId: String,
        command: String,
        success: Boolean,
        state: String,
        message: String,
    ) {
        val payload = JSONObject()
            .put("request_id", requestId)
            .put("command", command)
            .put("finished", true)
            .put("success", success)
            .put("state", state)
            .put("message", message)
        Log.i(TAG_RESULT, payload.toString())
    }

    companion object {
        const val ACTION_COMMAND = "com.yquant.mtsa.COMMAND"
        const val ACTION_NAVIGATE = "com.yquant.mtsa.ACTION_NAVIGATE"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_REQUEST_ID = "request_id"
        const val TAG_RESULT = "MtsaCommandResult"

        private val SUPPORTED_COMMANDS = setOf(
            "CHECK_LOGIN_STATUS",
            "LOGIN",
        )
    }
}
