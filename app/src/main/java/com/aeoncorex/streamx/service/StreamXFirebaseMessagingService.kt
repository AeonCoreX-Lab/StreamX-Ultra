package com.aeoncorex.streamx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.aeoncorex.streamx.MainActivity
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.data.FirestoreDb
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class StreamXFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID   = "streamx_announcements"
        const val CHANNEL_NAME = "StreamX Announcements"

        // Subscribe to topics based on user type
        // Call this from MainActivity/Application after auth state is known
        fun subscribeToTopics(isPremium: Boolean) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().apply {
                subscribeToTopic("all_users")
                if (isPremium) {
                    subscribeToTopic("premium_users")
                    unsubscribeFromTopic("free_users")
                } else {
                    subscribeToTopic("free_users")
                    unsubscribeFromTopic("premium_users")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Save FCM token to Firestore for user targeting
        val uid = Firebase.auth.currentUser?.uid ?: return
        FirestoreDb.instance.collection("users").document(uid)
            .update(mapOf("fcmToken" to token, "fcmUpdatedAt" to System.currentTimeMillis()))
            .addOnFailureListener { /* ignore — non-critical */ }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title        = message.notification?.title ?: message.data["title"] ?: return
        val body         = message.notification?.body  ?: message.data["body"]  ?: return
        val type         = message.data["type"] ?: "info"
        val actionUrl    = message.data["actionUrl"]

        showNotification(title, body, type, actionUrl)
    }

    private fun showNotification(
        title:     String,
        body:      String,
        type:      String,
        actionUrl: String?,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (no-op if already exists)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            when (type) {
                "urgent" -> NotificationManager.IMPORTANCE_HIGH
                "warning" -> NotificationManager.IMPORTANCE_DEFAULT
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
        ).apply {
            description   = "Important announcements from StreamX"
            enableLights(true)
            lightColor    = when (type) {
                "urgent"  -> Color.RED
                "warning" -> Color.YELLOW
                "success" -> Color.GREEN
                else      -> Color.MAGENTA
            }
            enableVibration(type == "urgent" || type == "warning")
        }
        nm.createNotificationChannel(channel)

        // Tap action — opens app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!actionUrl.isNullOrBlank()) putExtra("actionUrl", actionUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val accentColor = when (type) {
            "urgent"  -> android.graphics.Color.parseColor("#F43F5E")
            "warning" -> android.graphics.Color.parseColor("#F59E0B")
            "success" -> android.graphics.Color.parseColor("#10B981")
            else      -> android.graphics.Color.parseColor("#7C3AED")
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(accentColor)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (type == "urgent") NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
