package com.meshgames.game

/**
 * Общие типы игрового ядра. Модуль не зависит от Android — весь код здесь
 * тестируется на JVM (./gradlew :app:testDebugUnitTest).
 */

/** Идентификаторы игр, как они передаются по сети (1 байт в INVITE). */
@kotlinx.serialization.Serializable
enum class GameType(val id: Int, val title: String, val icon: String) {
    TIC_TAC_TOE(1, "Крестики-нолики", "✕ ○"),
    CHECKERS(2, "Шашки", "⬤"),
    CHESS(3, "Шахматы", "♞"),
    SEA_BATTLE(4, "Морской бой", "⛴"),
    BALDA(5, "Балда", "Б"),
    DURAK(6, "Дурак", "♦");

    companion object {
        fun fromId(id: Int): GameType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Стороны партии.
 * A — инициатор приглашения (всегда ходит/стреляет первым): крестики, белые,
 * в морском бое — первый стрелок. B — пригласивший оппонент.
 */
@kotlinx.serialization.Serializable
enum class Side {
    A, B;

    val other: Side get() = if (this == A) B else A
}

/** Как завершилась партия. */
@kotlinx.serialization.Serializable
enum class ResultReason {
    /** Мат / уничтожены все корабли / победа по правилам. */
    CHECKMATE,
    ALL_SHIPS_SUNK,
    /** Сдача. */
    RESIGN,
    /** Пат. */
    STALEMATE,
    /** Ничья по соглашению. */
    DRAW_AGREEMENT,
    /** Недостаточно материала для мата. */
    INSUFFICIENT_MATERIAL,
    /** Правило 50 ходов. */
    FIFTY_MOVES,
    /** Троекратное повторение. */
    THREEFOLD,
    /** Нет легальных ходов (шашки). */
    NO_MOVES,
    /** Соперник не отвечает / вышел. */
    OPPONENT_LEFT,
    /** Состояния разошлись (не совпал дайджест). */
    SYNC_CONFLICT,
    /** Поле заполнено: очки больше у одной из сторон, либо ничья (балда). */
    BOARD_FILLED,
    /** Три пропуска подряд одним игроком — его поражение (балда). */
    THREE_PASSES,
    /** У проигравшего остались карты — он «дурак» (дурак). */
    LAST_WITH_CARDS,
    /** Оба игрока вышли из игры одновременно — ничья (дурак). */
    BOTH_OUT,
}

/** Итог партии: winner == null -> ничья. */
@kotlinx.serialization.Serializable
data class GameResult(val winner: Side?, val reason: ResultReason)

/** Ввод хода с UI. Движок валидирует его ДО отправки по сети. */
sealed interface MoveInput {
    /** Крестики-нолики: клетка 0..8. */
    data class Cell(val index: Int) : MoveInput

    /**
     * Шахматы: from/to (0..63, 0 = a8), promotion: 0 — не нужна,
     * 1 — ферзь, 2 — ладья, 3 — слон, 4 — конь.
     */
    data class FromTo(val from: Int, val to: Int, val promotion: Int = 0) : MoveInput

    /**
     * Шашки: squares[0] — откуда, далее — последовательные площадки
     * (для простого хода ровно 2 элемента, для взятия — цепочка).
     */
    data class Chain(val squares: IntArray) : MoveInput

    /** Морской бой: выстрел, клетка 0..99. */
    data class Shoot(val coord: Int) : MoveInput

    /**
     * Балда: буква в пустую клетку [cell] + путь змейки [path] — индексы
     * клеток в порядке чтения слова (содержит [cell], без повторов, соседи
     * по горизонтали/вертикали). [letter] — код буквы 1..33 (А=1 … Я=33).
     */
    data class LetterAdd(val cell: Int, val letter: Int, val path: IntArray) : MoveInput

    /** Балда: пропустить ход. */
    data class Skip(val reason: Int = 0) : MoveInput

    /**
     * Дурак: карта = `rank*4 + suit` (0..35; rank 0 = 6 … 8 = Т,
     * suit 0 = ♦, 1 = ♣, 2 = ♥, 3 = ♠).
     */
    data class DurakAttack(val card: Int) : MoveInput

    /** Дурак: побить карту атаки (защитник). */
    data class DurakBeat(val card: Int) : MoveInput

    /** Дурак: забрать атаку (защитник, если бить нечем). */
    data object DurakTake : MoveInput

    /** Дурак: «хватит» — бито (атакующий, после забитой атаки). */
    data object DurakEnough : MoveInput
}

/** Снимок состояния для UI (sealed, по типу игры). */
sealed interface BoardSnapshot {
    val turn: Side?
    val finished: Boolean
    val result: GameResult?
    val log: List<String>
}

/** 0 — пусто, 1 — X (сторона A), 2 — O (сторона B). */
data class TttSnapshot(
    val cells: IntArray,
    override val turn: Side?,
    override val finished: Boolean,
    override val result: GameResult?,
    override val log: List<String>,
) : BoardSnapshot

/**
 * Шахматы. board[0] = a8 ... board[63] = h1.
 * Коды: 0 пусто; положительные — белые (1 пешка,2 конь,3 слон,4 ладья,5 ферзь,6 король),
 * отрицательные — чёрные. mySide == A -> я белые (играю внизу экрана).
 */
data class ChessSnapshot(
    val board: IntArray,
    override val turn: Side?,
    val checked: Side?,
    val castling: Int,          // биты: 1 белые рокировки короткая, 2 длинная, 4 чёрные короткая, 8 длинная
    val epSquare: Int,          // -1 нет
    val halfmove: Int,
    val fullmove: Int,
    override val finished: Boolean,
    override val result: GameResult?,
    override val log: List<String>,
) : BoardSnapshot

/**
 * Шашки. board[0] = a8 ... board[63] = h1.
 * 0 пусто; 1 белые шашки, 2 белые дамки; 3 чёрные шашки, 4 чёрные дамки.
 * Сторона A = белые.
 */
data class CheckersSnapshot(
    val board: IntArray,
    override val turn: Side?,
    /** Обязательно ли бить (есть хоть одно взятие у стороны, чья очередь). */
    val mustCapture: Boolean,
    /** -1, если выбора нет; иначе выбранная фигура. */
    val chainFrom: Int,
    /** Конечные клетки легальных ходов выбранной фигуры (финал цепочки взятий или простого хода). */
    val destinations: IntArray,
    override val finished: Boolean,
    override val result: GameResult?,
    override val log: List<String>,
) : BoardSnapshot

/** Фазы партии в морском бое (внутри движка). */
enum class SeaPhase { PLACEMENT, BATTLE, FINISHED }

/**
 * Морской бой. Коды клеток (100 клеток, 0 — верх лево):
 * 0 — вода/неизвестно, 1 — корабль (целый), 2 — подбит, 3 — убит, 4 — промах.
 * myGrid — своё поле (видно всё), oppGrid — поле соперника (0 — неизвестно).
 */
data class SeaBattleSnapshot(
    val myGrid: IntArray,
    val oppGrid: IntArray,
    val phase: SeaPhase,
    override val turn: Side?,
    val pendingShot: Int,       // -1 — выстрел не отправлен
    val myShipsLeft: Int,
    val oppShipsLeft: Int,
    override val finished: Boolean,
    override val result: GameResult?,
    override val log: List<String>,
) : BoardSnapshot

/**
 * Спорное слово в балде: положено на поле, но не найдено в чьём-то словаре.
 * [awaitingMyVerdict] — ждёт МОЕГО решения (диалог «Принять/Отказать»).
 * У стороны A (инициатор хода) вердикт всегда от соперника, поэтому флаг
 * у неё false — она ждёт ответа, а не принимает решение.
 */
data class BaldaDispute(
    val cell: Int,
    val word: String,
    val path: IntArray,
    val awaitingMyVerdict: Boolean,
)

/**
 * Балда 5×5: клетки — коды букв 0 (пусто) .. 33 (Я); стартовое слово
 * лежит в средней строке. lastPath — путь последнего принятого слова
 * (подсветка в UI, в digest не входит, но сохраняется в state).
 * startWord/words — для списка «кто что сыграл»: стартовое слово
 * (его нельзя повторить) и введённые слова в порядке игры.
 */
data class BaldaSnapshot(
    val cells: IntArray,
    val myScore: Int,
    val peerScore: Int,
    val lastPath: IntArray,
    val dispute: BaldaDispute?,
    val startWord: String,
    val words: List<BaldaWordEntry>,
    override val turn: Side?,
    override val finished: Boolean,
    override val result: GameResult?,
    override val log: List<String>,
) : BoardSnapshot

/** Введённое в балде слово: сторона и слово (для списка ввода и счёта).
 *  Запись `passed=true` — пропуск хода: пустое слово, +0 очков (в таблице
 *  счёта показывается красной строкой «Пропуск +0»). */
data class BaldaWordEntry(val side: Side, val word: String, val passed: Boolean = false)

/** Фазы дурака: атака/подкидывание (ждём карту или «хватит»), защита (бьём или берём), конец. */
enum class DurakPhase { ATTACK, DEFEND, FINISHED }

/**
 * Дурак (36 карт, подкидной): стол захода накапливает пары «атака/бой»
 * до 6, подкидывание — по номиналу любой карты захода (и атаки, и боя).
 * Скрытая информация — только уровень UI: чужая руна не рисуется, но
 * движки обеих сторон идентичны — раздача детерминирована от sessionId
 * (прецедент — стартовое слово балды).
 * Карты: код `rank*4 + suit` (rank 0 = 6 … 8 = Т, suit 0 = ♦, 1 = ♣,
 * 2 = ♥, 3 = ♠).
 */
data class DurakSnapshot(
    val myHand: IntArray,          // мои карты, отсортированы
    val peerHandCount: Int,        // чужая руна — только число (рубашками)
    val deckCount: Int,
    val trump: Int,                // код карты козыря (нижняя карта колоды)
    /** Карты атаки захода по порядку (лежат на столе до конца захода). */
    val attacks: IntArray,
    /** Бой параллельно [attacks]: -1 — карта ещё не забита. */
    val tableBeats: IntArray,
    val attacker: Side,
    val phase: DurakPhase,
    /** Карты моей руки, бьющие текущую атаку (подсветка при защите). */
    val legalBeats: IntArray,
    /** Карты моей руки, которые можно подкиднуть (номинал на столе, лимиты). */
    val legalThrows: IntArray,
    override val turn: Side?,
    override val finished: Boolean,
    override val result: GameResult?,
    override val log: List<String>,
    val taking: Boolean = false,
) : BoardSnapshot

/**
 * Игровой движок. Каждая сторона держит СВОЁ состояние и валидирует:
 *  - локальный ход — перед отправкой ([localMove] возвращает payload либо null);
 *  - ход соперника — при приёме ([remoteMove] возвращает false -> ход отклонён).
 * Состояние полностью сериализуется ([encodeState]/[GameEngines.restore]) для
 * продолжения прерванной игры.
 */
interface GameEngine {
    val type: GameType
    /** Моя сторона в этой партии. */
    val mySide: Side
    /** Чей сейчас ход (null — партия завершена). */
    val turn: Side?
    val finished: Boolean
    val result: GameResult?
    val log: List<String>

    fun snapshot(): BoardSnapshot

    /** Подсветка легальных клеток-целей из клетки [from] (для UI). */
    fun legalMoves(from: Int): Set<Int> = emptySet()

    /**
     * Локальный ход. Валидирует и применяет, возвращает payload для MOVE
     * (или SHOT при MoveInput.Shoot), либо null, если ход нелегален.
     */
    fun localMove(input: MoveInput): ByteArray?

    /**
     * Автоход тестового бота (настройка «Тестовый бот»): первый легальный ход,
     * подобранный движком по правилам — без какой-либо сложности, только чтобы
     * партия жила своей жизнью на радиоканале. [null] — ходить нечего: чужой
     * ход, партия завершена, фаза расстановки либо спор (балда).
     *
     * Метод не меняет состояние движка: он только подбирает [MoveInput],
     * применение идёт штатным [localMove] через сессионный слой.
     */
    fun botMove(): MoveInput? = null

    /** Ход соперника из сети. true — применён, false — отклонён (нелегален). */
    fun remoteMove(payload: ByteArray): Boolean

    /** Канонический дайджест публичной части состояния (u16, для проверки расхождений). */
    fun digest(): Int

    /**
     * true, если состояние «в покое» (нет операций в полёте, ожидающих ответа
     * соперника) — только тогда дайджесты обеих сторон сопоставимы.
     */
    val quiescent: Boolean get() = true

    /** Кодированное локальное состояние (для сохранения на диск). */
    fun encodeState(): ByteArray

    // --- Морской бой (для остальных игр — no-op) ---

    /** Переставить корабли в фазе расстановки. */
    fun rerollShips(): Boolean = false

    /** Подтвердить расстановку (переход к битве, когда подтвердят оба). */
    fun confirmShips(): Boolean = false

    /** Начать битву (вызывается менеджером, когда подтвердили оба). */
    fun beginBattle(): Boolean = false

    /** Мой выстрел: payload для SHOT, либо null (нет хода/клетка уже обстреляна). */
    fun localShot(coord: Int): ByteArray? = null

    /** Выстрел по мне: применить и вернуть payload для SHOT_RESULT (null — некорректен). */
    fun remoteShot(payload: ByteArray): ByteArray? = null

    /** Результат моего выстрела от соперника: применить. */
    fun applyShotResult(payload: ByteArray): Boolean = false

    /**
     * Отказаться от «зависшей» операции в полёте (морской бой: выстрел,
     * чей результат потерян безвозвратно). Сброс не входит в [digest] —
     * состояния сторон от него не расходятся; движок возвращается в покой
     * ([quiescent]) и синхронизация разблокирована. Вызывается таймаутом * сессионного слоя; true — состояние было изменено.
     */
    fun abandonPendingShot(): Boolean = false

    // --- Балда (для остальных игр — no-op) ---

    /** Есть ли спорное слово, ждущее МОЕГО вердикта (показать диалог). */
    val pendingVerdict: Boolean get() = false

    /**
     * Мой вердикт спорному слову соперника ([accept] = true — принять).
     * Возвращает payload для MOVE (вердикт шлётся кадром MOVE вне очереди
     * хода — как и ответы SHOT_RESULT в морском бое), либо null, если
     * спора, ожидающего моего решения, нет.
     */
    fun localVerdict(accept: Boolean): ByteArray? = null
}
