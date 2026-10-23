package com.meshgames.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshgames.BuildConfig
import com.meshgames.app.AppGraph
import com.meshgames.app.LinkStatus
import com.meshgames.app.SettingsStore
import com.meshgames.app.SoundPlayer
import com.meshgames.app.ThemeMode

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
    content()
}

@Composable
fun SettingsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onConnections: () -> Unit,
    onAbout: () -> Unit,
) {
    val theme by graph.settings.theme.collectAsState()
    val sound by graph.settings.sound.collectAsState()
    val notifications by graph.settings.notifications.collectAsState()
    val debugPanel by graph.settings.debugPanel.collectAsState()
    val presence by graph.settings.presenceIntervalMinutes.collectAsState()
    val link by graph.link.collectAsState()
    var playerName by remember { mutableStateOf(graph.playerName()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text("Настройки", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
        }

        Section("Подключение") {
            Card(Modifier.fillMaxWidth().clickable { onConnections() }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Радиосеть (мост MeshGames)", fontWeight = FontWeight.Bold)
                        Text(
                            statusText(link),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Section("Тема") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    val label = when (m) {
                        ThemeMode.SYSTEM -> "Системная"
                        ThemeMode.LIGHT -> "Светлая"
                        ThemeMode.DARK -> "Тёмная"
                    }
                    if (theme == m) {
                        Button(onClick = { graph.settings.setTheme(m) }) { Text(label, fontSize = 12.sp) }
                    } else {
                        OutlinedButton(onClick = { graph.settings.setTheme(m) }) { Text(label, fontSize = 12.sp) }
                    }
                }
            }
        }

        Section("Звук") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Звуки в играх и меню", Modifier.weight(1f), fontSize = 14.sp)
                Switch(
                    checked = sound,
                    onCheckedChange = {
                        graph.settings.setSound(it)
                        SoundPlayer.enabled = it
                        if (it) SoundPlayer.play(SoundPlayer.Cue.CLICK)
                    },
                )
            }
            TextButton(onClick = { SoundPlayer.play(SoundPlayer.Cue.MOVE) }) { Text("♪ Проверить звук") }
        }

        Section("Уведомления") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Уведомлять о приглашениях и итогах партий",
                    Modifier.weight(1f),
                    fontSize = 14.sp,
                )
                Switch(
                    checked = notifications,
                    onCheckedChange = { graph.settings.setNotifications(it) },
                )
            }
        }

        Section("Диагностика") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Панель дебага поверх экрана",
                    Modifier.weight(1f),
                    fontSize = 14.sp,
                )
                Switch(
                    checked = debugPanel,
                    onCheckedChange = { graph.settings.setDebugPanel(it) },
                )
            }
            Text(
                "Полупрозрачная: строки лога, копирование и экспорт — " +
                    "поверх экрана, рабочее место поля не отъедает.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Присутствие") {
            Text(
                "Как часто вещать маркер готовности в канал. " +
                    "Каждый маркер — эфирный пакет: реже = меньше нагрузка на эфир.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SettingsStore.MIN_PRESENCE_MIN,
                    SettingsStore.DEFAULT_PRESENCE_MIN,
                    15,
                    20,
                    SettingsStore.MAX_PRESENCE_MIN,
                ).distinct().forEach { m ->
                    if (presence == m) {
                        Button(onClick = { graph.settings.setPresenceInterval(m) }) {
                            Text("$m мин", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(onClick = { graph.settings.setPresenceInterval(m) }) {
                            Text("$m мин", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Section("Профиль") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    playerName,
                    { playerName = it },
                    Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("моё имя (видно соперникам)") },
                )
                Button(onClick = { graph.setPlayerName(playerName.trim().ifEmpty { playerName }) }) {
                    Text("ОК")
                }
            }
        }

        Section("О программе") {
            Card(Modifier.fillMaxWidth().clickable { onAbout() }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MeshGames v${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                        Text(
                            "протокол v2 · мост · сказано больше, чем в README",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------- о программе ---

private val FUN_FACTS = listOf(
    "Радиоволна доходит до соперника быстрее, чем ваш «Хм…» — но думать всё равно можно.",
    "Пакет MeshGames в эфире весит меньше, чем ваше имя в старом-старом SMS.",
    "«100% доставка» — не маркетинг: кадры повторяются по расписанию, " +
        "а подтверждение приезжает в ответном кадре. Настойчивость вознаграждается.",
    "Если соперник «думал» 40 секунд — он, скорее всего, просто вышел из зоны сети.",
    "Морской бой по радио — единственный случай, когда «промах» приходит с задержкой, " +
        "и это не баг.",
    "Мост — наш уголок: мимо чатов, через форк Meshtastic, прямо в эфир.",
    "Где-то сейчас летит кадр с seq 0x2A. Возможно, это был вы в шахматах.",
    "Мост прячет игровые события в отдельный порт — чужие приложения его не трогают.",
)

/** О программе: как работает, статистика, факты и парочка пасхалок. */
@Composable
fun AboutScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val link by graph.link.collectAsState()
    val sessions by graph.manager.sessions_.collectAsState()
    val peers by graph.bridge.peers.collectAsState()
    var taps by remember { mutableStateOf(0) }
    var factIndex by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Spacer(Modifier.weight(1f))
            Text("О программе", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(72.dp))
        }
        Spacer(Modifier.height(8.dp))
        MeshLogo(
            Modifier
                .size(120.dp)
                .clickable {
                    taps++
                    SoundPlayer.play(SoundPlayer.Cue.CLICK)
                    if (taps == 5) {
                        SoundPlayer.play(SoundPlayer.Cue.WIN)
                        Toast.makeText(
                            context,
                            "🎉 Вы нашли служебный бит эфира. Он ничего не делает — но мы гордимся.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
        )
        Spacer(Modifier.height(10.dp))
        Text("MeshGames", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            "v${BuildConfig.VERSION_NAME} · протокол v2 · собрано без единого сервера",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "«Если вы это читаете — у вас есть радио\nи немного терпения. Этого достаточно.»",
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Как это работает", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "Игровые события летят через TCP-мост форка Meshtastic " +
                        "(свой portnum, want_ack=false) — мимо чатов и прочих приложений.",
                    "Каждый кадр повторяется по фиксированному расписанию; " +
                        "подтверждение — piggyback cumAck в ответном кадре. " +
                        "Никакого ожидания: игра не замирает.",
                    "Движок проверяет ходы на ОБОИХ телефонах: жульничество невозможно " +
                        "по определению.",
                    "Состояние партии сохраняется на диск — прерванную игру можно " +
                        "продолжить хоть через неделю.",
                ).forEach {
                    Text("• $it", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Словарь Балды", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "66 994 слова — из морфологического словаря Хагена " +
                        "(публикация blog.kislenko.net). Расширить: файл balda_extra.txt " +
                        "в папке приложения, по слову на строку. Если слова нет ни у " +
                        "кого из игроков — спор решает соперник, а не алгоритм.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Цифры", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "сохранено партий: ${sessions.size} · " +
                        "игроков в эфире: ${peers.size} · " +
                        "мой id: ${when (val l = link) {
                            is LinkStatus.Ready -> l.myNodeNum.toString(16)
                            else -> "—"
                        }}",
                    fontSize = 13.sp,
                )
                Text("имя: ${graph.playerName()}", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Интересный факт", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(FUN_FACTS[factIndex % FUN_FACTS.size], fontSize = 13.sp)
                TextButton(onClick = {
                    factIndex++
                    SoundPlayer.play(SoundPlayer.Cue.CLICK)
                }) { Text("Ещё факт →") }
            }
        }
        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Благодарности", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Meshtastic — за открытый эфир и прекрасную документацию.\n" +
                        "Форку Meshtastic — за Mesh Bridge, нашу дверь в эфир.\n" +
                        "Jetpack Compose — за то, что этот экран помещается в один файл.\n" +
                        "Вам — за то, что играете там, где закончился интернет.",
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Собрано с ❤️ в 2026 году, когда интернет был слишком скучным.\n" +
                "Ни одного сервера не пострадало.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}
