package com.meshgames.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.meshgames.R
import com.meshgames.app.MeshGamesApp
import com.meshgames.game.GameType

/** Каналы и построение уведомлений приложения. */
object Notifiers {
    const val CHANNEL_INVITES = "invites"
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_RESULTS = "results"

    const val ACTION_ACCEPT = "com.meshgames.action.ACCEPT_INVITE"
    const val ACTION_DECLINE = "com.meshgames.action.DECLINE_INVITE"
    const val ACTION_RESUME = "com.meshgames.action.RESUME_GAME"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_OPEN_LIST = "open_list"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_INVITES, "Приглашения", NotificationManager.IMPORTANCE_HIGH),
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Связь", NotificationManager.IMPORTANCE_LOW),
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Итоги партий", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Уведомление о входящем приглашении с кнопками Принять/Отклонить. */
    fun invite(context: Context, sessionId: Int, peerName: String, game: GameType) {
        if (!canPost(context)) return
        ensureChannels(context)
        val accept = PendingIntent.getBroadcast(
            context, sessionId * 2,
            Intent(context, InviteActionReceiver::class.java)
                .setAction(ACTION_ACCEPT).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, sessionId * 2 + 1,
            Intent(context, InviteActionReceiver::class.java)
                .setAction(ACTION_DECLINE).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, sessionId,
            Intent(context, com.meshgames.ui.MainActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_INVITES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$peerName приглашает: ${game.title}")
            .setContentText("Нажмите «Принять», чтобы играть")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Принять", accept)
            .addAction(0, "Отклонить", decline)
            .build()
        NotificationManagerCompat.from(context).notify(sessionId, n)
    }

    /** Итог партии. */
    fun result(context: Context, sessionId: Int, text: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MeshGames")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(100000 + sessionId, n)
    }

    /**
     * Оппонент закрыл партию: «Продолжить» — возобновляем игру (соперник
     * получает уведомление о продолжении), «К списку» — открываем приложение
     * на списке партий. Тот же id, что и у [result] — уведомление заменяется.
     */
    fun peerClosed(context: Context, sessionId: Int, peerName: String, game: GameType) {
        if (!canPost(context)) return
        ensureChannels(context)
        val resume = PendingIntent.getBroadcast(
            context, sessionId * 3 + 200_000,
            Intent(context, InviteActionReceiver::class.java)
                .setAction(ACTION_RESUME).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openList = PendingIntent.getActivity(
            context, sessionId + 300_000,
            Intent(context, com.meshgames.ui.MainActivity::class.java)
                .putExtra(EXTRA_OPEN_LIST, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$peerName закрыл партию")
            .setContentText("${game.title} сохранена — можно продолжить позже")
            .setContentIntent(openList)
            .setAutoCancel(true)
            .addAction(0, "Продолжить", resume)
            .addAction(0, "К списку", openList)
            .build()
        NotificationManagerCompat.from(context).notify(100000 + sessionId, n)
    }

    /** Индикатор связи в шторке (foreground-сервис). */
    fun status(context: Context, text: String): Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.meshgames.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MeshGames")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun cancelInvite(context: Context, sessionId: Int) {
        NotificationManagerCompat.from(context).cancel(sessionId)
    }

    fun cancelResult(context: Context, sessionId: Int) {
        NotificationManagerCompat.from(context).cancel(100000 + sessionId)
    }

    fun app(context: Context): MeshGamesApp = context.applicationContext as MeshGamesApp
}
