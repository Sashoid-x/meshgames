package com.meshgames.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshgames.app.AppGraph
import com.meshgames.app.SoundPlayer
import com.meshgames.game.GameType
import com.meshgames.game.session.GameSession
import com.meshgames.game.session.SessionStatus
import com.meshgames.game.session.SessionView

// ------------------------------------------------------- приглашение ----

/** Входящее приглашение в игру: принять / отклонить. */
@Composable
fun InviteDialog(
    peerName: String,
    gameType: GameType,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Приглашение в игру") },
        text = { Text("$peerName приглашает сыграть: «${gameType.title}»") },
        confirmButton = {
            Button(onClick = onAccept) { Text("Принять") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text("Отклонить") }
        },
    )
}

// ------------------------------------------- закрытие/продолжение -------

/** Соперник закрыл игру с сохранением — предложить продолжить. */
@Composable
fun PeerClosedDialog(
    onResume: () -> Unit,
    onToList: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Оппонент закрыл игру") },
        text = { Text("Партия сохранена на обоих телефонах — можно продолжить позже.") },
        confirmButton = {
            Button(onClick = onResume) { Text("Продолжить") }
        },
        dismissButton = {
            TextButton(onClick = onToList) { Text("К списку") }
        },
    )
}

/** Соперник возобновил закрытую игру — можно вернуться к доске. */
@Composable
fun PeerResumedDialog(
    onPlay: () -> Unit,
    onToList: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Оппонент продолжает игру") },
        text = { Text("Партия снова активна на обоих телефонах — можно ходить.") },
        confirmButton = {
            Button(onClick = onPlay) { Text("К игре") }
        },
        dismissButton = {
            TextButton(onClick = onToList) { Text("К списку") }
        },
    )
}

/** Соперник удалил сохранённую сессию — предложить новую игру. */
@Composable
fun PeerDeletedDialog(
    peerName: String,
    gameType: GameType,
    onNewGame: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сессия удалена") },
        text = { Text("$peerName удалил сохранённую партию «${gameType.title}». Начать новую?") },
        confirmButton = {
            Button(onClick = onNewGame) { Text("Новая игра") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ОК") }
        },
    )
}

// --------------------------------------------------------- ничья -------

/** Соперник предлагает ничью (внутри игрового экрана). */
@Composable
fun DrawOfferDialog(
    peerName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ничья?") },
        text = { Text("$peerName предлагает ничью") },
        confirmButton = {
            Button(onClick = onAccept) { Text("Принять") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text("Отклонить") }
        },
    )
}

// -------------------------------------- действия с сохранённой сессией --

/** Текст состояния для карточки сессии и её диалога. */
fun sessionStateText(v: SessionView, sess: GameSession): String =
    when (sess.status) {
        SessionStatus.INVITING -> "ждём ответа…"
        SessionStatus.INVITED -> "вас приглашают!"
        SessionStatus.PLACEMENT -> "расстановка кораблей"
        SessionStatus.ACTIVE -> {
            val t = v.snapshot?.turn
            when {
                sess.closed -> "закрыта (с сохранением) — можно продолжить"
                sess.drawOfferByPeer -> "соперник предлагает ничью"
                sess.drawOfferByMe -> "вы предложили ничью"
                t == null -> "ход соперника"
                t == sess.mySide -> "ваш ход"
                else -> "ход соперника"
            }
        }
        SessionStatus.DECLINED, SessionStatus.CANCELLED -> "отменено"
        SessionStatus.FINISHED -> resultText(sess.result, sess.mySide)
    }

/** Выбор действия с сохранённой сессией: продолжить / новая игра / удалить. */
@Composable
fun SessionActionDialog(
    graph: AppGraph,
    v: SessionView,
    onOpenGame: (Int) -> Unit,
    onPickGameForPeer: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sess = v.session
    val resumable = sess.status == SessionStatus.ACTIVE ||
        sess.status == SessionStatus.PLACEMENT ||
        sess.status == SessionStatus.INVITING ||
        sess.status == SessionStatus.INVITED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sess.gameId.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${graph.peerName(sess.peerNode)} · ${sessionStateText(v, sess)}",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                if (resumable) {
                    TextButton(onClick = {
                        onDismiss()
                        if (sess.closed) graph.manager.resumeGame(sess.sessionId)
                        SoundPlayer.play(SoundPlayer.Cue.CLICK)
                        onOpenGame(sess.sessionId)
                    }, modifier = Modifier.fillMaxWidth()) { Text("▶ Продолжить") }
                }
                TextButton(onClick = {
                    onDismiss()
                    SoundPlayer.play(SoundPlayer.Cue.CLICK)
                    onPickGameForPeer(sess.peerNode)
                }, modifier = Modifier.fillMaxWidth()) { Text("＋ Новая игра") }
                TextButton(onClick = {
                    onDismiss()
                    SoundPlayer.play(SoundPlayer.Cue.TRASH)
                    graph.manager.deleteSession(sess.sessionId)
                }, modifier = Modifier.fillMaxWidth()) { Text("🗑 Удалить сессию") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
