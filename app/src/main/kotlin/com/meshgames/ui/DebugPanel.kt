package com.meshgames.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshgames.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val PanelBg = Color(0xF0101418)
private val PanelFg = Color(0xFF9FD39F)
private val PanelDim = Color(0xFF609060)

/**
 * Панель диагностики: последние строки [DebugLog] моноширинным шрифтом,
 * кнопки «копировать весь лог», «экспорт в Загрузки» (файл
 * `meshgames-<clientId>-<время>.log` — его можно прислать разработчику)
 * и «очистить» (только развёрнутой). Свёрнута — одна строка.
 *
 * По умолчанию панель скрыта; включается Настройки → Диагностика и
 * рисуется полупрозрачным оверлеем поверх экрана — макету (и доскам)
 * не мешает. [onDismiss] прячет панель (кнопка «✕»).
 */
@Composable
fun DebugPanel(modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val tail by DebugLog.tail.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Автопрокрутка вниз при новых строках.
    LaunchedEffect(tail.size) {
        if (expanded && tail.isNotEmpty()) listState.animateScrollToItem(tail.size - 1)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(PanelBg)
            .padding(horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) "▼ дебаг" else "▶ дебаг (${tail.size})",
                    fontSize = 11.sp, color = PanelFg,
                )
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(DebugLog.snapshot()))
                Toast.makeText(context, "Лог скопирован (${tail.size} строк)", Toast.LENGTH_SHORT).show()
            }) { Text("⧉ копировать", fontSize = 11.sp, color = PanelFg) }
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val name = DebugLog.exportToDownloads(context)
                    DebugLog.d("LOG", if (name != null) "экспорт в Загрузки: $name" else "экспорт не удался")
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (name != null) "Сохранено: Загрузки/$name" else "Экспорт не удался",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }) { Text("⟶ в Загрузки", fontSize = 11.sp, color = PanelFg) }
            if (expanded) {
                // Очистка — только развёрнутой: свёрнутую чистить незачем.
                TextButton(onClick = { DebugLog.clear() }) {
                    Text("очистить", fontSize = 11.sp, color = PanelDim)
                }
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("✕", fontSize = 12.sp, color = PanelDim)
                }
            }
            // Путь файла-лога — справа, только в свёрнутом виде (место в строке).
            if (!expanded) {
                Text(
                    DebugLog.filePath()?.substringAfterLast('/') ?: "",
                    fontSize = 8.sp, color = PanelDim,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
            }
        }
        if (expanded) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
            ) {
                items(tail) { line ->
                    Text(
                        line,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = PanelFg,
                        maxLines = 2,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
