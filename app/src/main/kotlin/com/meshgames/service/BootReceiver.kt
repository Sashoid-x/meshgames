package com.meshgames.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Перезапуск соединения после перезагрузки — прерванные игры продолжаются. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MeshService.start(context)
        }
    }
}
