package com.meshgames.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshgames.app.AppGraph
import com.meshgames.app.LinkStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------- сплэш ----

/** Логотип: little mesh из узлов — рисуется кодом, без картинок. */
@Composable
fun MeshLogo(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2.7f
        val pts = (0 until 5).map { i ->
            val a = -PI / 2 + i * 2 * PI / 5
            Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
        }
        val line = accent.copy(alpha = 0.55f)
        for (p in pts) drawLine(line, c, p, strokeWidth = 5f, cap = StrokeCap.Round)
        val ring = accent.copy(alpha = 0.3f)
        for (i in pts.indices) {
            drawLine(ring, pts[i], pts[(i + 1) % pts.size], strokeWidth = 3f, cap = StrokeCap.Round)
        }
        for (p in pts) drawCircle(accent, radius = 11f, center = p)
        drawCircle(accent, radius = 19f, center = c)
        drawCircle(
            accent.copy(alpha = 0.35f), radius = 30f, center = c, style = Stroke(width = 5f),
        )
    }
}

/** Сплэш-скрин, ровно 2 секунды (таймер — в AppRoot). */
@Composable
fun SplashScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MeshLogo(Modifier.size(170.dp))
        Spacer(Modifier.height(22.dp))
        Text("MeshGames", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "шахматы · шашки · морской бой · крестики-нолики",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "без интернета. без серверов. чистый эфир.",
            fontSize = 12.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "пробуждаем эфир…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// -------------------------------------------------------- подключения ---

/**
 * Список подключений: «локально» (мост этого телефона) или сохранённые
 * IP-подключения (можно добавить несколько и удалить). Пока пользователь
 * выбирает, маркер готовности уже уходит в сеть (announce при подключении).
 */
@Composable
fun ConnectScreen(
    graph: AppGraph,
    onConnectRequested: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val link by graph.link.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подключение", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    statusText(link),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenSettings) { Text("⚙") }
        }
        Text(
            "Маркер готовности уже разослан: пока вы подключаетесь, " +
                "соперники видят вас в списке.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // Единственный путь: локальный мост форка Meshtastic
        Card(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onConnectRequested()
                    graph.ensureConnected()
                },
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📱 Радиосеть (мост)", fontWeight = FontWeight.Bold)
                    Text(
                        "Игра идёт через форк Meshtastic на этом телефоне — " +
                            "нужен включённый Mesh Bridge.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (link is LinkStatus.Ready) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
                }
            }
        }

        if (link is LinkStatus.Error || link is LinkStatus.Disconnected) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Установите форк Meshtastic-Android с Mesh Bridge и включите его " +
                    "(Настройки → Radio Config → Mesh Bridge) — мост слушает " +
                    "127.0.0.1:8090 на этом телефоне.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
