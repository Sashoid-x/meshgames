package com.meshgames.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshgames.app.AppGraph
import com.meshgames.app.LinkStatus
import com.meshgames.game.session.SessionView

/**
 * Сохранённые партии: выбрать сессию → «Продолжить» или «Новая игра»
 * (с тем же соперником); сессии можно удалять — соперник узнает об этом.
 */
@Composable
fun SessionsScreen(
    graph: AppGraph,
    link: LinkStatus,
    onNewGame: () -> Unit,
    onOpenGame: (Int) -> Unit,
    onPickGameForPeer: (Int) -> Unit,
    onSettings: () -> Unit,
    onConnections: () -> Unit,
) {
    val sessions by graph.manager.sessions_.collectAsState()
    var selected by remember { mutableStateOf<SessionView?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MeshGames", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    statusText(link),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onConnections) { Text("🔌") }
            TextButton(onClick = onSettings) { Text("⚙") }
        }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) {
            Text("＋ Новая игра")
        }
        Spacer(Modifier.size(12.dp))
        Text("Сохранённые партии", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (sessions.isEmpty()) {
            Spacer(Modifier.size(4.dp))
            Text(
                "Пока пусто. Нажмите «Новая игра» — выберите соперника и игру, " +
                    "либо дождитесь приглашения.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(sessions) { v ->
                val sess = v.session
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selected = v },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        val peer = graph.peerName(sess.peerNode)
                        Text("${sess.gameId.title} — $peer", fontWeight = FontWeight.Bold)
                        val state = sessionStateText(v, sess)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state, fontSize = 13.sp)
                            if (sess.closed) {
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    "закрыта",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            if (v.hasPending) {
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "⟳ доставка",
                                    fontSize = 12.sp,
                                    color = Color(0xFFE0A030),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { v -> SessionActionDialog(graph, v, onOpenGame, onPickGameForPeer) { selected = null } }
}
