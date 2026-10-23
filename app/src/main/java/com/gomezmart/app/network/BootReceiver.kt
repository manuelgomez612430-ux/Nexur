package com.gomezmart.app.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val serviceIntent = Intent(context, GomezmartSyncService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }, 5000) // Delay para estabilidad tras reinicio
            }
        }
    }
}
