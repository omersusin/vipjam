package com.vipjam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.vipjam.service.VipJamService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VipJamService::class.java).apply {
                    action = VipJamService.ACTION_START
                }
            )
        }
    }
}
