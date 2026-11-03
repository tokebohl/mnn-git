package com.tokebohl.mnnnext.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starter JarvisBrainService automatisk når OnePlus 12R booter ind i LineageOS.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val serviceIntent = Intent(context, JarvisBrainService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
