package com.meshgames.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.meshgames.app.AppGraph
import com.meshgames.app.LinkStatus
import com.meshgames.app.SoundPlayer
import com.meshgames.game.GameResult
import com.meshgames.game.GameType
import com.meshgames.game.Side
import com.meshgames.game.session.SessionStatus
import kotlinx.coroutines.delay

/** Экраны приложения (простая навигация состоянием, без библиотек). */
sealed class Screen {
    /** Сплэш (2 секунды). */
    data object Splash : Screen()

    /** Список подключений: локально или сохранённые IP. */
    data object Connect : Screen()

    /** Сохранённые партии: продолжить / новая игра / настройки. */
    data object Sessions : Screen()

    /** Выбор оппонента для новой игры. */
    data object Opponents : Screen()

    /** Выбор игры после выбора оппонента; [back] — куда возвращаться. */
    data class GameType(val peerNode: Int, val back: Screen = Screen.Opponents) : Screen()

    /** Партия. */
    data class Game(val sessionId: Int) : Screen()

    data object Settings : Screen()
    data object About : Screen()
}

/** Единый текст статуса связи для всех экранов. */
fun statusText(link: LinkStatus): String = when (link) {
    is LinkStatus.Disconnected -> "нет связи"
    is LinkStatus.Connecting -> "подключение (${link.via})…"
    is LinkStatus.Ready -> "${link.via}: на связи (id ${link.myNodeNum.toString(16)})"
    is LinkStatus.Error -> "ошибка (${link.via}): ${link.message}"
}

/** Итог партии: «победа/поражение/ничья (причина)» глазами [mySide]. */
fun resultText(result: GameResult?, mySide: Side): String {
    if (result == null) return "завершено"
    val who = when (result.winner) {
        null -> "ничья"
        mySide -> "победа"
        else -> "поражение"
    }
    return "$who (${result.reason.name})"
}

/**
 * Корень UI: навигация по [Screen], сбор событий сессий в диалоги/звуки,
 * дебаг-панель поверх. Экраны и диалоги живут отдельными файлами
 * (GameScreen.kt, Dialogs.kt, SessionListScreen.kt, OpponentsScreen.kt,
 * SettingsScreen.kt, ConnectScreen.kt); состоянием игрового экрана владеет
 * [GameViewModel] — задел под новые игры без правок навигации.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    var screen by remember { mutableStateOf<Screen>(Screen.Splash) }
    val link by graph.link.collectAsState()
    val sound by graph.settings.sound.collectAsState()
    val debugPanelVisible by graph.settings.debugPanel.collectAsState()
    var connectRequested by remember { mutableStateOf(false) }

    // Звук включается/выключается настройкой.
    LaunchedEffect(sound) { SoundPlayer.enabled = sound }

    // Сплэш ровно 2 секунды: дальше — подключения (или сразу партии, если связь уже есть).
    LaunchedEffect(Unit) {
        delay(2000)
        screen = if (graph.link.value is LinkStatus.Ready) Screen.Sessions else Screen.Connect
    }

    // Пользователь выбрал подключение и оно поднялось → список сохранённых партий.
    LaunchedEffect(link) {
        if (connectRequested && link is LinkStatus.Ready) {
            connectRequested = false
            screen = Screen.Sessions
        }
    }

    // ------------------------------------------------------- события ----

    // Входящее приглашение -> модальный диалог (плюс уведомление от сервиса)
    var invite by remember { mutableStateOf<Pair<Int, Pair<Int, GameType>>?>(null) }
    LaunchedEffect(Unit) {
        graph.events.inviteReceived.collect {
            invite = it
            SoundPlayer.play(SoundPlayer.Cue.INVITE)
        }
    }

    // Соперник закрыл игру с сохранением / захотел продолжить
    var peerClosedId by remember { mutableStateOf<Int?>(null) }
    var peerResumedId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        graph.events.gameClosed.collect { (sessionId, closed) ->
            if (closed) {
                peerClosedId = sessionId
                SoundPlayer.play(SoundPlayer.Cue.ALERT)
            } else {
                // Соперник возобновил партию — сообщаем и даём перейти к игре.
                peerResumedId = sessionId
                SoundPlayer.play(SoundPlayer.Cue.CLICK)
            }
        }
    }

    // Соперник удалил сохранённую сессию
    var peerDeleted by remember { mutableStateOf<Triple<Int, GameType, Int>?>(null) }
    LaunchedEffect(Unit) {
        graph.events.sessionDeleted.collect {
            peerDeleted = it
            SoundPlayer.play(SoundPlayer.Cue.TRASH)
        }
    }

    // Итоги партий → звук победы/поражения
    LaunchedEffect(Unit) {
        graph.events.gameOver.collect { (sessionId, result) ->
            val sess = graph.manager.session(sessionId)
            SoundPlayer.play(
                when {
                    result.winner == null -> SoundPlayer.Cue.ALERT
                    sess != null && result.winner == sess.mySide -> SoundPlayer.Cue.WIN
                    else -> SoundPlayer.Cue.LOSE
                },
            )
        }
    }
    LaunchedEffect(Unit) {
        graph.events.drawOffered.collect { SoundPlayer.play(SoundPlayer.Cue.ALERT) }
    }
    // Ход/выстрел соперника
    LaunchedEffect(Unit) {
        graph.events.opponentAction.collect { SoundPlayer.play(SoundPlayer.Cue.OPPONENT) }
    }

    // Диалоги следят за состоянием сессий: ответили на приглашение кнопкой
    // на экране или из уведомления — диалог гаснет (второй ответ невозможен);
    // закрытие/возобновление, сделанное в другом месте, тоже закрывает диалог.
    LaunchedEffect(Unit) {
        graph.manager.sessions_.collect { list ->
            invite?.first?.let { sid ->
                val s = list.firstOrNull { it.session.sessionId == sid }?.session
                if (s == null || s.status != SessionStatus.INVITED) invite = null
            }
            peerClosedId?.let { sid ->
                val s = list.firstOrNull { it.session.sessionId == sid }?.session
                if (s == null || !s.closed) peerClosedId = null
            }
            peerResumedId?.let { sid ->
                val s = list.firstOrNull { it.session.sessionId == sid }?.session
                if (s == null || s.closed) peerResumedId = null
            }
        }
    }

    // Кнопка «К списку» в уведомлении о закрытии: открываем список партий.
    val goListTick by graph.goListTick.collectAsState()
    LaunchedEffect(goListTick) {
        if (goListTick > 0) screen = Screen.Sessions
    }

    // ------------------------------------------------------- диалоги ----

    invite?.let { (sessionId, peerAndGame) ->
        val (peerNode, gameType) = peerAndGame
        InviteDialog(
            peerName = graph.peerName(peerNode),
            gameType = gameType,
            onAccept = {
                invite = null
                graph.manager.respondInvite(sessionId, true)
                screen = Screen.Game(sessionId)
            },
            onDecline = {
                invite = null
                graph.manager.respondInvite(sessionId, false)
            },
            onDismiss = { invite = null },
        )
    }

    peerClosedId?.let { sessionId ->
        PeerClosedDialog(
            onResume = {
                peerClosedId = null
                // Возобновление: соперник получит уведомление о продолжении.
                graph.manager.resumeGame(sessionId)
                screen = Screen.Game(sessionId)
            },
            onToList = {
                peerClosedId = null
                screen = Screen.Sessions
            },
            onDismiss = { peerClosedId = null },
        )
    }

    peerResumedId?.let { sessionId ->
        PeerResumedDialog(
            onPlay = {
                peerResumedId = null
                screen = Screen.Game(sessionId)
            },
            onToList = {
                peerResumedId = null
                screen = Screen.Sessions
            },
            onDismiss = { peerResumedId = null },
        )
    }

    peerDeleted?.let { (_, gameType, peerNode) ->
        PeerDeletedDialog(
            peerName = graph.peerName(peerNode),
            gameType = gameType,
            onNewGame = {
                peerDeleted = null
                screen = Screen.GameType(peerNode, Screen.Sessions)
            },
            onDismiss = { peerDeleted = null },
        )
    }

    // -------------------------------------------------- навигация ------

    BackHandler(
        enabled = screen != Screen.Splash && screen != Screen.Connect && screen != Screen.Sessions,
    ) {
        screen = when (val s = screen) {
            is Screen.GameType -> s.back
            Screen.About -> Screen.Settings
            else -> Screen.Sessions
        }
    }

    // Экраны занимают всю область; дебаг-панель (Настройки → Диагностика) —
    // полупрозрачный оверлей поверх: макету не мешает, места поля не ест.
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when (val s = screen) {
                Screen.Splash -> SplashScreen()

                Screen.Connect -> ConnectScreen(
                    graph,
                    onConnectRequested = { connectRequested = true },
                    onOpenSettings = { screen = Screen.Settings },
                )

                Screen.Sessions -> SessionsScreen(
                    graph, link,
                    onNewGame = { screen = Screen.Opponents },
                    onOpenGame = { screen = Screen.Game(it) },
                    onPickGameForPeer = { screen = Screen.GameType(it, Screen.Sessions) },
                    onSettings = { screen = Screen.Settings },
                    onConnections = { screen = Screen.Connect },
                )

                Screen.Opponents -> OpponentsScreen(
                    graph,
                    onBack = { screen = Screen.Sessions },
                    onPick = { screen = Screen.GameType(it, Screen.Opponents) },
                )

                is Screen.GameType -> GameTypeScreen(
                    graph, s.peerNode,
                    onBack = { screen = s.back },
                    onInvited = { sessionId -> screen = Screen.Game(sessionId) },
                )

                is Screen.Game -> GameScreen(
                    graph, s.sessionId,
                    onFinished = { screen = Screen.Sessions },
                    // «Ещё раз»: свежая сессия той же игры — открываем её.
                    onPlayAgain = { newSid -> screen = Screen.Game(newSid) },
                )

                Screen.Settings -> SettingsScreen(
                    graph,
                    onBack = { screen = Screen.Sessions },
                    onConnections = { screen = Screen.Connect },
                    onAbout = { screen = Screen.About },
                )

                Screen.About -> AboutScreen(graph, onBack = { screen = Screen.Settings })
            }
        }

        if (debugPanelVisible && screen != Screen.Splash) {
            DebugPanel(
                onDismiss = { graph.settings.setDebugPanel(false) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .alpha(0.75f),
            )
        }
    }
}
