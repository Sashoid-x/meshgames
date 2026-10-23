package com.meshgames.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meshgames.app.MeshGamesApp

/** Кнопки в уведомлениях: Принять/Отклонить (приглашение), Продолжить (закрытие). */
class InviteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(Notifiers.EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return
        val graph = (context.applicationContext as MeshGamesApp).graph
        when (intent.action) {
            Notifiers.ACTION_ACCEPT -> graph.manager.respondInvite(sessionId, true)
            Notifiers.ACTION_DECLINE -> graph.manager.respondInvite(sessionId, false)
            Notifiers.ACTION_RESUME -> {
                // Возобновление закрытой игры: соперник получит уведомление
                // о продолжении (SESSION_CLOSE со значением 0).
                graph.manager.resumeGame(sessionId)
                Notifiers.cancelResult(context, sessionId)
                return
            }
        }
        Notifiers.cancelInvite(context, sessionId)
    }
}
