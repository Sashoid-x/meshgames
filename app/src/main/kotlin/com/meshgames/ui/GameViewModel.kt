package com.meshgames.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.meshgames.app.AppGraph
import com.meshgames.game.MoveInput
import com.meshgames.game.session.SessionStatus
import com.meshgames.game.session.SessionView
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel игрового экрана: единая точка намерений (intent) одной сессии.
 *
 * Экран [GameScreen] только рисует состояние и зовёт методы отсюда; знание
 * о [com.meshgames.game.session.SessionManager] и AppGraph живёт здесь.
 * Это задел под новые игры: пятая игра подключается без правок вьюмодели —
 * новый движок + доска в GameBoard + описало в GAME_BLURBS.
 *
 * Каждая сессия — отдельный экземпляр (ключ `game-<sessionId>` в
 * ViewModelStore активити): подписки и состояние сессий не пересекаются.
 */
class GameViewModel(
    private val graph: AppGraph,
    val sessionId: Int,
) : ViewModel() {

    /** Состояние своей сессии (null — сессия удалена с другого экрана). */
    val view: StateFlow<SessionView?> = graph.manager.sessions_
        .map { list -> list.firstOrNull { it.session.sessionId == sessionId } }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // Начальное значение синхронно — экран не мигает «не найдена».
            graph.manager.sessions_.value.firstOrNull { it.session.sessionId == sessionId },
        )

    /** Оценка латентности канала с пиру (мс; null — ещё не измерена). */
    val rttMs: StateFlow<Long?> = graph.manager.rttFlow(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** (sessionId, запись журнала) — партия откатена после сверки ходов. */
    val sessionRecovered get() = graph.events.sessionRecovered

    init {
        // Зависшее приглашение: ответ соперника потерялся, расписание
        // исчерпано ещё до открытия экрана — переотправляем (идемпотентно).
        graph.manager.retryInviteIfStuck(sessionId)
    }

    // ------------------------------------------------- интенты сессии ---

    fun respondInvite(accept: Boolean) = graph.manager.respondInvite(sessionId, accept)
    fun submitMove(input: MoveInput): Boolean = graph.manager.submitMove(sessionId, input)
    fun legalMoves(from: Int): Set<Int> = graph.manager.legalMoves(sessionId, from)
    fun reroll(): Boolean = graph.manager.reroll(sessionId)
    fun ready(): Boolean = graph.manager.ready(sessionId)
    fun offerDraw() = graph.manager.offerDraw(sessionId)
    fun respondDraw(accept: Boolean) = graph.manager.respondDraw(sessionId, accept)
    fun resign() = graph.manager.resign(sessionId)
    fun closeGame() = graph.manager.closeGame(sessionId)
    fun resumeGame() = graph.manager.resumeGame(sessionId)
    fun dismiss() = graph.manager.dismiss(sessionId)

    /**
     * «Ещё раз» после завершения партии: новое приглашение тому же
     * сопернику той же игры — свежая сессия (старая партия остаётся в
     * списке). Возвращает id новой сессии для навигации (null — нечего
     * переигрывать).
     */
    fun playAgain(): Int? {
        val sess = view.value?.session ?: return null
        if (sess.status != SessionStatus.FINISHED) return null
        return graph.manager.invite(sess.peerNode, sess.gameId, sess.difficulty)
    }

    // --- шашки: выбор хода «фигура → конечные клетки → маршрут» ---

    fun checkersSelect(square: Int): Boolean = graph.manager.checkersSelect(sessionId, square)
    fun checkersCancel() = graph.manager.checkersCancel(sessionId)
    fun checkersCommit(square: Int, routeIndex: Int = 0): Boolean =
        graph.manager.checkersCommit(sessionId, square, routeIndex)

    /** Все легальные цепочки, ведущие в [square] (несколько — игрок выбирает маршрут). */
    fun checkersRoutes(square: Int): List<IntArray> = graph.manager.checkersRoutes(sessionId, square)
    fun checkersSelection(): Pair<Int, Set<Int>>? = graph.manager.checkersSelection(sessionId)

    // --- балда: спорное слово ---

    /** Мой вердикт спорному слову соперника (кадром MOVE). */
    fun submitVerdict(accept: Boolean): Boolean = graph.manager.submitVerdict(sessionId, accept)

    /** Балда: слово найдено в словаре движка (предупреждение перед спором). */
    fun baldaWordKnown(word: String): Boolean =
        graph.manager.wordInDictionary(sessionId, word)

    /**
     * Принудительная синхронизация (кнопка ⟳, все игры): SYNC_REQ с
     * наивысшим приоритетом — в обход очереди обычных отправок.
     */
    fun forceSync(): Boolean = graph.manager.requestSync(sessionId)

    // --------------------------------------------------- справочники ---

    fun playerName(): String = graph.playerName()
    fun peerName(peerNode: Int): String = graph.peerName(peerNode)

    companion object {
        /** Фабрика для [androidx.lifecycle.viewmodel.compose.viewModel]. */
        fun factory(graph: AppGraph, sessionId: Int) = viewModelFactory {
            initializer { GameViewModel(graph, sessionId) }
        }
    }
}
