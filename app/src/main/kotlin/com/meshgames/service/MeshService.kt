package com.meshgames.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.meshgames.app.MeshGamesApp
import com.meshgames.app.LinkStatus
import com.meshgames.game.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-сервис: держит соединение с TCP-мостом (радиопуть через
 * форк Meshtastic с Mesh Bridge), следит за событиями сессий, показывает
 * уведомления о приглашениях и итогах.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs: List<Job> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as MeshGamesApp).graph
        startAsForeground("Подключение к ноде…")

        jobs = listOf(
            // статус соединения → текст в шторке + переподключение
            scope.launch {
                var last: LinkStatus? = null
                while (isActive) {
                    val s = graph.link.value
                    if (s != last) {
                        last = s
                        startAsForeground(statusText(s))
                        if (s is LinkStatus.Disconnected || s is LinkStatus.Error) {
                            delay(3000)
                            graph.ensureConnected()
                        }
                    }
                    delay(1000)
                }
            },
            // входящие приглашения → уведомления с кнопками
            scope.launch {
                graph.events.inviteReceived.collect { (sessionId, peerAndGame) ->
                    val (peerNode, gameType) = peerAndGame
                    if (!graph.settings.notifications.value) return@collect
                    val peerName = graph.peerName(peerNode)
                    Notifiers.invite(this@MeshService, sessionId, peerName, gameType)
                }
            },
            // приглашение больше не «висит» (ответили из приложения, кнопкой
            // на экране или уведомлением) → гасим его уведомление: второй
            // ответ на то же приглашение невозможен и визуально тоже.
            scope.launch {
                var invited = emptySet<Int>()
                graph.manager.sessions_.collect { list ->
                    val now = list
                        .filter { it.session.status == SessionStatus.INVITED }
                        .map { it.session.sessionId }
                        .toSet()
                    for (sid in invited - now) Notifiers.cancelInvite(this@MeshService, sid)
                    invited = now
                }
            },
            // итоги партий → уведомление
            scope.launch {
                graph.events.gameOver.collect { (sessionId, result) ->
                    if (!graph.settings.notifications.value) return@collect
                    val sess = graph.manager.session(sessionId)
                    val text = when {
                        result.winner == null -> "Партия окончена: ничья (${result.reason})"
                        sess != null && result.winner == sess.mySide -> "Вы победили!"
                        else -> "Вы проиграли (${result.reason})"
                    }
                    Notifiers.result(this@MeshService, sessionId, text)
                }
            },
            // соперник закрыл/продолжил игру → уведомления
            scope.launch {
                graph.events.gameClosed.collect { (sessionId, closed) ->
                    if (!graph.settings.notifications.value) return@collect
                    val sess = graph.manager.session(sessionId) ?: return@collect
                    if (closed) {
                        val peerName = graph.peerName(sess.peerNode)
                        Notifiers.peerClosed(this@MeshService, sessionId, peerName, sess.gameId)
                    } else {
                        // Продолжение: партнёр нажал «Продолжить» (или возобновил
                        // из уведомления) — сообщаем, что партия снова активна.
                        Notifiers.result(
                            this@MeshService, sessionId,
                            "Оппонент хочет продолжить партию «${sess.gameId.title}»",
                        )
                    }
                }
            },
            // соперник удалил сессию → уведомление
            scope.launch {
                graph.events.sessionDeleted.collect { (_, gameType, peerNode) ->
                    if (!graph.settings.notifications.value) return@collect
                    val peerName = graph.peerName(peerNode)
                    Notifiers.result(
                        this@MeshService, -1,
                        "$peerName удалил сохранённую партию «${gameType.title}»",
                    )
                }
            },
        )
        graph.ensureConnected()
    }

    private fun statusText(s: LinkStatus): String = when (s) {
        is LinkStatus.Disconnected -> "Нет связи"
        is LinkStatus.Connecting -> "Подключение (${s.via})…"
        is LinkStatus.Ready -> "${s.via}: готово (id ${s.myNodeNum.toString(16)})"
        is LinkStatus.Error -> "Ошибка ${s.via}: ${s.message}"
    }

    private fun startAsForeground(text: String) {
        val notification = Notifiers.status(this, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MeshService::class.java))
        }
    }
}
