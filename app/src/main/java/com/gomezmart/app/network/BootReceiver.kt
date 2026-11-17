package com.gomezmart.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.gomezmart.app.SyncManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                // Sincronización en segundo plano respetuosa con la batería y compatible con Android 12-15 mediante WorkManager
                SyncManager(context).scheduleOfflineSync()
            }
        }
    }
}
