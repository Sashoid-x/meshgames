package com.meshgames.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshgames.app.AppGraph
import com.meshgames.app.SoundPlayer
import com.meshgames.game.BaldaDispute
import com.meshgames.game.BaldaSnapshot
import com.meshgames.game.BaldaWordEntry
import com.meshgames.game.BoardSnapshot
import com.meshgames.game.CheckersSnapshot
import com.meshgames.game.ChessSnapshot
import com.meshgames.game.DurakPhase
import com.meshgames.game.DurakSnapshot
import com.meshgames.game.MoveInput
import com.meshgames.game.SeaBattleSnapshot
import com.meshgames.game.TttSnapshot
import com.meshgames.game.engine.BaldaEngine
import com.meshgames.game.engine.DurakEngine
import com.meshgames.game.session.GameSession
import com.meshgames.game.session.SessionStatus
import com.meshgames.game.session.SessionView
import kotlinx.coroutines.delay

// -------------------------------------------------------------- игра ----

/**
 * Игровой экран: шапка (заголовок/статус/латентность), кнопки партии и
 * доска. Состоянием и намерениями владеет [GameViewModel] (создаётся здесь
 * по ключу сессии) — новая игра добавляется доской в [GameBoard] без правок
 * в эту обвязку. Завершённая партия не закрывается: поле остаётся с
 * итогом и кнопками «Ещё раз» ([onPlayAgain] — переход на новую сессию)
 * и «Закрыть».
 */
@Composable
fun GameScreen(
    graph: AppGraph,
    sessionId: Int,
    onFinished: () -> Unit,
    onPlayAgain: (Int) -> Unit = {},
) {
    val vm: GameViewModel = viewModel(
        key = "game-$sessionId",
        factory = GameViewModel.factory(graph, sessionId),
    )
    val view by vm.view.collectAsState()
    // Латентность канала (оценка по piggyback-подтверждениям) — «~N с»
    // в строке статуса: видно, что связь жива и сколько она ждёт.
    val rttMs by vm.rttMs.collectAsState()
    if (view == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onFinished) { Text("← К списку") }
            Text("Партия не найдена (возможно, сессия удалена).")
        }
        return
    }
    val sess = view!!.session
    val snapshot = view!!.snapshot
    val peerName = vm.peerName(sess.peerNode)

    // Уведомление об откате партии после сверки журналов ходов: строка под
    // статусом, сама исчезает через несколько секунд.
    var rollbackNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        vm.sessionRecovered.collect { (sid, toEntry) ->
            if (sid != sessionId) return@collect
            rollbackNote = if (toEntry == 0) {
                "↺ Состояния разошлись — партия откатена к началу"
            } else {
                "↺ Состояния разошлись — откат к ходу №$toEntry, партия продолжена"
            }
            SoundPlayer.play(SoundPlayer.Cue.ALERT)
            delay(8000)
            rollbackNote = null
        }
    }

    // диалог ничьей от соперника
    var drawDialog by remember { mutableStateOf(sess.drawOfferByPeer) }
    LaunchedEffect(sess.drawOfferByPeer) { if (sess.drawOfferByPeer) drawDialog = true }
    if (drawDialog && sess.drawOfferByPeer) {
        DrawOfferDialog(
            peerName = peerName,
            onAccept = {
                drawDialog = false
                vm.respondDraw(true)
            },
            onDecline = {
                drawDialog = false
                vm.respondDraw(false)
            },
            onDismiss = { drawDialog = false },
        )
    }

    // Заголовок и состояние — общие для портрета и альбома.
    val titleText = "${sess.gameId.title} — $peerName"
    val stateText = when (sess.status) {
        SessionStatus.INVITING -> "ждём ответа соперника…"
        SessionStatus.INVITED -> "соперник ждёт вашего решения"
        SessionStatus.PLACEMENT ->
            "расстановка: ${if (sess.myReady) "вы готовы" else "расставьте корабли"}"
        SessionStatus.ACTIVE -> {
            val t = snapshot?.turn
            // Выстрел в полёте: ход уже сделан — честно показываем ожидание
            // результата, а не «ваш ход» (по факту ходить пока нельзя).
            val pendingShot = (snapshot as? SeaBattleSnapshot)?.pendingShot ?: -1
            // Балда: спорное слово — отдельные состояния, иначе статус врал
            // бы («ваш ход» у инициатора спора, пока ждём вердикт).
            val baldaDispute = (snapshot as? BaldaSnapshot)?.dispute
            when {
                sess.closed -> "игра закрыта (с сохранением)"
                sess.drawOfferByPeer -> "соперник предлагает ничью"
                sess.drawOfferByMe -> "вы предложили ничью"
                baldaDispute != null && baldaDispute.awaitingMyVerdict ->
                    "слово «${baldaDispute.word}» не в словаре — примите решение"
                baldaDispute != null -> "слово «${baldaDispute.word}» на проверке у соперника…"
                pendingShot >= 0 -> "ход сделан — ждём результат выстрела…"
                t == sess.mySide -> "ваш ход"
                // Мой ход ушёл в эфир, а подтверждения доставки (cumAck) ещё
                // нет — не показываем «ход соперника» как свершившийся факт.
                // Работает во всех играх: ответ соперника гасит ожидание
                // мгновенно, без ответа расписание повторов выдыхается (~125 с).
                view!!.hasPending -> "ход отправлен — ждём подтверждения…"
                else -> "ход соперника"
            }
        }
        SessionStatus.DECLINED, SessionStatus.CANCELLED -> "отменено"
        SessionStatus.FINISHED -> resultText(sess.result, sess.mySide)
    }
    // Одна строка состояния фиксированной высоты (⟳ — доставка, ~N с —
    // латентность канала): шапка не меняет высоту, доска не прыгает.
    val statusLine = stateText +
        (if (view!!.hasPending) " ⟳" else "") +
        (rttMs?.let { " · ~${(it + 500) / 1000} с" } ?: "")

    BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
        if (maxWidth <= maxHeight) {
            // ПОРТРЕТ: шапка сверху, доска ниже — Box(weight) отдаёт доске
            // ограниченную высоту, клетки честно считаются по доступному месту.
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            titleText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            statusLine,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.height(20.dp),
                        )
                    }
                    if (sess.status == SessionStatus.ACTIVE || sess.status == SessionStatus.PLACEMENT) {
                        OutlinedButton(onClick = {
                            // Закрыть: партия сохраняется, соперник получает SESSION_CLOSE.
                            vm.closeGame()
                            SoundPlayer.play(SoundPlayer.Cue.TRASH)
                            onFinished()
                        }) { Text("✕ Закрыть") }
                    }
                }
                rollbackNote?.let { note ->
                    Text(
                        note,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (sess.status == SessionStatus.ACTIVE) {
                    // Компактные кнопки — минимальная высота, доске достаётся больше.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { vm.offerDraw() },
                            enabled = !sess.drawOfferByMe,
                            modifier = Modifier.height(36.dp),
                        ) { Text("Ничья") }
                        TextButton(
                            onClick = { vm.resign() },
                            modifier = Modifier.height(36.dp),
                        ) { Text("Сдаться") }
                        TextButton(
                            onClick = {
                                // Принудительная синхронизация: SYNC_REQ
                                // приоритетной очередью, ни на что не ожидая.
                                if (vm.forceSync()) SoundPlayer.play(SoundPlayer.Cue.CLICK)
                            },
                            modifier = Modifier.height(36.dp),
                        ) { Text("⟳ Синхр") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ClosedGameCard(vm, sess, onFinished)
                GameArea(vm, view!!, onFinished, onPlayAgain, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            // АЛЬБОМ: кнопки и статусы — узкой рейкой слева, доске достаётся
            // вся высота экрана: поля максимально возможные, ничего не режется
            // и ничего не перекрывается.
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.width(190.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        titleText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLine,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.height(20.dp),
                    )
                    rollbackNote?.let { note ->
                        Text(
                            note,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (sess.status == SessionStatus.ACTIVE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { vm.offerDraw() },
                                enabled = !sess.drawOfferByMe,
                                modifier = Modifier.height(36.dp),
                            ) { Text("Ничья", fontSize = 13.sp) }
                            TextButton(
                                onClick = { vm.resign() },
                                modifier = Modifier.height(36.dp),
                            ) { Text("Сдаться", fontSize = 13.sp) }
                        }
                        // Принудительная синхронизация — во всех играх, свой рядом:
                        // SYNC_REQ уходит приоритетной очередью без ожиданий.
                        TextButton(
                            onClick = {
                                if (vm.forceSync()) SoundPlayer.play(SoundPlayer.Cue.CLICK)
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                        ) { Text("⟳ Синхронизация", fontSize = 13.sp) }
                    }
                    if (sess.status == SessionStatus.ACTIVE || sess.status == SessionStatus.PLACEMENT) {
                        OutlinedButton(
                            onClick = {
                                vm.closeGame()
                                SoundPlayer.play(SoundPlayer.Cue.TRASH)
                                onFinished()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("✕ Закрыть") }
                    }
                    ClosedGameCard(vm, sess, onFinished)
                    Spacer(Modifier.weight(1f))
                }
                GameArea(vm, view!!, onFinished, onPlayAgain, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** Карточка закрытой партии: продолжить с сохранённого состояния или к списку.
 *  Ничего не показывает, если игра не закрыта (высота шапки не меняется). */
@Composable
private fun ClosedGameCard(
    vm: GameViewModel,
    sess: GameSession,
    onFinished: () -> Unit,
) {
    if (!sess.closed || (sess.status != SessionStatus.ACTIVE && sess.status != SessionStatus.PLACEMENT)) {
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Игра закрыта и сохранена.", fontWeight = FontWeight.Bold)
            Button(
                onClick = {
                    vm.resumeGame()
                    SoundPlayer.play(SoundPlayer.Cue.CLICK)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("▶ Продолжить") }
            OutlinedButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                Text("К списку")
            }
        }
    }
}

/** Игровая область состояния: кнопки приглашения / доска / итог.
 *  [modifier] обязан нести weight в области вызывающего (Column/Row) — только
 *  так дети получат ограниченную высоту, и доски посчитают клетки по месту. */
@Composable
private fun GameArea(
    vm: GameViewModel,
    view: SessionView,
    onFinished: () -> Unit,
    onPlayAgain: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sess = view.session
    val snapshot = view.snapshot
    Column(modifier) {
        when (sess.status) {
            SessionStatus.INVITING -> Text("Отправляем приглашение… переотправка автоматическая.")
            SessionStatus.INVITED -> {
                // Кнопки здесь, а не только в диалоге: после рестарта диалог
                // уже не показывается, и без кнопок сессия зависала бы
                // в «соперник ждёт вашего решения» без возможности ответить.
                Text("Соперник ждёт вашего решения.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.respondInvite(true) }) { Text("Принять") }
                    OutlinedButton(onClick = { vm.respondInvite(false) }) {
                        Text("Отклонить")
                    }
                }
            }
            SessionStatus.PLACEMENT -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                PlacementPanel(vm, sess.myReady, view.snapshot)
            }
            SessionStatus.ACTIVE -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                GameBoard(vm, sess, snapshot)
            }
            SessionStatus.FINISHED -> {
                // Поле не закрывается: финальная позиция + крупный итог и
                // две кнопки внизу (не перекрывают поле — живут в потоке
                // макета). «Ещё раз» — новое приглашение тому же сопернику.
                Text(
                    resultText(sess.result, sess.mySide),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    GameBoard(vm, sess, snapshot)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val newSid = vm.playAgain()
                            if (newSid != null) {
                                SoundPlayer.play(SoundPlayer.Cue.CLICK)
                                onPlayAgain(newSid)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Ещё раз") }
                    OutlinedButton(
                        onClick = {
                            vm.dismiss()
                            SoundPlayer.play(SoundPlayer.Cue.TRASH)
                            onFinished()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Закрыть") }
                }
            }
            SessionStatus.DECLINED, SessionStatus.CANCELLED -> {
                Text("Приглашение отклонено/отменено.")
                Button(onClick = { vm.dismiss(); onFinished() }) {
                    Text("Закрыть")
                }
            }
        }
    }
}

/** Расстановка кораблей (морской бой): поле + «Переставить»/«Готово».
 *  После подтверждения (myReady, переживает рестарт) кнопки гаснут. */
@Composable
private fun PlacementPanel(
    vm: GameViewModel,
    myReady: Boolean,
    snapshot: BoardSnapshot?,
) {
    Column(Modifier.fillMaxSize()) {
        Text("Расставьте корабли (без соприкосновений):")
        if (snapshot is SeaBattleSnapshot) {
            // Поле занимает всё свободное место — клетки масштабируются.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SeaBattleBoard(
                    grid = snapshot.myGrid,
                    myTurn = false,
                    onCellTap = null,
                    gridTitle = vm.playerName(),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Text(
                "Ваши корабли: ${snapshot.myShipsLeft} палуб подтверждено",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    vm.reroll()
                    SoundPlayer.play(SoundPlayer.Cue.CLICK)
                },
                enabled = !myReady,
            ) { Text("Переставить") }
            Button(
                onClick = {
                    vm.ready()
                    SoundPlayer.play(SoundPlayer.Cue.MOVE)
                },
                enabled = !myReady,
            ) { Text(if (myReady) "Вы готовы ✓" else "Готово") }
        }
    }
}

/** Доска по типу снимка движка: тут добавляется ветка пятой игры. */
@Composable
private fun GameBoard(
    vm: GameViewModel,
    sess: GameSession,
    snapshot: BoardSnapshot?,
) {
    val sessionId = vm.sessionId
    val myTurn = snapshot?.turn == sess.mySide && !snapshot.finished && !sess.closed
    when (snapshot) {
        is TttSnapshot -> TttBoard(snapshot, myTurn, sess.mySide) { cell ->
            if (vm.submitMove(MoveInput.Cell(cell))) {
                SoundPlayer.play(SoundPlayer.Cue.MOVE)
            }
        }
        is ChessSnapshot -> ChessBoard(
            snapshot, myTurn, sess.mySide,
            legalFrom = { from -> vm.legalMoves(from) },
            onMove = { from, to, promo ->
                if (vm.submitMove(MoveInput.FromTo(from, to, promo))) {
                    SoundPlayer.play(SoundPlayer.Cue.MOVE)
                }
            },
        )
        is CheckersSnapshot -> {
            val sel = vm.checkersSelection()
            // Выбранная точка завершения и индекс маршрута. В одну клетку может
            // вести несколько разных цепочек взятий (русские шашки не требуют
            // максимального взятия) — тогда маршрут переключается кнопками.
            var pendingDest by remember(sessionId) { mutableStateOf(-1) }
            var pendingRoute by remember(sessionId) { mutableStateOf(0) }
            val selFrom = sel?.first ?: -1
            val dests = sel?.second ?: emptySet()
            val routes =
                if (selFrom >= 0 && pendingDest in dests) vm.checkersRoutes(pendingDest)
                else emptyList()
            val routeIndex = pendingRoute.coerceIn(0, (routes.size - 1).coerceAtLeast(0))
            CheckersBoard(
                snapshot, myTurn, sess.mySide,
                selected = selFrom,
                destinations = dests,
                // Все ветви в выбранную клетку — змейками на доске, кнопками
                // ◀ ▶ игрок переключает выбранную (яркую) ветвь.
                routes = routes,
                chosenDest = if (routes.isNotEmpty()) pendingDest else -1,
                routeIndex = routeIndex,
                // Кнопки нужны, когда тап по шашке неоднозначен (ход завершается
                // на исходной клетке) и когда в клетку ведёт несколько маршрутов.
                choiceButtons = selFrom >= 0 && (selFrom in dests || routes.size > 1),
                onSquareTap = tap@{ sq ->
                    val s = vm.checkersSelection() ?: return@tap
                    val from = s.first
                    val ds = s.second
                    when {
                        // точка завершения (в т.ч. исходная клетка самой шашки):
                        // первый тап — маршрут, повторный — ход
                        sq in ds -> {
                            if (pendingDest == sq) {
                                if (vm.checkersCommit(sq, routeIndex)) {
                                    SoundPlayer.play(SoundPlayer.Cue.MOVE)
                                    pendingDest = -1
                                    pendingRoute = 0
                                }
                            } else {
                                pendingDest = sq
                                pendingRoute = 0
                            }
                        }
                        // выбора ещё нет или тап по той же шашке — обычная отмена
                        sq == from -> {
                            vm.checkersCancel()
                            pendingDest = -1
                            pendingRoute = 0
                        }
                        // другая своя фигура — переключение выбора
                        else -> {
                            vm.checkersSelect(sq)
                            pendingDest = -1
                            pendingRoute = 0
                        }
                    }
                },
                onRouteChange = { idx ->
                    pendingRoute = idx.coerceIn(0, (routes.size - 1).coerceAtLeast(0))
                },
                onCommit = {
                    if (vm.checkersCommit(pendingDest, routeIndex)) {
                        SoundPlayer.play(SoundPlayer.Cue.MOVE)
                        pendingDest = -1
                        pendingRoute = 0
                    }
                },
                onCancel = {
                    vm.checkersCancel()
                    pendingDest = -1
                    pendingRoute = 0
                },
            )
        }
        is SeaBattleSnapshot -> {
            val myName = vm.playerName()
            SeaBattleBoard(
                grid = snapshot.oppGrid,
                myTurn = myTurn && snapshot.pendingShot == -1,
                onCellTap = { coord ->
                    if (vm.submitMove(MoveInput.Shoot(coord))) {
                        SoundPlayer.play(SoundPlayer.Cue.MOVE)
                    }
                },
                gridTitle = vm.peerName(sess.peerNode),
                myGrid = snapshot.myGrid,
                myTitle = myName,
                pendingShot = snapshot.pendingShot,
            )
        }
        is BaldaSnapshot -> BaldaPanel(vm, sess, snapshot)
        is DurakSnapshot -> DurakPanel(vm, sess, snapshot)
        null -> Text("Загрузка состояния…")
    }
}

// -------------------------------------------------------------- дурак --

/**
 * Ретро-кнопка в стиле Windows 95 / XP: приподнятая 3D-фаска, серый фон.
 */
@Composable
private fun RetroButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xFFD4D0C8), RoundedCornerShape(2.dp))
            .border(
                1.dp,
                if (enabled) Color(0xFF000000) else Color(0xFF808080),
                RoundedCornerShape(2.dp),
            )
            .drawBehind {
                if (enabled) {
                    // Верх и лево — белый блик
                    drawLine(Color(0xFFFFFFFF), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 3f)
                    drawLine(Color(0xFFFFFFFF), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3f)
                    // Низ и право — тёмная тень
                    drawLine(Color(0xFF404040), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 3f)
                    drawLine(Color(0xFF404040), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), strokeWidth = 3f)
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = if (enabled) Color(0xFF000000) else Color(0xFF808080),
        )
    }
}

/**
 * Панель строки состояния Windows: утопленная 3D-фаска.
 */
@Composable
private fun RetroStatusPanel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xFFD4D0C8))
            .drawBehind {
                // Утопленная 3D-рамка: верх/лево серые (#808080), низ/право белые (#FFFFFF)
                drawLine(Color(0xFF808080), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f)
                drawLine(Color(0xFF808080), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f)
                drawLine(Color(0xFFFFFFFF), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 2f)
                drawLine(Color(0xFFFFFFFF), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), strokeWidth = 2f)
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF000000),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Колода со слоями толщины и открытым козырем под ней (как в SweetGames).
 */
@Composable
private fun DurakDeckStack(
    deckCount: Int,
    trumpCard: Int,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val cardHeight = cardWidth * (96f / 71f)
    if (deckCount <= 0) {
        Box(
            modifier = modifier
                .size(cardWidth, cardHeight)
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("0", color = Color(0x66FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        return
    }

    Box(modifier = modifier.size(cardWidth * 1.55f, cardHeight * 1.25f)) {
        // Открытый козырь снизу слева
        PlayingCard(
            code = trumpCard,
            width = cardWidth,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // Колода сверху рубашкой, сдвинута вправо и вниз
        if (deckCount > 1) {
            val deckOffsetX = cardWidth * 0.45f
            val deckOffsetY = cardHeight * 0.22f
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = deckOffsetX, y = deckOffsetY)
                    .size(cardWidth, cardHeight),
            ) {
                // Слои толщины колоды на срезе
                val layers = minOf(4, (deckCount - 1) / 3 + 1)
                for (i in layers downTo 1) {
                    val offset = (i * 2).dp
                    Box(
                        modifier = Modifier
                            .offset(x = -offset, y = offset)
                            .size(cardWidth, cardHeight)
                            .background(Color(0xFFE2E0D0), RoundedCornerShape(2.dp))
                            .border(1.dp, Color(0xFF000000), RoundedCornerShape(2.dp)),
                    )
                }
                // Верхняя карта колоды (рубашка)
                PlayingCard(
                    code = 0,
                    width = cardWidth,
                    faceDown = true,
                )
            }
        }

        // Бэйдж с остатком колоды
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color(0xEE000000), RoundedCornerShape(2.dp))
                .border(1.dp, Color(0xFFFFEE00), RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                "$deckCount",
                color = Color(0xFFFFEE00),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Пара захода: атакующая карта снизу, бьющая карта сверху со сдвигом.
 */
@Composable
private fun DurakAttackPair(
    attackCode: Int,
    beatCode: Int,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val cardHeight = cardWidth * (96f / 71f)
    val hasBeat = beatCode >= 0
    val boxWidth = if (hasBeat) cardWidth + 12.dp else cardWidth
    val boxHeight = if (hasBeat) cardHeight + 12.dp else cardHeight

    Box(modifier = modifier.size(boxWidth, boxHeight)) {
        // Карта атаки
        PlayingCard(
            code = attackCode,
            width = cardWidth,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // Бьющая карта (сдвинута вправо-вниз, открывая индекс нижней)
        if (hasBeat) {
            PlayingCard(
                code = beatCode,
                width = cardWidth,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 12.dp, y = 12.dp),
            )
        }
    }
}

/**
 * Дурак подкидной в визуале SweetGames / Windows Solitaire (зелёное сукно,
 * классические карты cards.dll, открытый козырь под колодой со слоями,
 * жёлтый промпт, ретро-кнопки и строка состояния).
 */
@Composable
private fun DurakPanel(
    vm: GameViewModel,
    sess: GameSession,
    snapshot: DurakSnapshot,
) {
    val myTurn = snapshot.turn == sess.mySide && !snapshot.finished
    val myAttack = myTurn && snapshot.phase == DurakPhase.ATTACK
    val myDefend = myTurn && snapshot.phase == DurakPhase.DEFEND && !snapshot.taking
    val canEnough = myAttack && snapshot.attacks.isNotEmpty() &&
        (snapshot.taking || snapshot.tableBeats.none { it == -1 })
    val cardsOnTable = snapshot.attacks.size + snapshot.tableBeats.count { it >= 0 }
    val discardCount = 36 - snapshot.deckCount - snapshot.myHand.size -
        snapshot.peerHandCount - cardsOnTable

    val promptText = when {
        snapshot.finished -> "> Партия завершена"
        snapshot.taking && myAttack ->
            if (snapshot.legalThrows.isNotEmpty()) "> Соперник берёт! Подкиньте вдогонку или «Хватит»"
            else "> Соперник берёт! Подкинуть нечего — нажмите «Хватит»"
        snapshot.taking -> "> Вы берёте со стола. Соперник подкидывает…"
        myDefend -> "> Бейте или нажмите «Беру»"
        myAttack && snapshot.attacks.isEmpty() -> "> Твой ход"
        myAttack -> "> Подкиньте карту или «Хватит»"
        snapshot.phase == DurakPhase.DEFEND -> "> Соперник отбивается…"
        else -> "> Ход соперника"
    }

    fun onCardTap(card: Int) {
        val input = when {
            myAttack -> MoveInput.DurakAttack(card)
            myDefend -> MoveInput.DurakBeat(card)
            else -> null
        }
        if (input != null && vm.submitMove(input)) {
            SoundPlayer.play(SoundPlayer.Cue.MOVE)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val totalHeight = maxHeight
        val isLandscape = totalWidth > totalHeight

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF008000), RoundedCornerShape(6.dp))
                .border(2.dp, Color(0xFF004D00), RoundedCornerShape(6.dp))
                .padding(6.dp),
        ) {
            if (isLandscape) {
                val playerCardWidth = ((totalHeight - 24.dp) / 4.6f).coerceIn(38.dp, 52.dp)
                val attackCardWidth = (playerCardWidth * 0.95f).coerceIn(36.dp, 48.dp)
                val opponentCardWidth = (playerCardWidth * 0.80f).coerceIn(30.dp, 40.dp)
                val deckCardWidth = (playerCardWidth * 0.85f).coerceIn(32.dp, 44.dp)

                Column(Modifier.fillMaxSize()) {
                    // Верх: жёлтый статус-промпт + соперник
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            promptText,
                            color = Color(0xFFFFEE00),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        if (snapshot.peerHandCount > 0) {
                            val overlap = if (snapshot.peerHandCount > 8) (-10).dp else 2.dp
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(overlap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                repeat(snapshot.peerHandCount) {
                                    PlayingCard(code = 0, width = opponentCardWidth, faceDown = true)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Стол: слева заход, справа колода с козырьком
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (snapshot.attacks.isNotEmpty()) {
                                    Row(
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        for (i in snapshot.attacks.indices) {
                                            DurakAttackPair(
                                                attackCode = snapshot.attacks[i],
                                                beatCode = snapshot.tableBeats.getOrElse(i) { -1 },
                                                cardWidth = attackCardWidth,
                                            )
                                        }
                                    }
                                }
                            }
                            DurakDeckStack(
                                deckCount = snapshot.deckCount,
                                trumpCard = snapshot.trump,
                                cardWidth = deckCardWidth,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Низ: рука игрока + кнопки
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomStart) {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                for (card in snapshot.myHand) {
                                    val canPlay = (myDefend && card in snapshot.legalBeats) ||
                                        (myAttack && (snapshot.attacks.isEmpty() || card in snapshot.legalThrows))
                                    PlayingCard(
                                        code = card,
                                        width = playerCardWidth,
                                        highlighted = canPlay,
                                        modifier = Modifier
                                            .offset(y = if (canPlay) (-8).dp else 0.dp)
                                            .clickable(enabled = canPlay) {
                                                onCardTap(card)
                                            },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RetroButton(
                                text = "Беру",
                                enabled = myDefend,
                                onClick = {
                                    if (vm.submitMove(MoveInput.DurakTake)) {
                                        SoundPlayer.play(SoundPlayer.Cue.MOVE)
                                    }
                                },
                            )
                            RetroButton(
                                text = "Хватит",
                                enabled = canEnough,
                                onClick = {
                                    if (vm.submitMove(MoveInput.DurakEnough)) {
                                        SoundPlayer.play(SoundPlayer.Cue.MOVE)
                                    }
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Статус-бар в 3 секции
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFD4D0C8))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RetroStatusPanel("Колода: ${snapshot.deckCount}", Modifier.weight(1f))
                        RetroStatusPanel("Бито: $discardCount", Modifier.weight(1f))
                        RetroStatusPanel(
                            "Козырь: ${DurakEngine.RANK_NAMES[snapshot.trump / 4]}${DurakEngine.SUIT_NAMES[snapshot.trump % 4]}",
                            Modifier.weight(1.2f),
                        )
                    }
                }
            } else {
                // ПОРТРЕТ
                val playerCardWidth = ((totalWidth - 28.dp) / 6.2f).coerceIn(44.dp, 58.dp)
                val attackCardWidth = (playerCardWidth * 0.95f).coerceIn(40.dp, 54.dp)
                val opponentCardWidth = (playerCardWidth * 0.82f).coerceIn(34.dp, 44.dp)
                val deckCardWidth = (playerCardWidth * 0.85f).coerceIn(36.dp, 48.dp)

                Column(Modifier.fillMaxSize()) {
                    // 1. Верх: Жёлтый статус-промпт
                    Text(
                        promptText,
                        color = Color(0xFFFFEE00),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                    )

                    // 2. Чужая рука (рубашками)
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (snapshot.peerHandCount > 0) {
                            val overlap = if (snapshot.peerHandCount > 6) (-10).dp else 3.dp
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(overlap),
                                verticalAlignment = Alignment.Top,
                            ) {
                                repeat(snapshot.peerHandCount) {
                                    PlayingCard(code = 0, width = opponentCardWidth, faceDown = true)
                                }
                            }
                        } else {
                            Text("(у соперника нет карт)", color = Color(0xAAFFFFFF), fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 3. Стол (заход + козырь/колода)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Заход (пары атака/бой)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (snapshot.attacks.isEmpty()) {
                                    Box(
                                        Modifier
                                            .size(attackCardWidth, attackCardWidth * (96f / 71f))
                                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(2.dp)),
                                    )
                                } else {
                                    Row(
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        for (i in snapshot.attacks.indices) {
                                            DurakAttackPair(
                                                attackCode = snapshot.attacks[i],
                                                beatCode = snapshot.tableBeats.getOrElse(i) { -1 },
                                                cardWidth = attackCardWidth,
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            // Колода с козырьком
                            DurakDeckStack(
                                deckCount = snapshot.deckCount,
                                trumpCard = snapshot.trump,
                                cardWidth = deckCardWidth,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // 4. Кнопки управления ("Беру", "Хватит") + подсказка
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RetroButton(
                            text = "Беру",
                            enabled = myDefend,
                            onClick = {
                                if (vm.submitMove(MoveInput.DurakTake)) {
                                    SoundPlayer.play(SoundPlayer.Cue.MOVE)
                                }
                            },
                        )
                        RetroButton(
                            text = "Хватит",
                            enabled = canEnough,
                            onClick = {
                                if (vm.submitMove(MoveInput.DurakEnough)) {
                                    SoundPlayer.play(SoundPlayer.Cue.MOVE)
                                }
                            },
                        )
                        Text(
                            durakHint(snapshot, myAttack, myDefend),
                            fontSize = 11.sp,
                            color = Color(0xDDFFFFFF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // 5. Моя рука (лицом вверх, с подсветкой и подъёмом играемых)
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            for (card in snapshot.myHand) {
                                val canPlay = (myDefend && card in snapshot.legalBeats) ||
                                    (myAttack && (snapshot.attacks.isEmpty() || card in snapshot.legalThrows))
                                PlayingCard(
                                    code = card,
                                    width = playerCardWidth,
                                    highlighted = canPlay,
                                    modifier = Modifier
                                        .offset(y = if (canPlay) (-10).dp else 0.dp)
                                        .clickable(enabled = canPlay) {
                                            onCardTap(card)
                                        },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 6. Классический статус-бар Windows в 3 секции
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFD4D0C8))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RetroStatusPanel("Колода: ${snapshot.deckCount}", Modifier.weight(1f))
                        RetroStatusPanel("Бито: $discardCount", Modifier.weight(1f))
                        RetroStatusPanel(
                            "Козырь: ${DurakEngine.RANK_NAMES[snapshot.trump / 4]}${DurakEngine.SUIT_NAMES[snapshot.trump % 4]}",
                            Modifier.weight(1.2f),
                        )
                    }
                }
            }
        }
    }
}

/** Подсказка фазы дурака (подкидной): защита, подкидывание, бито. */
private fun durakHint(snapshot: DurakSnapshot, myAttack: Boolean, myDefend: Boolean): String {
    val onTable = snapshot.attacks.size + snapshot.tableBeats.count { it >= 0 }
    return when {
        snapshot.finished -> "Партия завершена — итог над полем."
        snapshot.taking && myAttack ->
            if (snapshot.legalThrows.isNotEmpty()) "Соперник берёт: подкиньте карту того же номинала или «Хватит»."
            else "Соперник берёт: подкинуть больше нечего — нажмите «Хватит»."
        snapshot.taking -> "Вы берёте со стола ($onTable карт) — ждём, пока соперник подкинет или нажмёт «Хватит»."
        myDefend -> "Бейте «${DurakEngine.cardName(snapshot.attacks.lastOrNull() ?: -1)}» " +
            "старше той же масти либо козырем — или «Беру» (заберёте всё: $onTable карт)."
        myAttack && snapshot.attacks.isEmpty() -> "Ваш ход: сыграйте любую карту из руны."
        myAttack && snapshot.legalThrows.isNotEmpty() ->
            "Подкиньте карту того же номинала (подсвечены) или «Хватит»."
        myAttack -> "Подкинуть нечем — «Хватит»: заход уходит в сброс."
        snapshot.phase == DurakPhase.DEFEND -> "Ждём: соперник бьёт или берёт…"
        else -> "Ход соперника…"
    }
}

// -------------------------------------------------------------- балда --

/**
 * Балда: доска, выбор буквы таблицей поверх поля (своя, без IME — клавиатура
 * приложения не перекрывает поле; пары е/ё, и/й, ь/ъ живут в одной клетке),
 * ручная прокладка пути и диалог спора («Принять/Отказать» — вердикт кадром
 * MOVE). В альбоме — как в морском бое: слева доска, справа счёт и слова.
 *
 * Сценарии: тап пустой клетки рядом с буквами поля («глухие» клетки UI не
 * даёт выбрать) → поверх поля таблица букв → выбранная буква лежит на поле
 * полутоном, игрок тапает путь змейки: повторный тап по последней клетке —
 * отправка, тап по первой — сброс пути, долгий тап по новой букве — убрать
 * её (кнопок управления нет). Слово вне словаря не уходит в спор молча —
 * предупреждение «Отмена/Отправить». Список слов — всё свободное место
 * под доской, два столбика в обеих ориентациях (в альбоме — справа от
 * доски): заголовки «Имя: счёт», слова новые сверху, «слово» +N = очки.
 */
@Composable
private fun BaldaPanel(
    vm: GameViewModel,
    sess: GameSession,
    snapshot: BaldaSnapshot,
) {
    val sessionId = vm.sessionId
    val myTurn = snapshot.turn == sess.mySide && !snapshot.finished && !sess.closed
    val dispute = snapshot.dispute

    var selectedCell by remember(sessionId) { mutableStateOf(-1) }
    var chosenLetter by remember(sessionId) { mutableStateOf(0) }
    var manualMode by remember(sessionId) { mutableStateOf(false) }
    var manualPath by remember(sessionId) { mutableStateOf(IntArray(0)) }
    var hint by remember(sessionId) { mutableStateOf<String?>(null) }
    // Слово вне словаря: игрок подтверждает спор (диалог «Отмена/Отправить»).
    var disputeConfirm by remember(sessionId) { mutableStateOf(false) }

    fun resetInput() {
        selectedCell = -1
        chosenLetter = 0
        manualMode = false
        manualPath = IntArray(0)
        hint = null
        disputeConfirm = false
    }

    // Ход ушёл к сопернику или появился спор — ввод гасим.
    LaunchedEffect(snapshot.turn, dispute, myTurn) {
        if (!myTurn || dispute != null) resetInput()
    }

    // Собираемое слово в ручном режиме (буквы по клеткам пути).
    val liveWord = if (manualMode && manualPath.isNotEmpty() && selectedCell >= 0 && chosenLetter > 0) {
        buildString {
            for (idx in manualPath) {
                append(
                    if (idx == selectedCell) BaldaEngine.codeLetter(chosenLetter)
                    else BaldaEngine.codeLetter(snapshot.cells[idx]),
                )
            }
        }
    } else ""

    fun submitManual(force: Boolean = false): Boolean {
        // Слово не в словаре — не спорим молча: показываем предупреждение
        // («проверьте, нет ли ошибки?»), игрок выбирает Отмена/Отправить.
        if (!force && liveWord.isNotEmpty() && !vm.baldaWordKnown(liveWord)) {
            disputeConfirm = true
            return false
        }
        val ok = vm.submitMove(MoveInput.LetterAdd(selectedCell, chosenLetter, manualPath))
        if (ok) {
            SoundPlayer.play(SoundPlayer.Cue.MOVE)
            resetInput()
        } else {
            hint = "Ход не принят: путь — соседние клетки без повторов через новую клетку, " +
                "слово не должно повторяться (включая стартовое)"
        }
        return ok
    }

    // --- спор: мой вердикт (диалог обязателен — без решения партия висит) ---
    if (dispute != null && dispute.awaitingMyVerdict) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Проверка слова") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Соперник предлагает слово:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "«${dispute.word}»",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "В вашем словаре его нет. Принять — очки сопернику и ход " +
                            "переходит к вам; отклонить — буква снимается, ход остаётся у соперника.",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.submitVerdict(true)
                    SoundPlayer.play(SoundPlayer.Cue.MOVE)
                    resetInput()
                }) { Text("Принять", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.submitVerdict(false)
                    SoundPlayer.play(SoundPlayer.Cue.CLICK)
                    resetInput()
                }) { Text("Отказать") }
            },
        )
    }

    // --- слово вне словаря: подтверждение перед спором («Отмена/Отправить») ---
    if (disputeConfirm && manualMode) {
        AlertDialog(
            onDismissRequest = { disputeConfirm = false },
            title = { Text("Слова нет в словаре") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "«${liveWord}»",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Проверьте, нет ли ошибки. «Отправить» — соперник решит, " +
                            "принять слово или отклонить; «Отмена» — вернуться к вводу.",
                        fontSize = 13.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    disputeConfirm = false
                    submitManual(force = true) // уйдёт сопернику как спорное
                }) { Text("Отправить", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { disputeConfirm = false }) { Text("Отмена") }
            },
        )
    }

    // Обработчики доски — общие для портрета и альбома.
    val onTap: (Int) -> Unit = { i ->
        if (manualMode && selectedCell >= 0) {
            val path = manualPath
            when {
                // повторный тап по последней клетке пути — отправка
                // (последней может быть и новая, и старая клетка)
                path.size >= 2 && i == path.last() && selectedCell in path ->
                    submitManual()
                // тап по пройденной клетке — откат пути к ней; по первой —
                // путь сброшен к ней (это и есть «сброс пути»)
                i in path -> manualPath = path.copyOf(path.indexOf(i) + 1)
                path.isEmpty() && (i == selectedCell || snapshot.cells[i] != 0) ->
                    manualPath = intArrayOf(i)
                path.isNotEmpty() && BaldaEngine.adjacent(path.last(), i) &&
                    (i == selectedCell || snapshot.cells[i] != 0) && path.size < 25 ->
                    manualPath = path + i
                else ->
                    hint = "Ткните соседнюю клетку; в пути не должно быть повторов"
            }
        } else when {
            i == selectedCell -> resetInput() // снять выбор
            snapshot.cells[i] == 0 && myTurn -> if (baldaTouchFilled(snapshot.cells, i)) {
                selectedCell = i
                hint = null
            } else {
                // Правило балды: новую букву ставят только рядом
                // с буквами на поле (иначе слова не собрать).
                hint = "Букву можно ставить только в клетку, соседнюю с буквами поля"
            }
        }
    }
    val onLongTap: (Int) -> Unit = { i ->
        // Долгий тап по новой букве — убрать её с поля (ввод с начала).
        if (i == selectedCell && chosenLetter > 0) resetInput()
    }
    val onPick: (Int) -> Unit = { code ->
        // Буква сразу лежит полутоном на поле; путь — тапами клеток.
        chosenLetter = code
        manualMode = true
        manualPath = IntArray(0)
        hint = "Ткните клетки по порядку: повторный тап по последней клетке — " +
            "отправка, долгий тап по новой букве — убрать её"
    }
    val onSkip: () -> Unit = {
        if (vm.submitMove(MoveInput.Skip())) {
            SoundPlayer.play(SoundPlayer.Cue.CLICK)
        }
        resetInput()
    }
    val myTitle = "${vm.playerName()}: ${snapshot.myScore}"
    val peerTitle = "${vm.peerName(sess.peerNode)}: ${snapshot.peerScore}"
    val myWords = snapshot.words.asReversed()
        .filter { it.side == sess.mySide }
    val peerWords = snapshot.words.asReversed()
        .filter { it.side != sess.mySide }
    // Последнее действие — пропуск соперника: красная плашка над доской.
    val peerPassedLast = snapshot.words.lastOrNull()
        ?.let { it.passed && it.side != sess.mySide } == true

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            // Как в морском бое: слева доска, справа счёт и слова.
            Row(Modifier.fillMaxSize()) {
                BaldaPlay(
                    snapshot = snapshot,
                    myTurn = myTurn && dispute == null,
                    selectedCell = selectedCell,
                    chosenLetter = chosenLetter,
                    manualMode = manualMode,
                    manualPath = manualPath,
                    onTap = onTap,
                    onLongTap = onLongTap,
                    onPick = onPick,
                    onDismiss = { resetInput() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    BaldaSkipRow(
                        myTurn = myTurn,
                        disputeActive = dispute != null,
                        peerPassedLast = peerPassedLast,
                        onSkip = onSkip,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                    )
                    // Слова занимают всё место колонки — два столбика,
                    // как в портрете.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WordColumn(
                                title = myTitle,
                                words = myWords,
                                accent = true,
                                modifier = Modifier.weight(1f),
                            )
                            WordColumn(
                                title = peerTitle,
                                words = peerWords,
                                accent = false,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    BaldaHints(
                        dispute = dispute,
                        myTurn = myTurn,
                        selectedCell = selectedCell,
                        manualMode = manualMode,
                        liveWord = liveWord,
                        pathSize = manualPath.size,
                        hint = hint,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    )
                }
            }
        } else {
            // Портрет: доска компактно (клетки по месту), список слов — всё
            // свободное место под ней, два столбика; подсказки внизу.
            val boardMaxH = (maxHeight - 32.dp - 92.dp - 64.dp).coerceAtLeast(140.dp)
            Column(Modifier.fillMaxSize()) {
                BaldaSkipRow(
                    myTurn = myTurn,
                    disputeActive = dispute != null,
                    peerPassedLast = peerPassedLast,
                    onSkip = onSkip,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
                Box(Modifier.fillMaxWidth().heightIn(max = boardMaxH)) {
                    BaldaPlay(
                        snapshot = snapshot,
                        myTurn = myTurn && dispute == null,
                        selectedCell = selectedCell,
                        chosenLetter = chosenLetter,
                        manualMode = manualMode,
                        manualPath = manualPath,
                        onTap = onTap,
                        onLongTap = onLongTap,
                        onPick = onPick,
                        onDismiss = { resetInput() },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        WordColumn(
                            title = myTitle,
                            words = myWords,
                            accent = true,
                            modifier = Modifier.weight(1f),
                        )
                        WordColumn(
                            title = peerTitle,
                            words = peerWords,
                            accent = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                BaldaHints(
                    dispute = dispute,
                    myTurn = myTurn,
                    selectedCell = selectedCell,
                    manualMode = manualMode,
                    liveWord = liveWord,
                    pathSize = manualPath.size,
                    hint = hint,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                )
            }
        }
    }
}

/**
 * Ряд над доской: слева — красная плашка «Соперник пропустил ход»
 * (пропуск иначе незаметен: просто меняется «ваш ход»), справа — кнопка
 * «Пропустить ход» в своем ходу. Высота постоянная, доска не прыгает.
 */
@Composable
private fun BaldaSkipRow(
    myTurn: Boolean,
    disputeActive: Boolean,
    peerPassedLast: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (peerPassedLast) {
            Text(
                "Соперник пропустил ход",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (myTurn && !disputeActive) {
            TextButton(onClick = onSkip, modifier = Modifier.height(32.dp)) {
                Text("Пропустить ход", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Доска балды с таблицей букв поверх: выбор клетки (только рядом с
 * занятыми), полутонная буква до подтверждения, ручной путь змейкой,
 * долгий тап по новой букве — убрать её.
 */
@Composable
private fun BaldaPlay(
    snapshot: BaldaSnapshot,
    myTurn: Boolean,
    selectedCell: Int,
    chosenLetter: Int,
    manualMode: Boolean,
    manualPath: IntArray,
    onTap: (Int) -> Unit,
    onLongTap: (Int) -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        BaldaBoard(
            snapshot = snapshot,
            myTurn = myTurn,
            selectedCell = selectedCell,
            manualMode = manualMode && selectedCell >= 0,
            manualPath = if (manualMode) manualPath else IntArray(0),
            // Полутонная буква лежит на поле до подтверждения хода.
            ghostCell = if (chosenLetter > 0) selectedCell else -1,
            ghostLetter = chosenLetter,
            // При вводе пути чужая змейка гасится — не путает со своим.
            dimLastPath = manualPath.isNotEmpty(),
            onCellTap = onTap,
            onCellLongTap = onLongTap,
        )
        // Таблица букв поверх поля: клетка выбрана, буквы ещё нет.
        if (myTurn && selectedCell >= 0 && chosenLetter == 0) {
            BaldaLetterPicker(
                modifier = Modifier.matchParentSize(),
                onPick = onPick,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * Подсказки/статусы балды: спор, собираемое слово, чей ход. Кнопок
 * управления нет — всё делается тапами по доске (отправка — тап по
 * последней клетке пути, сброс пути — тап по первой, удаление буквы —
 * долгий тап по ней).
 */
@Composable
private fun BaldaHints(
    dispute: BaldaDispute?,
    myTurn: Boolean,
    selectedCell: Int,
    manualMode: Boolean,
    liveWord: String,
    pathSize: Int,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            dispute != null && !dispute.awaitingMyVerdict -> Text(
                "Слово «${dispute.word}» отправлено сопернику — ждём решения…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dispute != null -> Text(
                "Слово «${dispute.word}» ждёт вашего решения — ответьте в диалоге.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            manualMode && selectedCell >= 0 -> {
                Text(
                    hint ?: "Ткните клетки по порядку: повторный тап по последней клетке — " +
                        "отправка, долгий тап по новой букве — убрать её",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (liveWord.isNotEmpty()) {
                    Text(
                        "«$liveWord» ($pathSize)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
            selectedCell >= 0 -> Text(
                hint ?: "Выберите букву в таблице над полем " +
                    "(пары е/ё, и/й, ь/ъ живут в одной клетке).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> Text(
                if (myTurn) hint ?: "Ваш ход: ткните пустую клетку рядом с буквами поля."
                else "Ход соперника…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Есть ли рядом (по сторонам света) занятая клетка — куда можно ставить
 * букву: правило балды, UI не даёт выбрать «глухую» пустую клетку
 * (движок и так не пропустит ход — путь через неё не построить).
 */
private fun baldaTouchFilled(cells: IntArray, cell: Int): Boolean =
    cells.indices.any { BaldaEngine.adjacent(it, cell) && cells[it] != 0 }

/**
 * Таблица букв поверх поля (6×5 = 30 клеток из [BaldaEngine.LETTER_PICKER]):
 * своя, без IME — не перекрывает поле системной клавиатурой; пары е/ё, и/й,
 * ь/ъ живут в одной клетке (канон-код движка). Тап по букве — выбор, тап
 * мимо сетки — отмена выбора клетки.
 */
@Composable
private fun BaldaLetterPicker(
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // Клетка под доступное место поверх доски (не больше 56 dp).
        val pick = minOf(
            56.dp,
            (maxWidth - 24.dp) / 6,
            (maxHeight - 16.dp) / 5,
        ).coerceAtLeast(30.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BaldaEngine.LETTER_PICKER.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (label, code) ->
                        Box(
                            Modifier
                                .size(pick)
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(6.dp),
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { onPick(code) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (label.length > 1) (pick.value * 0.26f).sp
                                else (pick.value * 0.44f).sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Столбик слов одной стороны: сверху «Имя: счёт», ниже записи (новые сверху,
 * «слово» +N = очки; пропуск — красное «Пропуск +0»). Прокручивается —
 * место фиксировано соседом/доской.
 */
@Composable
private fun WordColumn(
    title: String,
    words: List<BaldaWordEntry>,
    accent: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        for (w in words) {
            Text(
                if (w.passed) "Пропуск +0" else "«${w.word}» +${w.word.length}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    w.passed -> MaterialTheme.colorScheme.error
                    accent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
