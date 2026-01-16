package com.aeoncorex.streamx.services

import android.os.Build
import android.util.Log
import com.aeoncorex.streamx.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object EmailService {

    private const val WORKER_URL = BuildConfig.EMAIL_WORKER_URL
    private const val AUTH_KEY = BuildConfig.EMAIL_AUTH_KEY

    fun sendWelcomeEmail(email: String, name: String) {
        if (WORKER_URL.isEmpty()) {
            Log.e("StreamX_Mail", "Worker URL is empty. Check GitHub Secrets.")
            return
        }
        
        val payload = JSONObject().apply {
            put("type", "welcome")
            put("user_email", email)
            put("user_name", name)
        }
        sendRequest(payload)
    }

    fun sendLoginAlert(email: String, name: String) {
        if (WORKER_URL.isEmpty()) return

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val payload = JSONObject().apply {
            put("type", "alert")
            put("user_email", email)
            put("user_name", name)
            put("device_name", deviceName)
        }
        sendRequest(payload)
    }

    private fun sendRequest(jsonBody: JSONObject) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(WORKER_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                
                // --- SECURITY: Send Authorization Token ---
                connection.setRequestProperty("Authorization", "Bearer $AUTH_KEY")
                
                connection.doOutput = true

                connection.outputStream.use { os ->
                    val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                Log.d("StreamX_Mail", "Email request sent. Response Code: $responseCode")
                
            } catch (e: Exception) {
                Log.e("StreamX_Mail", "Failed to send email trigger", e)
            }
        }
    }
}
