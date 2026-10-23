package com.meshgames.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.meshgames.app.SoundPlayer
import com.meshgames.game.GameType
import com.meshgames.game.session.SessionStatus

/**
 * Выбор оппонента для новой игры. Приоритет: ★ избранные, затем те, кто
 * прислал маркер готовности (presence), свежие — выше.
 */
@Composable
fun OpponentsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val peers by graph.bridge.peers.collectAsState()
    val favorites by graph.settings.favorites.collectAsState()

    data class Candidate(
        val id: Int,
        val name: String,
        val marker: Boolean,
        val busy: Boolean,
        val lastSeen: Long,
        val isBot: Boolean,
    )

    val candidates: List<Candidate> = peers.values
        .map {
            Candidate(
                id = it.id,
                name = it.name,
                marker = true,
                busy = it.busy,
                lastSeen = it.lastSeenMs,
                isBot = it.isBot || it.name.contains("[AI]") || it.name.contains("[БОТ]") || it.name.contains("MeshBot"),
            )
        }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<Candidate> { it.id in favorites }
                .thenByDescending { it.marker }
                .thenByDescending { it.lastSeen },
        )

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text("Выбор соперника", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
        }
        Text(
            "★ — избранные сверху · ● — в сети · 🤖 — бот",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (peers.isEmpty()) {
            Text(
                "Игроки не найдены. Убедитесь, что MeshGames запущен у соперника " +
                    "и включён Mesh Bridge; список обновится сам.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(candidates) { c ->
                val favorite = c.id in favorites
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            SoundPlayer.play(SoundPlayer.Cue.CLICK)
                            onPick(c.id)
                        },
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            graph.settings.toggleFavorite(c.id)
                            SoundPlayer.play(SoundPlayer.Cue.CLICK)
                        }) {
                            Text(
                                if (favorite) "★" else "☆",
                                fontSize = 22.sp,
                                color = if (favorite) Color(0xFFF4B400)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (favorite) "★ " else "") + c.name,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (c.isBot) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "🤖 БОТ",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                "id 0x${c.id.toString(16)}" +
                                    (if (c.busy) " · в игре" else ""),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (c.marker) {
                            Text("●", color = Color(0xFF43A047), fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------- новая игра --

private val GAME_BLURBS = mapOf(
    GameType.TIC_TAC_TOE to "Классика на поле 3×3 — быстрый бой за пару ходов.",
    GameType.CHECKERS to "Взятия обязательны, дамки бьют через всю доску.",
    GameType.CHESS to "Полные правила: рокировки, взятие на проходе, превращение.",
    GameType.SEA_BATTLE to "Расставьте флот и потопите флот соперника.",
    GameType.BALDA to "Кладите букву и составляйте слова змейкой по полю 5×5.",
    GameType.DURAK to "36 карт на двоих, подкидной: бей старше или бери.",
)

/** Выбор игры после выбора оппонента → отправка приглашения. */
@Composable
fun GameTypeScreen(
    graph: AppGraph,
    peerNode: Int,
    onBack: () -> Unit,
    onInvited: (Int) -> Unit,
) {
    val sessions by graph.manager.sessions_.collectAsState()
    val peers by graph.bridge.peers.collectAsState()
    val peer = peers[peerNode]
    val peerName = graph.peerName(peerNode)
    val isBot = peer?.isBot == true || peerName.contains("[AI]") || peerName.contains("[БОТ]") || peerName.contains("MeshBot")
    var selectedDifficulty by remember { mutableStateOf(2) } // 1 = легкий, 2 = средний, 3 = сложный

    val alreadyPlaying = sessions.any {
        it.session.peerNode == peerNode &&
            (it.session.status == SessionStatus.ACTIVE ||
                it.session.status == SessionStatus.PLACEMENT ||
                it.session.status == SessionStatus.INVITING ||
                it.session.status == SessionStatus.INVITED)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text("Новая игра", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(peerName, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (isBot) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "🤖 Бот-сервер",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (alreadyPlaying) {
            Text(
                "⚠ У вас уже идёт партия с этим соперником — новая откроется параллельно.",
                fontSize = 12.sp,
                color = Color(0xFFE0A030),
            )
        }

        if (isBot) {
            Spacer(Modifier.height(8.dp))
            Text("Сложность бота:", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(1, "Лёгкий", Color(0xFF43A047)),
                    Triple(2, "Средний", Color(0xFFF4B400)),
                    Triple(3, "Сложный", Color(0xFFE53935)),
                ).forEach { (diff, label, color) ->
                    val selected = selectedDifficulty == diff
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedDifficulty = diff
                            SoundPlayer.play(SoundPlayer.Cue.CLICK)
                        },
                        label = {
                            Text(
                                label,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) color else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(GameType.entries) { gt ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            SoundPlayer.play(SoundPlayer.Cue.CLICK)
                            onInvited(graph.manager.invite(peerNode, gt, if (isBot) selectedDifficulty else 0))
                        },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(gt.icon, fontSize = 30.sp, modifier = Modifier.width(52.dp))
                        Column(Modifier.weight(1f)) {
                            Text(gt.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                GAME_BLURBS[gt] ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("→", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
