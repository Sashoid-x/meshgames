package com.meshgames.game.session

import com.meshgames.game.GameType
import com.meshgames.game.MoveInput
import com.meshgames.game.Side
import com.meshgames.game.engine.EmptyWordChecker
import com.meshgames.game.engine.GameEngines
import com.meshgames.game.engine.WordChecker
import com.meshgames.game.engine.BaldaEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тестовый бот ([BotAgent]) поверх [PairNetwork]: принимает приглашения и
 * запросы, сам не инициирует игры, в начатую партию ходит по правилам —
 * партия доигрывается движками до итога, дайджесты обеих сторон совпадают.
 */
class BotAgentTest {

    private class FakeWords(words: Collection<String>) : WordChecker {
        private val set = words.map { BaldaEngine.normalize(it) }.toSet()
        override fun contains(word: String) = word in set
        override fun startWords() = listOf("балда")
    }

    @Before
    fun installDict() {
        GameEngines.wordChecker = FakeWords(listOf("балда", "бы"))
    }

    @After
    fun restoreDict() {
        GameEngines.wordChecker = EmptyWordChecker
    }

    /**
     * Один «тик» приложения: тик менеджера (переотправки), шаг бота,
     * доставка всего накопленного.
     */
    private fun tick(net: PairNetwork, vararg bots: BotAgent, times: Int = 1) {
        repeat(times) {
            net.advanceTime(1000)
            for (b in bots) b.step()
            net.pump()
        }
    }

    /** Приглашение от A в игру [game]; возвращает sid. */
    private fun invite(net: PairNetwork, game: GameType): Int {
        val sid = net.managerA.invite(net.nodeB, game)
        net.pump()
        return sid
    }

    // ------------------------------------------------------- приглашение

    @Test
    fun botAcceptsEveryInvite() {
        for (game in GameType.entries) {
            val net = PairNetwork()
            net.start()
            val bot = BotAgent(net.managerB) { true }
            val sid = invite(net, game)
            assertEquals(SessionStatus.INVITING, net.managerA.session(sid)!!.status)
            tick(net, bot)

            val s = net.managerB.session(sid)!!
            // Морской бой после согласия уходит в расстановку, остальное — в бой.
            val expected = if (game == GameType.SEA_BATTLE) SessionStatus.PLACEMENT else SessionStatus.ACTIVE
            assertEquals("бот не принял $game", expected, s.status)
            // Инициатор видит то же: в морском бое он тоже расставляет корабли.
            assertEquals("инициатор не увидел согласие в $game", expected, net.managerA.session(sid)!!.status)
        }
    }

    @Test
    fun disabledBotStaysSilent() {
        val net = PairNetwork()
        net.start()
        val bot = BotAgent(net.managerB) { false }
        val sid = invite(net, GameType.TIC_TAC_TOE)
        tick(net, bot, times = 5)

        assertEquals("выключенный бот ответил", SessionStatus.INVITED, net.managerB.session(sid)!!.status)
        assertEquals(SessionStatus.INVITING, net.managerA.session(sid)!!.status)
        assertTrue(net.eventsB.started.isEmpty())
    }

    @Test
    fun botNeverStartsGamesItself() {
        val net = PairNetwork()
        net.start()
        val bot = BotAgent(net.managerB) { true }
        tick(net, bot, times = 5)

        assertTrue("бот сам завёл сессию", net.managerB.sessions_.value.isEmpty())
        assertTrue(net.eventsB.invites.isEmpty())
        assertTrue(net.eventsA.invites.isEmpty())
    }

    // ------------------------------------------------------- морской бой

    @Test
    fun botConfirmsPlacementAndPlaysSeaBattleToTheEnd() {
        val net = PairNetwork()
        net.start()
        val botA = BotAgent(net.managerA) { true }
        val botB = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.SEA_BATTLE)

        // Бот подтверждает расстановку, когда дошла его очередь.
        tick(net, botA, botB, times = 3)
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // Партия доигрывается автоматически: выстрелы, попадания, итог.
        tick(net, botA, botB, times = 220)
        val s = net.managerA.session(sid)!!
        assertEquals("партия не завершилась", SessionStatus.FINISHED, s.status)
        assertNotNull(s.result)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertFalse(net.eventsA.over.isEmpty())
    }

    // ------------------------------------------------- крестики-нолики

    @Test
    fun botPlaysTicTacToeAgainstBotToFinish() {
        val net = PairNetwork()
        net.start()
        val botA = BotAgent(net.managerA) { true }
        val botB = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.TIC_TAC_TOE)

        tick(net, botA, botB, times = 15)
        val s = net.managerA.session(sid)!!
        assertEquals("партия не завершилась", SessionStatus.FINISHED, s.status)
        assertNotNull(s.result)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertTrue(net.eventsB.over.isNotEmpty())
    }

    @Test
    fun botFinishesGameStartedByHumanMoves() {
        val net = PairNetwork()
        net.start()
        val bot = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.TIC_TAC_TOE)
        tick(net, bot)
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // Человек играет своим ходом — бот отвечает автоматически.
        var guard = 0
        while (net.managerA.session(sid)!!.status == SessionStatus.ACTIVE && guard++ < 12) {
            val snap = net.managerA.snapshot(sid)
            val myTurn = snap?.turn == Side.A
            if (myTurn) {
                val cell = snap.let { s ->
                    (0..8).firstOrNull { (s as com.meshgames.game.TttSnapshot).cells[it] == 0 }
                }
                assertNotNull(cell)
                assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(cell!!)))
            }
            tick(net, bot)
        }
        assertEquals(SessionStatus.FINISHED, net.managerA.session(sid)!!.status)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
    }

    /**
     * Полевой сценарий 20.10: инициатор приглашения получает сторону B (нолики),
     * бот — крестики и ходит первым. Все клетки и итог обязаны совпасть с
     * абсолютной разметкой поля (1 = ✕ сторона A, 2 = ○ сторона B) и с
     * [GameResult.winner] — иначе игрок читает вердикт с точностью до наоборот.
     */
    @Test
    fun humanIsNoughtsAndResultMatchesAbsoluteBoard() {
        val net = PairNetwork()
        net.start()
        // Инициатор — сторона B; у бота остаются крестики (ходит первым).
        net.managerA = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeA), net.eventsA, { net.clock },
            sideDecider = { Side.B },
        )
        val bot = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.TIC_TAC_TOE)
        tick(net, bot)
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // Бот (сторона A) уже сходил: на поле один крестик, очередь — моя.
        tick(net, bot)
        val opening = net.managerA.snapshot(sid) as com.meshgames.game.TttSnapshot
        assertEquals("крестик бота не на месте", 1, opening.cells.count { it == 1 })
        assertEquals("у игрока не нолики", 0, opening.cells.count { it == 2 })
        assertEquals(Side.B, opening.turn)

        // Играю ноликами до конца партии — бот отвечает автоматически.
        var guard = 0
        var myMoves = 0
        while (net.managerA.session(sid)!!.status == SessionStatus.ACTIVE && guard++ < 20) {
            val snap = net.managerA.snapshot(sid) as com.meshgames.game.TttSnapshot
            if (snap.turn == Side.B) {
                val cell = (0..8).firstOrNull { snap.cells[it] == 0 }
                assertNotNull(cell)
                assertTrue(net.managerA.submitMove(sid, MoveInput.Cell(cell!!)))
                myMoves++
                val after = net.managerA.snapshot(sid) as com.meshgames.game.TttSnapshot
                // Мой ход лёг ноликом (2) — и только мои клетки стали ноликами.
                assertEquals("мой ход не нолик", 2, after.cells[cell])
                assertEquals("не только мои ходы стали ноликами", myMoves, after.cells.count { it == 2 })
            }
            tick(net, bot)
        }
        assertTrue("игрок так и не сходил", myMoves > 0)

        val s = net.managerA.session(sid)!!
        assertEquals("партия не завершилась", SessionStatus.FINISHED, s.status)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        val snap = net.managerA.snapshot(sid) as com.meshgames.game.TttSnapshot
        val result = s.result
        assertNotNull("нет итога", result)
        // Итог согласован с полем: победитель — сторона, чья линия на доске.
        val winnerMark = when (result!!.winner) {
            Side.A -> 1
            Side.B -> 2
            null -> 0
        }
        if (winnerMark != 0) {
            assertTrue("итог $result не подтверждается линией на поле", tttLine(snap.cells.toList(), winnerMark))
        } else {
            assertTrue("ничья объявлена при незакрытом поле", snap.cells.all { it != 0 })
        }
    }

    /** Есть ли на поле линия из трёх клеток значения [mark]. */
    private fun tttLine(cells: List<Int>, mark: Int): Boolean {
        val lines = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )
        return lines.any { line -> line.all { cells[it] == mark } }
    }

    // ----------------------------------------------------------- ничья

    @Test
    fun botAcceptsDrawOfferFromPeer() {
        val net = PairNetwork()
        net.start()
        val bot = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.CHESS)
        tick(net, bot)
        assertEquals(SessionStatus.ACTIVE, net.managerB.session(sid)!!.status)

        net.managerA.offerDraw(sid)
        net.pump()
        assertTrue("пир не увидел предложения", net.managerB.session(sid)!!.drawOfferByPeer)

        tick(net, bot)
        val s = net.managerA.session(sid)!!
        assertEquals("бот не принял ничью", SessionStatus.FINISHED, s.status)
        assertNotNull(s.result)
        assertNull(s.result!!.winner)
        assertTrue(net.eventsB.over.isNotEmpty())
    }

    // --------------------------------------------------- шахматы и шашки

    @Test
    fun botChessMovesStayLegalAndInSync() {
        val net = PairNetwork()
        net.start()
        val botA = BotAgent(net.managerA) { true }
        val botB = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.CHESS)
        tick(net, botA, botB, times = 40)

        // Ни одного расхождения: дайджесты равны на каждом тике — иначе
        // сессионный слой давно бы объявил SYNC_CONFLICT.
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        val s = net.managerA.session(sid)!!
        assertTrue(s.status == SessionStatus.ACTIVE || s.status == SessionStatus.FINISHED)
        assertNull(net.eventsA.conflicts.firstOrNull())
        assertNull(net.eventsB.conflicts.firstOrNull())
    }

    @Test
    fun botCheckersMovesStayLegalAndInSync() {
        val net = PairNetwork()
        net.start()
        val botA = BotAgent(net.managerA) { true }
        val botB = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.CHECKERS)
        tick(net, botA, botB, times = 60)

        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        val s = net.managerA.session(sid)!!
        assertTrue(s.status == SessionStatus.ACTIVE || s.status == SessionStatus.FINISHED)
        assertNull(net.eventsA.conflicts.firstOrNull())
        assertNull(net.eventsB.conflicts.firstOrNull())
    }

    // ------------------------------------------------------------- балда

    @Test
    fun botBaldaFindsDictionaryWordAsFirstMover() {
        val net = PairNetwork()
        // Инициатор играет чёрными (сторона B) — бот получает сторону A и
        // ходит первым.
        net.managerA = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeA), net.eventsA, { net.clock },
            sideDecider = { Side.B },
        )
        net.managerB = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeB), net.eventsB, { net.clock },
        )
        val bot = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.BALDA)
        // Первый тик — бот принимает приглашение, второй — делает ход.
        tick(net, bot, times = 2)

        val snap = net.managerB.snapshot(sid) as com.meshgames.game.BaldaSnapshot
        // Первое слово по порядку перебора: клетка 5 (над «б»), буква «ы»,
        // путь [10, 5] → «бы»; слово словарное, спора нет.
        assertEquals(29, snap.cells[5])
        assertArrayEquals(intArrayOf(10, 5), snap.lastPath)
        assertNull(snap.dispute)
        assertEquals(Side.B, snap.turn)
        assertEquals(2, snap.myScore)
    }

    @Test
    fun botAcceptsBaldaDisputeVerdict() {
        val net = PairNetwork()
        net.start()
        val bot = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.BALDA)
        tick(net, bot)
        assertEquals(SessionStatus.ACTIVE, net.managerA.session(sid)!!.status)

        // A (сторона A) кладёт «ба» — слова нет в словаре, уходит спором.
        assertTrue(
            net.managerA.submitMove(sid, MoveInput.LetterAdd(5, 1, intArrayOf(10, 5))),
        )
        net.pump()
        assertTrue(net.managerB.pendingVerdict(sid))

        tick(net, bot)
        val snap = net.managerB.snapshot(sid) as com.meshgames.game.BaldaSnapshot
        assertNull("бот не закрыл спор", snap.dispute)
        // Вердикт принят: очки сопернику, очередь хода у него.
        assertEquals(2, snap.peerScore)
        assertEquals(Side.B, snap.turn)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
    }

    @Test
    fun botBaldaNeverRepeatsWordsOverManyTurns() {
        val net = PairNetwork()
        // Боты на обеих сторонах: словарь крошечный, партия должна кончиться.
        net.managerA = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeA), net.eventsA, { net.clock },
        )
        net.managerB = SessionManager(
            InMemorySessionStore(), net.transportRef(net.nodeB), net.eventsB, { net.clock },
        )
        val botA = BotAgent(net.managerA) { true }
        val botB = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.BALDA)
        tick(net, botA, botB, times = 20)

        val s = net.managerA.session(sid)!!
        assertEquals("партия не завершилась", SessionStatus.FINISHED, s.status)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        // Слова не повторялись: каждый записан только один раз
        // (пропуски в счёт не считаем — это пустые записи истории).
        val words = (net.managerA.snapshot(sid) as com.meshgames.game.BaldaSnapshot).words
            .filter { !it.passed }
        val seen = words.map { BaldaEngine.normalize(it.word) }
        assertEquals("повтор слова в списке", seen.size, seen.toSet().size)
    }

    // -------------------------------------------------------------- дурак

    @Test
    fun botDurakPlaysFullGameOverNetworkInSync() {
        val net = PairNetwork()
        net.start()
        val botA = BotAgent(net.managerA) { true }
        val botB = BotAgent(net.managerB) { true }
        val sid = invite(net, GameType.DURAK)
        // Атака/бой/беру/хватит чередуются по фазам (атакующий может ходить
        // несколько раз подряд) — сеть гоняет всё через журнал и повторы.
        tick(net, botA, botB, times = 300)

        val s = net.managerA.session(sid)!!
        assertEquals("партия не завершилась", SessionStatus.FINISHED, s.status)
        assertEquals(net.managerA.digest(sid), net.managerB.digest(sid))
        assertNull(net.eventsA.conflicts.firstOrNull())
        assertNull(net.eventsB.conflicts.firstOrNull())
        assertNotNull("нет итога партии", net.managerA.snapshot(sid)!!.result)
    }

    private fun assertArrayEquals(expected: IntArray, actual: IntArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
