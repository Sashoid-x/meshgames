package com.meshgames.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.meshgames.app.AppGraph
import com.meshgames.app.MeshGamesApp
import com.meshgames.app.ThemeMode
import com.meshgames.service.Notifiers

/**
 * Точка входа: edge-to-edge, тема, foreground-сервис, разрешение на
 * уведомления. Весь UI — в [AppRoot] (Navigation.kt); экраны разнесены по
 * файлам (GameScreen/Dialogs/SessionList/Opponents/Settings/Connect),
 * игровым состоянием владеет [GameViewModel].
 */
class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: контент рисуется под системными панелями (принудительно
        // на Android 15+/targetSdk 35+) — отступы за safeDrawing ниже.
        enableEdgeToEdge()
        graph = (application as MeshGamesApp).graph
        // Запуск по кнопке «К списку» из уведомления → сразу к списку партий.
        if (intent?.getBooleanExtra(Notifiers.EXTRA_OPEN_LIST, false) == true) {
            graph.goListTick.value++
        }
        // Разрешение на уведомления (Android 13+)
        val notifPerm = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        // Старт foreground-сервиса: связь + уведомления о приглашениях
        com.meshgames.service.MeshService.start(this)

        setContent {
            val theme by graph.settings.theme.collectAsState()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Контент не заходит под статус-бар/панель навигации.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        AppRoot(graph)
                    }
                }
            }
        }
    }

    // Приложение уже запущено — кнопка «К списку» в уведомлении досылается
    // через onNewIntent (singleTop), переход к списку обрабатывает AppRoot.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Notifiers.EXTRA_OPEN_LIST, false)) {
            graph.goListTick.value++
        }
    }
}
