package com.meshgames.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshgames.R
import com.meshgames.game.BaldaSnapshot
import com.meshgames.game.CheckersSnapshot
import com.meshgames.game.ChessSnapshot
import com.meshgames.game.Side
import com.meshgames.game.TttSnapshot
import com.meshgames.game.engine.BaldaEngine
import com.meshgames.game.engine.DurakEngine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------- шахматы --

/**
 * Деревянная доска (стиль chess.jpg): рамка из тёмного дерева, тёплые
 * квадраты, фигуры «плоские» — кремовые (Side A) и угольные (Side B).
 */
private val WoodFrame = Color(0xFF5C3A21)
private val ChessLight = Color(0xFFE3B183)
private val ChessDark = Color(0xFF7A4526)
private val PieceWhite = Color(0xFFF5E6CB)
private val PieceBlack = Color(0xFF2F3337)

private fun pieceGlyph(code: Int): String = when (kotlin.math.abs(code)) {
    1 -> "♟"
    2 -> "♞"
    3 -> "♝"
    4 -> "♜"
    5 -> "♛"
    6 -> "♚"
    else -> ""
}

/** Тень-обводка: светлые фигуры читаются на светлых клетках и т.д. */
private fun pieceStyle(white: Boolean): TextStyle = if (white) {
    TextStyle(shadow = Shadow(Color(0x77000000), offset = Offset(1.5f, 1.5f), blurRadius = 2f))
} else {
    TextStyle(shadow = Shadow(Color(0x33FFFFFF), offset = Offset(1f, 1f), blurRadius = 1f))
}

// ------------------------------------------------ координаты доски 8×8 --

/** Ширина рейки с цифрами горизонталей (под шрифт 11sp). */
private val BoardRail = 14.dp

/** Отступ от края рамки до клеток: 3.dp бордюра + 4.dp padding. */
private val BoardInset = 7.dp

/** Высота строки букв под/над полем (под шрифт 11sp). */
private val BoardLabelRow = 16.dp

/** Алгебраическая разметка файла по столбцу [c] (0 = «a»). */
private fun fileLabel(c: Int): String = ('a' + c).toString()

/** Алгебраическая разметка горизонтали по строке [r] (0 = 8-я). */
private fun rankLabel(r: Int): String = (8 - r).toString()

/**
 * Буквы файлов над и под полем: слева направо — как их видит игрок.
 *
 * Цвет — из темы: рейки лежат ВНЕ деревянной рамки, прямо на фоне экрана,
 * поэтому кремовый «под дерево» на светлой теме был нечитаем.
 */
@Composable
private fun FileLabels(cell: Dp, flipped: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(start = BoardInset, end = BoardInset)) {
        for (cv in 0..7) {
            val c = if (flipped) 7 - cv else cv
            Text(
                fileLabel(c),
                fontSize = 11.sp,
                fontFamily = FontFamily.Cursive,
                color = ink,
                modifier = Modifier.width(cell).height(BoardLabelRow).wrapContentSize(),
            )
        }
    }
}

/** Цифры горизонталей слева и справа от поля. Цвет — см. [FileLabels]. */
@Composable
private fun RankLabels(cell: Dp, flipped: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = BoardInset)) {
        for (rv in 0..7) {
            val r = if (flipped) 7 - rv else rv
            Text(
                rankLabel(r),
                fontSize = 11.sp,
                fontFamily = FontFamily.Cursive,
                color = ink,
                modifier = Modifier.width(BoardRail).height(cell).wrapContentSize(),
            )
        }
    }
}

/**
 * Шахматная доска. [mySide] == A — играю белыми (снизу). Выбор фигуры +
 * подсветка легальных клеток; при ходе пешки на последнюю горизонталь —
 * выбор фигуры превращения. Размер клетки подстраивается под доступную
 * площадь (портрет/альбом).
 */
@Composable
fun ChessBoard(
    snapshot: ChessSnapshot,
    myTurn: Boolean,
    mySide: Side,
    legalFrom: (Int) -> Set<Int>,
    onMove: (from: Int, to: Int, promo: Int) -> Unit,
) {
    var selected by remember { mutableStateOf(-1) }
    var promoFrom by remember { mutableStateOf(-1) }
    var promoTo by remember { mutableStateOf(-1) }
    val flipped = mySide == Side.B // чёрные играют сверху
    val legal = remember(selected, snapshot, myTurn) {
        if (selected >= 0 && myTurn) legalFrom(selected) else emptySet()
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Слот «Шах!» зарезервирован всегда — появление текста не двигает доску.
        // Резерв увеличен на две строки букв координат (2×16dp) и на рейки цифр.
        val cell = minOf(46.dp, (maxWidth - BoardRail * 2) / 8, (maxHeight - 80.dp) / 8)
            .coerceAtLeast(22.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            FileLabels(cell, flipped)
            Row(verticalAlignment = Alignment.Top) {
                RankLabels(cell, flipped)
                Box(
                    Modifier
                        .background(WoodFrame, RoundedCornerShape(6.dp))
                        .border(3.dp, Color(0xFF3E2514), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                ) {
                    Column {
                        for (rv in 0..7) {
                            val r = if (flipped) 7 - rv else rv
                            Row {
                                for (cv in 0..7) {
                                    val c = if (flipped) 7 - cv else cv
                                    val i = r * 8 + c
                                    val code = snapshot.board[i]
                                    val isLight = (r + c) % 2 == 0
                                    Box(
                                        Modifier
                                            .size(cell)
                                            .background(if (isLight) ChessLight else ChessDark)
                                            .then(
                                                when {
                                                    i == selected -> Modifier.border(
                                                        2.dp, Color(0xFFE0A030), RoundedCornerShape(4.dp),
                                                    )
                                                    i in legal && myTurn -> Modifier.border(
                                                        1.dp, Color(0xFF80C060), RoundedCornerShape(4.dp),
                                                    )
                                                    else -> Modifier
                                                },
                                            )
                                            .clickable(enabled = myTurn) {
                                                val mine = code != 0 &&
                                                    (code > 0) == (mySide == Side.A) &&
                                                    snapshot.turn == mySide
                                                when {
                                                    i == selected -> selected = -1
                                                    mine -> selected = i
                                                    selected >= 0 && i in legal -> {
                                                        // превращение пешки?
                                                        val from = selected
                                                        val rowTo = i / 8
                                                        if (snapshot.board[from] == (if (mySide == Side.A) 1 else -1) &&
                                                            (rowTo == 0 || rowTo == 7)
                                                        ) {
                                                            promoFrom = from
                                                            promoTo = i
                                                        } else {
                                                            onMove(from, i, 0)
                                                            selected = -1
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            pieceGlyph(code),
                                            fontSize = (cell.value * 0.66f).sp,
                                            color = if (code >= 0) PieceWhite else PieceBlack,
                                            style = pieceStyle(white = code >= 0),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                RankLabels(cell, flipped)
            }
            FileLabels(cell, flipped)
            Box(Modifier.height(24.dp)) {
                if (snapshot.checked != null) {
                    Text(
                        "Шах!",
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (promoFrom >= 0) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { promoFrom = -1 },
            title = { Text("Превращение") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(5 to "♛", 4 to "♜", 3 to "♝", 2 to "♞").forEach { (code, glyph) ->
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(ChessLight, RoundedCornerShape(8.dp))
                                .clickable {
                                    onMove(promoFrom, promoTo, code)
                                    promoFrom = -1
                                    selected = -1
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                glyph, fontSize = 32.sp, color = PieceBlack,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

// --------------------------------------------------------------- шашки --

/** Цвета по chess.jpg: красные (сторона A) и угольные (сторона B) шашки. */
private val CheckerRedFace = Color(0xFFC6402F)
private val CheckerRedRim = Color(0xFF8F2B1E)
private val CheckerBlackFace = Color(0xFF3C4145)
private val CheckerBlackRim = Color(0xFF1F2225)
private val CheckerLight = Color(0xFFEDD5A8)
private val CheckerDark = Color(0xFF7C4E2E)

/**
 * Шашка: кружок с ободом и внутренним кольцом; на дамке — корона.
 * Коды движка: 1 белая (A, красная), 2 белая дамка, 3 чёрная (B), 4 дамка.
 */
@Composable
private fun CheckerPiece(code: Int, cell: Dp) {
    val red = code == 1 || code == 2
    val face = if (red) CheckerRedFace else CheckerBlackFace
    val rim = if (red) CheckerRedRim else CheckerBlackRim
    Box(
        Modifier.size(cell * 0.84f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(cell * 0.84f)
                .background(face, CircleShape)
                .border(BorderStroke((cell * 0.09f).coerceAtLeast(2.dp), rim), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(cell * 0.56f)
                    .border(BorderStroke((cell * 0.06f).coerceAtLeast(1.5.dp), rim), CircleShape),
            )
            if (code == 2 || code == 4) {
                Text("♛", fontSize = (cell.value * 0.40f).sp, color = rim)
            }
        }
    }
}

/**
 * Шашки. Выбор хода «фигура → конечные клетки → маршрут → подтверждение»:
 * тап по своей фигуре подсвечивает конечные клетки легальных ходов, первый
 * тап по цели рисует маршрут линией, повторный тап по той же клетке
 * подтверждает ход, тап по шашке отменяет выбор. Когда легальный ход
 * завершается на исходной клетке (дамка вернулась в круге боя), тап по
 * шашке неоднозначен — вместо него показываются кнопки
 * «Отменить»/«Подтвердить».
 *
 * В одну клетку может вести несколько разных цепочек взятий (правила
 * русских шашек не требуют максимального взятия — игрок выбирает любой
 * маршрут): все ведущие в клетку маршруты рисуются змейками, как в балде —
 * выбранный ярко-оранжевый, остальные приглушённые; кнопками ◀ ▶ маршрут
 * переключается ([routeIndex] из [routes]), подтверждается выбранный.
 * Игровые клетки — тёмные; размер клетки адаптируется под доступную площадь.
 */
@Composable
fun CheckersBoard(
    snapshot: CheckersSnapshot,
    myTurn: Boolean,
    mySide: Side,
    selected: Int,
    destinations: Set<Int>,
    routes: List<IntArray>,
    chosenDest: Int,
    routeIndex: Int,
    choiceButtons: Boolean,
    onSquareTap: (Int) -> Unit,
    onRouteChange: (Int) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val flipped = mySide == Side.B

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Слоты подсказки и кнопок зарезервированы всегда — доска не прыгает.
        // Резерв увеличен на две строки букв координат (2×16dp) и на рейки цифр.
        val cell = minOf(46.dp, (maxWidth - BoardRail * 2) / 8, (maxHeight - 120.dp) / 8)
            .coerceAtLeast(22.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            FileLabels(cell, flipped)
            Row(verticalAlignment = Alignment.Top) {
                RankLabels(cell, flipped)
                Box(
                    Modifier
                        .background(WoodFrame, RoundedCornerShape(6.dp))
                        .border(3.dp, Color(0xFF3E2514), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                ) {
                    // Маршруты в выбранную клетку — змейки под фигурами: все
                    // ветви видно сразу (площадки взятий — тёмные клетки,
                    // поэтому ветви светлые), выбранная ярче остальных.
                    if (routes.isNotEmpty()) {
                        Canvas(Modifier.matchParentSize()) {
                            val step = cell.toPx()
                            val half = step / 2f
                            fun centers(route: IntArray) = route.map { i ->
                                val r = i shr 3
                                val c = i and 7
                                val rv = if (flipped) 7 - r else r
                                val cv = if (flipped) 7 - c else c
                                Offset(cv * step + half, rv * step + half)
                            }
                            val stroke = (cell.value * 0.08f).coerceAtLeast(2f)
                            // Остальные ветви — приглушённые и потоньше.
                            routes.forEachIndexed { idx, r ->
                                if (idx != routeIndex) {
                                    snakePath(
                                        centers(r),
                                        Color(0xFFF3EDE1).copy(alpha = 0.55f),
                                        stroke * 0.6f,
                                    )
                                }
                            }
                            // Выбранная ветвь — яркая, поверх остальных.
                            routes.getOrNull(routeIndex)?.let {
                                snakePath(centers(it), Color(0xFFE0A030).copy(alpha = 0.85f), stroke)
                            }
                        }
                    }
                    Column {
                        for (rv in 0..7) {
                            val r = if (flipped) 7 - rv else rv
                            Row {
                                for (cv in 0..7) {
                                    val c = if (flipped) 7 - cv else cv
                                    val i = r * 8 + c
                                    val code = snapshot.board[i]
                                    // Игровые клетки движка (r+c нечёт) — тёмные, как на
                                    // настоящей доске: ближний левый угол (a1) тёмный,
                                    // а8 светлая — расстановка шашек совпадает с эталоном.
                                    val isLight = (r + c) % 2 == 0
                                    val chosen = i == chosenDest
                                    val highlight = i == selected || i in destinations
                                    Box(
                                        Modifier
                                            .size(cell)
                                            .background(if (isLight) CheckerLight else CheckerDark)
                                            .then(
                                                if (chosen) Modifier.border(
                                                    3.dp, Color(0xFFF5F5F5), RoundedCornerShape(4.dp),
                                                ) else if (highlight) Modifier.border(
                                                    2.dp, Color(0xFFE0A030), RoundedCornerShape(4.dp),
                                                ) else Modifier,
                                            )
                                            .clickable(enabled = myTurn) { onSquareTap(i) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (code != 0) CheckerPiece(code, cell)
                                    }
                                }
                            }
                        }
                    }
                }
                RankLabels(cell, flipped)
            }
            FileLabels(cell, flipped)
            Box(Modifier.height(20.dp)) {
                // Подсказка видна только игроку, чья очередь бить.
                when {
                    routes.size > 1 -> Text(
                        "Несколько маршрутов взятия — выбирайте ◀ ▶",
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    myTurn && snapshot.mustCapture -> Text(
                        "Обязательное взятие!",
                        color = Color(0xFFE53935),
                    )
                }
            }
            Box(Modifier.height(44.dp)) {
                // Кнопки — когда тап по шашке неоднозначен или когда в одну
                // клетку ведёт несколько цепочек взятий (маршрут ◀ ▶).
                if (choiceButtons) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.TextButton(onClick = onCancel) { Text("Отменить") }
                        if (routes.size > 1) {
                            androidx.compose.material3.TextButton(
                                onClick = { onRouteChange(routeIndex - 1) },
                                enabled = routeIndex > 0,
                            ) { Text("◀") }
                            Text(
                                "${routeIndex + 1}/${routes.size}",
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { onRouteChange(routeIndex + 1) },
                                enabled = routeIndex < routes.size - 1,
                            ) { Text("▶") }
                        }
                        androidx.compose.material3.TextButton(
                            onClick = onCommit,
                            enabled = chosenDest >= 0,
                        ) { Text("Подтвердить") }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------- морской бой --

// Тетрадь в клетку: бумага, синяя/чёрная ручка — сетка и координаты.
// Корабли и метки рисуются кодом (стиль seabattle2): корабли синие
// (свои) и красные (чужие, только убитые), метки всегда контрастны
// цвету корабля под ними — красные на своём поле, синие на чужом.
private val Paper = Color(0xFFFBFBF3)
private val InkBlue = Color(0xFF2F55A4)
private val InkBlack = Color(0xFF30343A)
private val InkRed = Color(0xFFD32F2F)

private const val FIELD_LETTERS = "АБВГДЕЖЗИК"

/**
 * Поле морского боя 10×10 в тетрадной стилистике. [grid] — основное поле с
 * шапкой [gridTitle] (имя владельца, обычным жирным), [onCellTap] — тап по
 * полю соперника (когда моя очередь). Своё поле показывается ниже (портрет)
 * или справа (альбом) с шапкой [myTitle]; размер клетки и раскладка
 * подстраиваются под доступную площадь. [pendingShot] — мой выстрел в
 * полёте: клетка помечается сразу, а после SHOT_RESULT превращается в
 * «мимо»/«попал».
 *
 * Корабли и метки рисуются кодом (см. [FieldBlock]): на своём поле корабли
 * видны всегда, на чужом — только убитые; метки «попал»/«мимо» контрастны
 * цвету корабля под ними.
 */
@Composable
fun SeaBattleBoard(
    grid: IntArray,
    myTurn: Boolean,
    onCellTap: ((Int) -> Unit)?,
    gridTitle: String,
    modifier: Modifier = Modifier,
    myGrid: IntArray? = null,
    myTitle: String? = null,
    pendingShot: Int = -1,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        val landscape = maxWidth > maxHeight
        val hasMine = myGrid != null
        // Основное поле — чужое, если рядом показывается своё (боевой режим);
        // при расстановке myGrid == null и grid — своё собственное поле.
        val mainIsEnemy = hasMine
        // Клетка по формуле «весь контент целиком + запас»: строки координат
        // фиксированной высоты (не клетка), шапки (имя) — одна
        // строка на поле. Запасы: альбом с двумя полями ~74dp, портрет с
        // двумя ~96dp, одиночное (расстановка) ~56dp.
        val cell = if (landscape && hasMine) {
            minOf(30.dp, (maxWidth / 2 - 64.dp) / 10, (maxHeight - 74.dp) / 10)
        } else if (hasMine) {
            minOf(28.dp, (maxWidth - 64.dp) / 10, (maxHeight - 96.dp) / 20)
        } else {
            minOf(32.dp, (maxWidth - 64.dp) / 10, (maxHeight - 56.dp) / 10)
        }.coerceAtLeast(13.dp)

        // Контент центрируется по обеим осям — поля не прижимаются в угол,
        // в альбоме вокруг них симметричные поля «бумаги».
        Column(
            Modifier.align(Alignment.Center).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (landscape && hasMine) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    FieldBlock(
                        gridTitle, grid, cell, InkBlack, myTurn, onCellTap, pendingShot,
                        isEnemyField = mainIsEnemy,
                    )
                    FieldBlock(myTitle, myGrid, cell, InkBlue, myTurn = false, onCellTap = null)
                }
            } else {
                FieldBlock(
                    gridTitle, grid, cell, InkBlack, myTurn, onCellTap, pendingShot,
                    isEnemyField = mainIsEnemy,
                )
                if (hasMine) {
                    Box(Modifier.height(6.dp))
                    FieldBlock(myTitle, myGrid, cell, InkBlue, myTurn = false, onCellTap = null)
                }
            }
        }
    }
}

/**
 * Сетка 10×10 с координатами и шапкой-именем (обычный жирный шрифт).
 * Поверх сетки Canvas'ом рисуются корабли и метки в «рисованном» стиле
 * (см. [drawShipArt], [drawXMark], [drawDotMark]). [isEnemyField] —
 * чужое поле: корабли только убитые (красные), метки синие; своё поле —
 * корабли синие всегда, метки красные.
 */
@Composable
private fun FieldBlock(
    title: String?,
    grid: IntArray,
    cell: Dp,
    pen: Color,
    myTurn: Boolean,
    onCellTap: ((Int) -> Unit)?,
    pendingShot: Int = -1,
    isEnemyField: Boolean = false,
) {
    // Корабли: на своём поле — все (синие, видны всегда), на чужом — только
    // убитые (красные): до уничтожения соперника заменяют лишь метки.
    // Метки контрастны цвету корабля под ними: на чужом поле синие,
    // на своём — красные.
    val ships = remember(grid.contentHashCode(), isEnemyField) { groupShips(grid, isEnemyField) }
    val shipColor = if (isEnemyField) InkRed else InkBlue
    val markColor = if (isEnemyField) InkBlue else InkRed
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (title != null) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = pen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        // Координаты фиксированной высоты/ширины: не съедают клетку и всегда
        // читаются (9sp) — рисунок полей остаётся симметричным и полным.
        Row(Modifier.padding(start = 15.dp)) {
            FIELD_LETTERS.forEach { ch ->
                Text(
                    ch.toString(),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Cursive,
                    color = pen,
                    modifier = Modifier.width(cell).height(14.dp).wrapContentSize(),
                )
            }
        }
        // Клетки (кликабельные) + оверлей с рисунком поверх: Canvas не
        // перехватывает тапы — события уходят клеткам-Box снизу.
        Box {
            Column {
                for (r in 0..9) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (r + 1).toString(),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Cursive,
                            color = pen,
                            modifier = Modifier.width(15.dp).height(cell).wrapContentSize(),
                        )
                        for (c in 0..9) {
                            val i = r * 10 + c
                            val v = grid[i]
                            val targetable = myTurn && v == 0
                            // Мой выстрел в полёте: клетка помечена сразу, после
                            // SHOT_RESULT на ней вырастет «✕»/«•».
                            val inFlight = v == 0 && i == pendingShot
                            Box(
                                Modifier
                                    .size(cell)
                                    .background(Paper)
                                    .border(
                                        when {
                                            targetable || inFlight -> 1.5.dp
                                            else -> 1.dp
                                        },
                                        if (targetable || inFlight) InkRed else pen,
                                    )
                                    .clickable(enabled = targetable) { onCellTap?.invoke(i) },
                            ) {
                                // Контента нет: корабли и метки рисует Canvas-оверлей.
                            }
                        }
                    }
                }
            }
            // Рисованные корабли и метки поверх сетки. Смещение (15dp, 0) —
            // внутри Box нет строки букв, только номера слева.
            Canvas(Modifier.matchParentSize()) {
                val ox = 15.dp.toPx()
                val cp = cell.toPx()
                for (g in ships) {
                    val x = ox + g.c * cp
                    val y = g.r * cp
                    val seed = g.r * 131 + g.c * 17 + g.len * 7 + (if (isEnemyField) 1 else 0)
                    if (g.horiz) {
                        drawShipArt(x, y, g.len * cp, cp, g.len, shipColor, seed)
                    } else {
                        // Вертикаль: рисуем горизонтальную форму и поворачиваем
                        // на 90° вокруг центра корабля.
                        val cxP = x + cp / 2f
                        val cyP = y + g.len * cp / 2f
                        rotate(90f, Offset(cxP, cyP)) {
                            drawShipArt(
                                cxP - g.len * cp / 2f,
                                cyP - cp / 2f,
                                g.len * cp,
                                cp,
                                g.len,
                                shipColor,
                                seed,
                            )
                        }
                    }
                }
                val mw = (cp * 0.10f).coerceAtLeast(1.6f)
                for (i in 0 until 100) {
                    val v = grid[i]
                    val cx = ox + (i % 10) * cp + cp / 2f
                    val cy = (i / 10) * cp + cp / 2f
                    val inFlight = v == 0 && i == pendingShot
                    when {
                        v == 2 || v == 3 -> drawXMark(cx, cy, cp * 0.30f, markColor, mw, i)
                        v == 4 -> drawDotMark(cx, cy, cp * 0.10f, markColor, i)
                        inFlight -> drawXMark(
                            cx, cy, cp * 0.26f, markColor.copy(alpha = 0.45f), mw * 0.9f, i,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------- рисованные элементы морского боя --

/** Прямой корабль: (r,c) — левый верхний угол, [len] — палуб. */
private data class SeaShip(val r: Int, val c: Int, val len: Int, val horiz: Boolean)

/**
 * Собирает клетки поля в корабли. Своё поле — сегменты {1 цел, 2 подбит,
 * 3 убит}; чужое — только убитые (3): до полного уничтожения корабль
 * соперника не виден, остаются лишь метки «попал».
 */
private fun groupShips(grid: IntArray, isEnemy: Boolean): List<SeaShip> {
    fun onShip(v: Int) = if (isEnemy) v == 3 else v == 1 || v == 2 || v == 3
    val seen = BooleanArray(100)
    val out = ArrayList<SeaShip>(10)
    for (i in 0 until 100) {
        if (seen[i] || !onShip(grid[i])) continue
        val comp = ArrayList<Int>(4)
        val stack = ArrayList<Int>()
        stack.add(i)
        seen[i] = true
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(stack.size - 1)
            comp.add(p)
            val r = p / 10
            val c = p % 10
            for ((dr, dc) in listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)) {
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0..9 || nc !in 0..9) continue
                val ni = nr * 10 + nc
                if (seen[ni] || !onShip(grid[ni])) continue
                seen[ni] = true
                stack.add(ni)
            }
        }
        val r0 = comp.minOf { it / 10 }
        val c0 = comp.minOf { it % 10 }
        out.add(SeaShip(r0, c0, comp.size, comp.all { it / 10 == r0 }))
    }
    return out
}

/** Детерминированное «дрожание» [-1..1] по seed — без random, чтобы при
 *  перекомпозиции линии не дрожали на каждом кадре. */
private fun j(seed: Int, i: Int): Float {
    var h = seed * 374761393 + i * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return ((h and 0xFFFF).toFloat() / 32767.5f) - 1f
}

/** Линия «от руки»: дрожащие концы + вторая тонкая штриха рядом. */
private fun DrawScope.handLine(
    x1: Float, y1: Float, x2: Float, y2: Float,
    color: Color, width: Float, seed: Int,
) {
    val amp = width * 0.30f
    val a = Offset(x1 + j(seed, 0) * amp, y1 + j(seed, 1) * amp)
    val b = Offset(x2 + j(seed, 2) * amp, y2 + j(seed, 3) * amp)
    drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)
    val off = Offset(j(seed, 4) * amp, j(seed, 5) * amp)
    drawLine(
        color, a + off, b + off,
        strokeWidth = (width * 0.45f).coerceAtLeast(0.8f), cap = StrokeCap.Round,
    )
}

/** Обводка пути дважды (основная + тонкая со смещением) — «рисовано». */
private fun DrawScope.strokeHand(path: Path, color: Color, width: Float, seed: Int) {
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    translate(j(seed, 31) * width * 0.25f, j(seed, 32) * width * 0.25f) {
        drawPath(
            path, color,
            style = Stroke(width = (width * 0.45f).coerceAtLeast(0.8f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Контур корпуса многопалубного корабля в прямоугольнике (w×h) — дрожащие
 *  линии: нос вправо, корма скруглена (как на ассет-листе). */
private fun hullPath(w: Float, h: Float, seed: Int): Path {
    val amp = h * 0.03f
    val x0 = 0.04f * w
    val bow = 0.985f * w
    val yTop = 0.44f * h
    val yBow = 0.60f * h
    val yBot = 0.88f * h
    return Path().apply {
        moveTo(x0 + j(seed, 0) * amp, yTop + j(seed, 1) * amp)
        lineTo(0.72f * w, yTop + j(seed, 2) * amp)
        quadraticBezierTo(0.93f * w, yTop + j(seed, 3) * amp, bow, yBow + j(seed, 4) * amp)
        quadraticBezierTo(0.66f * w, yBot + j(seed, 5) * amp, 0.18f * w, yBot + j(seed, 6) * amp)
        quadraticBezierTo(
            x0 + j(seed, 7) * amp, yBot + j(seed, 8) * amp,
            x0 + j(seed, 9) * amp, (yTop + yBot) / 2f,
        )
        close()
    }
}

/** Диагональная штриховка области (срезается clipPath вызывающего). */
private fun DrawScope.hatch(
    x1: Float, y1: Float, x2: Float, y2: Float,
    color: Color, width: Float, seed: Int,
) {
    val step = ((y2 - y1) * 1.6f).coerceAtLeast(5f)
    var x = x1 - (y2 - y1)
    var k = 0
    while (x < x2) {
        handLine(x, y2, x + (y2 - y1), y1, color, width, seed + k)
        x += step
        k++
    }
}

/** Открытая «надстройка»: три линии (вверх-вправо-вниз), низ — палуба. */
private fun DrawScope.cabin(
    x1: Float, x2: Float, h: Float, color: Color, width: Float, seed: Int,
) {
    handLine(x1, h * 0.44f, x1, h * 0.24f, color, width, seed)
    handLine(x1, h * 0.24f, x2, h * 0.24f, color, width, seed + 1)
    handLine(x2, h * 0.24f, x2, h * 0.44f, color, width, seed + 2)
}

/**
 * Корабль в «рисованном» стиле (стиль seabattle2): дрожащий контур,
 * штриховка под ватерлинией, надстройки и границы палуб. Рисуется в
 * прямоугольнике (left, top, w, h); вызывающий размещает и поворачивает.
 */
private fun DrawScope.drawShipArt(
    left: Float,
    top: Float,
    w: Float,
    h: Float,
    len: Int,
    color: Color,
    seed: Int,
) {
    translate(left, top) {
        val sw = (h * 0.075f).coerceAtLeast(1.6f)
        val amp = h * 0.025f
        if (len == 1) {
            // Однопалубный: капсула с рубкой и перископом.
            val cx = w / 2f
            val cy = h * 0.62f
            val rx = w * 0.44f
            val ry = h * 0.20f
            val hull = Path()
            for (k in 0 until 12) {
                val a = k * (2f * PI.toFloat() / 12f)
                val px = cx + rx * cos(a) + j(seed, k) * amp
                val py = cy + ry * sin(a) + j(seed, k + 12) * amp
                if (k == 0) hull.moveTo(px, py) else hull.lineTo(px, py)
            }
            hull.close()
            strokeHand(hull, color, sw, seed)
            val sail = Path().apply {
                moveTo(w * 0.44f, h * 0.44f)
                lineTo(w * 0.56f, h * 0.44f)
                lineTo(w * 0.54f, h * 0.30f)
                lineTo(w * 0.46f, h * 0.30f)
                close()
            }
            strokeHand(sail, color, sw * 0.9f, seed + 5)
            handLine(w * 0.50f, h * 0.30f, w * 0.50f, h * 0.16f, color, sw * 0.8f, seed + 7)
            clipPath(hull) {
                hatch(w * 0.08f, h * 0.62f, w * 0.92f, h * 0.80f, color, sw * 0.5f, seed + 9)
            }
        } else {
            val hull = hullPath(w, h, seed)
            strokeHand(hull, color, sw, seed)
            // Палубная линия.
            handLine(w * 0.05f, h * 0.57f, w * 0.80f, h * 0.58f, color, sw * 0.6f, seed + 3)
            // Штриховка под ватерлинией.
            clipPath(hull) {
                hatch(w * 0.06f, h * 0.68f, w * 0.90f, h * 0.86f, color, sw * 0.5f, seed + 9)
            }
            // Надстройки: чем длиннее корабль, тем богаче декор.
            cabin(w * 0.34f, w * 0.56f, h, color, sw, seed + 11)
            if (len >= 3) {
                cabin(w * 0.60f, w * 0.72f, h, color, sw, seed + 13)
                handLine(w * 0.47f, h * 0.24f, w * 0.47f, h * 0.10f, color, sw * 0.8f, seed + 17)
            }
            if (len == 4) {
                handLine(w * 0.76f, h * 0.44f, w * 0.76f, h * 0.34f, color, sw, seed + 19)
                handLine(w * 0.76f, h * 0.34f, w * 0.87f, h * 0.37f, color, sw, seed + 21)
            }
            // Границы палуб (по клеткам).
            for (k in 1 until len) {
                val x = w * k / len
                handLine(x, h * 0.14f, x, h * 0.92f, color, sw * 0.75f, seed + 100 + k)
            }
        }
    }
}

/** «Попал»: крест по клетке — всегда контрастен цвету корабля под ним. */
private fun DrawScope.drawXMark(
    cx: Float, cy: Float, r: Float, color: Color, width: Float, seed: Int,
) {
    handLine(cx - r, cy - r, cx + r, cy + r, color, width, seed)
    handLine(cx + r, cy - r, cx - r, cy + r, color, width, seed + 40)
}

/** «Мимо»: точка. */
private fun DrawScope.drawDotMark(cx: Float, cy: Float, r: Float, color: Color, seed: Int) {
    drawCircle(color, radius = r, center = Offset(cx + j(seed, 2) * r * 0.3f, cy + j(seed, 3) * r * 0.3f))
}

// ------------------------------------------------------- крестики ------

@Composable
fun TttBoard(snapshot: TttSnapshot, myTurn: Boolean, mySide: Side, onCell: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Клетка масштабируется под доступную площадь (портрет/альбом);
        // 128dp — запас на отступы, подпись своей стороны и строку результата.
        val cell = minOf(96.dp, (maxWidth - 64.dp) / 3, (maxHeight - 128.dp) / 3)
            .coerceAtLeast(44.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            // Чья сторона каким знаком играет: без подписи игроки путают
            // крестики и нолики и читают итог с точностью до наоборот
            // (полевой отчёт 20.10: «выиграл ноликами — написало поражение»).
            Text(
                if (mySide == Side.A) "вы — ✕" else "вы — ○",
                fontSize = 13.sp,
            )
            for (r in 0..2) {
                Row {
                    for (c in 0..2) {
                        val i = r * 3 + c
                        val v = snapshot.cells[i]
                        Box(
                            Modifier
                                .size(cell)
                                .padding(2.dp)
                                .background(
                                    when (v) {
                                        1 -> Color(0xFFC62828) // крестик — красный
                                        2 -> Color(0xFF2E7D32) // нолик — зелёный
                                        else -> Color(0xFF37474F)
                                    },
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable(enabled = myTurn && v == 0) { onCell(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                when (v) { 1 -> "✕"; 2 -> "○"; else -> "" },
                                fontSize = (cell.value * 0.45f).sp,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            val r = snapshot.result
            if (r != null) {
                Spacer(Modifier.height(12.dp))
                // Всегда глазами [mySide], как и статусная строка и карточка
                // партии: жёстко Side.A инвертировало вердикт для второй стороны.
                Text(resultText(r, mySide), fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -------------------------------------------------------------- балда --

/** Акцент балды: спор и подсветка путей (оранжевый, как ручка на полях). */
private val BaldaAccent = Color(0xFFE0A030)

/** Змейка пути через центры клеток (балда — слова, шашки — маршруты взятий). */
private fun DrawScope.snakePath(pts: List<Offset>, color: Color, width: Float) {
    if (pts.isEmpty()) return
    for (k in 0 until pts.size - 1) {
        drawLine(color, pts[k], pts[k + 1], strokeWidth = width, cap = StrokeCap.Round)
    }
    for (p in pts) drawCircle(color, radius = width * 1.3f, center = p)
}

/**
 * Поле балды 5×5 в тетрадной стилистике: буквы от руки, клетка тапабельна,
 * когда пуста (или, в ручном режиме, — когда занята и входит в путь).
 * [onCellLongTap] — долгий тап по клетке (в ручном режиме — убрать новую
 * букву с поля). Подсветки: путь последнего принятого слова (бледно-оранжевый; при вводе
 * гасится до серого — [dimLastPath], чтобы не путать со своим путём), спорное
 * слово (оранжевый контур клетки + линия), строящийся вручную путь
 * (синий). Выбранная, но не подтверждённая буква лежит полутоном в пустой
 * клетке ([ghostCell]/[ghostLetter]). Линии рисуются Canvas'ом поверх
 * сетки — тапы он не перехватывает.
 */
@Composable
fun BaldaBoard(
    snapshot: BaldaSnapshot,
    myTurn: Boolean,
    selectedCell: Int,
    manualMode: Boolean,
    manualPath: IntArray,
    onCellTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Полутонная буква в пустой клетке — выбранная, но ещё не подтверждённая.
    ghostCell: Int = -1,
    ghostLetter: Int = 0,
    // При вводе пути змейка последнего слова гасится — не путает с синим.
    dimLastPath: Boolean = false,
    onCellLongTap: ((Int) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth().background(Paper)) {
        // Без координатной обвязки: место — только клеткам (5×5).
        val cell = minOf(
            84.dp,
            (maxWidth - 16.dp) / 5,
            (maxHeight - 8.dp) / 5,
        ).coerceAtLeast(34.dp)
        val disputeCell = snapshot.dispute?.cell ?: -1
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box {
                Column {
                    for (r in 0..4) {
                        Row {
                            for (c in 0..4) {
                                val i = r * 5 + c
                                val v = snapshot.cells[i]
                                val inManual = manualPath.contains(i)
                                val tappable = myTurn &&
                                    if (manualMode) (v != 0 || i == selectedCell) else v == 0
                                Box(
                                    Modifier
                                        .size(cell)
                                        .background(Paper)
                                        .border(
                                            when {
                                                i == disputeCell -> 2.dp
                                                i == selectedCell -> 2.dp
                                                inManual -> 1.5.dp
                                                else -> 1.dp
                                            },
                                            when {
                                                i == disputeCell -> BaldaAccent
                                                i == selectedCell -> InkRed
                                                inManual -> InkBlue
                                                else -> InkBlack
                                            },
                                            RoundedCornerShape(3.dp),
                                        )
                                        .then(
                                            if (onCellLongTap != null) {
                                                Modifier.combinedClickable(
                                                    enabled = tappable,
                                                    onLongClick = { onCellLongTap(i) },
                                                    onClick = { onCellTap(i) },
                                                )
                                            } else {
                                                Modifier.clickable(enabled = tappable) { onCellTap(i) }
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (v in 1..33) {
                                        Text(
                                            BaldaEngine.codeLetter(v).uppercase(),
                                            fontFamily = FontFamily.Cursive,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (cell.value * 0.52f).sp,
                                            color = InkBlack,
                                        )
                                    } else if (i == ghostCell && ghostLetter in 1..33) {
                                        // Полутон: буква лежит до подтверждения хода.
                                        Text(
                                            BaldaEngine.codeLetter(ghostLetter).uppercase(),
                                            fontFamily = FontFamily.Cursive,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (cell.value * 0.52f).sp,
                                            color = InkBlack.copy(alpha = 0.32f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Пути поверх букв: Canvas без pointerInput пропускает тапы
                // в клетки-Box снизу (как в FieldBlock морского боя).
                Canvas(Modifier.matchParentSize()) {
                    val cp = cell.toPx()
                    fun centers(path: IntArray) = path.map {
                        Offset((it % 5) * cp + cp / 2f, (it / 5) * cp + cp / 2f)
                    }
                    val w = (cp * 0.07f).coerceAtLeast(2f)
                    // Змейка последнего слова при вводе гасится до едва видной
                    // серой — игрок не путает её со своим синим путём.
                    val lastColor = if (dimLastPath) {
                        InkBlack.copy(alpha = 0.10f)
                    } else {
                        BaldaAccent.copy(alpha = 0.30f)
                    }
                    snakePath(centers(snapshot.lastPath), lastColor, w)
                    snapshot.dispute?.let {
                        snakePath(centers(it.path), BaldaAccent.copy(alpha = 0.75f), w)
                    }
                    if (manualPath.size >= 2) {
                        snakePath(centers(manualPath), InkBlue.copy(alpha = 0.70f), w)
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------- дурак --

val DURAK_CARD_DRAWABLES = intArrayOf(
    R.drawable.card_00, R.drawable.card_01, R.drawable.card_02, R.drawable.card_03,
    R.drawable.card_04, R.drawable.card_05, R.drawable.card_06, R.drawable.card_07,
    R.drawable.card_08, R.drawable.card_09, R.drawable.card_10, R.drawable.card_11,
    R.drawable.card_12, R.drawable.card_13, R.drawable.card_14, R.drawable.card_15,
    R.drawable.card_16, R.drawable.card_17, R.drawable.card_18, R.drawable.card_19,
    R.drawable.card_20, R.drawable.card_21, R.drawable.card_22, R.drawable.card_23,
    R.drawable.card_24, R.drawable.card_25, R.drawable.card_26, R.drawable.card_27,
    R.drawable.card_28, R.drawable.card_29, R.drawable.card_30, R.drawable.card_31,
    R.drawable.card_32, R.drawable.card_33, R.drawable.card_34, R.drawable.card_35,
)

/**
 * Карточка дурака: классическая колода Windows / SweetGames (cards.dll).
 * [faceDown] — рубашка (атласная косая клетка), [highlighted] — золотая
 * рамка (карта бьёт или подкидывается).
 */
@Composable
fun PlayingCard(
    code: Int,
    width: Dp,
    modifier: Modifier = Modifier,
    faceDown: Boolean = false,
    highlighted: Boolean = false,
) {
    val height = width * (96f / 71f)
    val resId = if (faceDown) {
        R.drawable.card_back
    } else if (code in DURAK_CARD_DRAWABLES.indices) {
        DURAK_CARD_DRAWABLES[code]
    } else {
        null
    }

    Box(
        modifier
            .size(width, height)
            .then(
                if (highlighted) {
                    Modifier.border(2.dp, Color(0xFFFFD700), RoundedCornerShape(3.dp))
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (resId != null) {
            Image(
                painter = painterResource(resId),
                contentDescription = if (faceDown) "Рубашка" else DurakEngine.cardName(code),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}
