package com.meshgames.game.session

import com.meshgames.game.protocol.Envelope
import com.meshgames.game.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Схема «повторяй и не жди»: фиксированное расписание повторов, никаких
 * standalone ACK (в протоколе их нет вовсе) и никакого ожидания
 * подтверждений — только piggyback cumAck в заголовке.
 */
class ReliableChannelTest {
    private val SID = 77

    private fun channel() = ReliableChannel(SID)

    @Test
    fun inOrderDeliveryProducesNoAckTraffic() {
        val a = channel()
        val b = channel()
        val now = 0L
        val f0 = a.send(MsgType.MOVE, SID, byteArrayOf(1), now)
        val f1 = a.send(MsgType.MOVE, SID, byteArrayOf(2), now)
        assertEquals(2, a.pendingCount())

        val r0 = b.receive(f0.frame, now)
        assertEquals(1, r0.deliveries.size)
        assertEquals(MsgType.MOVE, r0.deliveries[0].type)
        assertTrue(r0.deliveries[0].payload.contentEquals(byteArrayOf(1)))
        // Получатель ничего не «должен»: отдельных ACK не порождаем
        assertTrue(r0.toSend.isEmpty())

        val r1 = b.receive(f1.frame, now)
        assertEquals(1, r1.deliveries.size)
        assertTrue(r1.toSend.isEmpty())
        // Ни по какому тику B не начинает слать ACK — их больше нет
        assertTrue(b.tick(now + 600_000).isEmpty())
    }

    @Test
    fun lostFrameIsRetransmittedOnScheduleAndDeduplicated() {
        val a = channel()
        val b = channel()
        val now = 0L
        val f0 = a.send(MsgType.MOVE, SID, byteArrayOf(1), now)

        // кадр потерян: B его не видит

        // до срока первого гэпа переотправки нет
        assertTrue(a.tick(now + 1_000).isEmpty())
        val retrans = a.tick(now + ReliableChannel.RETRY_GAPS_MS[0] + 1)
        assertEquals(1, retrans.size)

        val r = b.receive(retrans[0].frame, now)
        assertEquals(1, r.deliveries.size)
        // повторный приём того же кадра не порождает вторую доставку
        val dup = b.receive(f0.frame, now)
        assertTrue(dup.deliveries.isEmpty())
        assertTrue(dup.toSend.isEmpty())
    }

    @Test
    fun scheduleRunsThenFrameEntersEternalMode() {
        val a = channel()
        val now = 0L
        a.send(MsgType.MOVE, SID, byteArrayOf(1), now)

        // отправки по всему расписанию 0/4/10/20/35/65/125 с — по одной за дуэ
        val gaps = ReliableChannel.RETRY_GAPS_MS
        var t = now
        for ((i, g) in gaps.withIndex()) {
            t += g
            assertEquals("гэп #$i", 1, a.tick(t).size)
        }

        // базовое расписание исчерпано, но кадр не подтверждён — он не
        // забыт, а живёт в вечном режиме (по одному редкому повтору)
        assertEquals(1, a.pendingCount())
        assertTrue(a.tick(t + 1_000).isEmpty())
        val eternal = a.tick(t + 1_000 + ReliableChannel.ETERNAL_GAP_MS)
        assertEquals(1, eternal.size)
        assertEquals(1, a.pendingCount())
    }

    @Test
    fun normalScheduleTailCoversTwoMinuteHoles() {
        val gaps = ReliableChannel.RETRY_GAPS_MS
        assertEquals(6_000L, gaps[0])
        assertEquals(5, gaps.size) // 5 гэпов = 6 отправок (0/6/16/32/62/122 с)
        assertEquals(122_000L, gaps.sum())
        assertEquals(8_000L, ReliableChannel.PATIENT_RETRY_GAPS_MS[0])
    }

    @Test
    fun terminalFramesDoNotEnterEternalMode() {
        val a = channel()
        val now = 0L
        a.send(MsgType.GAME_OVER, SID, byteArrayOf(1), now)
        assertEquals(1, a.pendingCount())

        val gaps = ReliableChannel.PATIENT_RETRY_GAPS_MS
        var t = now
        for ((i, g) in gaps.withIndex()) {
            t += g
            val frames = a.tick(t)
            assertEquals("гэп #$i", 1, frames.size)
        }

        // Попытки базового расписания исчерпаны:
        // Терминальный кадр GAME_OVER должен быть удалён из unacked, а не переходить в вечный режим!
        assertEquals(0, a.pendingCount())
        assertTrue(a.tick(t + ReliableChannel.ETERNAL_GAP_MS).isEmpty())
    }

    @Test
    fun controlFrameBypassesSeqHoleAndIsDeduplicated() {
        val a = channel()
        val b = channel()
        val now = 0L
        val f0 = a.send(MsgType.MOVE, SID, byteArrayOf(1), now)        // seq 0
        val f1 = a.send(MsgType.MOVE, SID, byteArrayOf(2), now)        // seq 1 — потерян
        val sync = a.send(MsgType.SYNC_RES, SID, byteArrayOf(9), now)  // seq 2

        // seq 1 осел в стэше (дырка ниже), а SYNC_RES достаётся мимо порядка
        assertTrue(b.receive(f1.frame, now).deliveries.isEmpty())
        val oob = b.receiveOutOfBand(sync.frame, now)
        assertEquals(1, oob.deliveries.size)
        assertEquals(MsgType.SYNC_RES, oob.deliveries[0].type)

        // повтор того же контрольного кадра — дубликат: ни через OOB, ни через обычный приём
        assertTrue(b.receiveOutOfBand(sync.frame, now).deliveries.isEmpty())
        assertTrue(b.receive(sync.frame, now).deliveries.isEmpty())

        // подтверждение видит и стэш, и отданный мимо порядка: до закрытия
        // дырки биты i=1 (seq 1 в стэше) и i=2 (seq 2 отдан) уже стоят
        val early = b.send(MsgType.MOVE, SID, byteArrayOf(3), now)
        assertEquals(0b110, Envelope.decode(early.frame)!!.bitmap)

        // дырка закрылась (seq 0 дошёл повтором): seq 1 доставляется,
        // seq 2 «проглатывается» без второй доставки — rxNext догоняет сразу
        val drained = b.receive(f0.frame, now)
        assertEquals(2, drained.deliveries.size) // seq 0 и seq 1
        assertEquals(3, b.rxNextSeq())

        // свежий ответ несёт cumAck=2 — всё расписание A погашено,
        // включая повторы SYNC_RES
        val reply = b.send(MsgType.MOVE, SID, byteArrayOf(4), now)
        assertEquals(2, Envelope.decode(reply.frame)!!.cumAck)
        a.receive(reply.frame, now)
        assertEquals(0, a.pendingCount())
        assertTrue(a.tick(now + 10_000_000).isEmpty())
    }

    @Test
    fun handshakeFramesUsePatientSchedule() {
        val a = channel()
        val now = 0L
        a.send(MsgType.INVITE, SID, byteArrayOf(1), now)
        // INVITE живёт по PATIENT-расписанию: первый повтор через 5с, не 4с
        assertTrue(a.tick(now + ReliableChannel.RETRY_GAPS_MS[0]).isEmpty())
        assertEquals(1, a.tick(now + ReliableChannel.PATIENT_RETRY_GAPS_MS[0] + 1).size)
    }

    @Test
    fun missedTicksCatchUpInOneCall() {
        val a = channel()
        val now = 0L
        a.send(MsgType.MOVE, SID, byteArrayOf(1), now)

        // приложение спало — один тик догоняет все оставшиеся повторы
        // базового расписания (взрыва нет: вечный режим — по одному за тик)
        val caught = a.tick(now + 1_000_000)
        assertEquals(ReliableChannel.RETRY_GAPS_MS.size, caught.size)
        assertEquals(1, a.pendingCount())
        // до гэпа вечного режима эфир чист, дальше — редкий повтор
        assertTrue(a.tick(now + 1_000_000).isEmpty())
        assertEquals(
            1,
            a.tick(now + 1_000_000 + ReliableChannel.ETERNAL_GAP_MS).size,
        )
    }

    @Test
    fun outOfOrderIsStashedAndFlushed() {
        val a = channel()
        val b = channel()
        val now = 0L
        val f0 = a.send(MsgType.MOVE, SID, byteArrayOf(10), now)
        val f1 = a.send(MsgType.MOVE, SID, byteArrayOf(11), now)
        val f2 = a.send(MsgType.MOVE, SID, byteArrayOf(12), now)

        // пришли 2, 1, 0
        assertTrue(b.receive(f2.frame, now).deliveries.isEmpty())
        assertTrue(b.receive(f1.frame, now).deliveries.isEmpty())
        val r = b.receive(f0.frame, now)
        assertEquals(3, r.deliveries.size)
        assertTrue(r.deliveries[0].payload.contentEquals(byteArrayOf(10)))
        assertTrue(r.deliveries[1].payload.contentEquals(byteArrayOf(11)))
        assertTrue(r.deliveries[2].payload.contentEquals(byteArrayOf(12)))

        // подтверждение кумулятивное — piggybackом в ответном кадре
        val reply = b.send(MsgType.MOVE, SID, byteArrayOf(13), now)
        val env = Envelope.decode(reply.frame)!!
        assertEquals(2, env.cumAck)
        a.receive(reply.frame, now)
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun piggybackAckClearsPendingEarly() {
        val a = channel()
        val b = channel()
        val now = 0L
        val f = a.send(MsgType.MOVE, SID, byteArrayOf(1), now)
        assertEquals(1, a.pendingCount())
        assertTrue(b.receive(f.frame, now).deliveries.size == 1)

        // B отвечает данными — в заголовке идёт cumAck вместо отдельного ACK
        val reply = b.send(MsgType.MOVE, SID, byteArrayOf(2), now)
        val env = Envelope.decode(reply.frame)!!
        assertTrue(env.hasAck)
        assertEquals(0, env.cumAck)

        a.receive(reply.frame, now)
        // piggyback погасил расписание раньше срока
        assertEquals(0, a.pendingCount())
        assertTrue(a.tick(now + 1_000_000).isEmpty())
    }

    @Test
    fun duplicatesProduceNoTraffic() {
        val a = channel()
        val b = channel()
        val now = 0L
        val f = a.send(MsgType.MOVE, SID, byteArrayOf(1), now)
        assertEquals(1, b.receive(f.frame, now).deliveries.size)

        // дубликаты не порождают ни пакетов, ни состояния
        assertTrue(b.receive(f.frame, now).toSend.isEmpty())
        assertTrue(b.receive(f.frame, now + 500).toSend.isEmpty())
        assertTrue(b.tick(now + 600_000).isEmpty())
    }

    @Test
    fun sentinelCumAckAcknowledgesNothing() {
        // Кадр пира без подтверждений (ACK_NONE + пустой bitmap) не должен
        // снимать ни один наш кадр — иначе повторы гаснут у неполученных.
        val a = channel()
        val now = 0L
        a.send(MsgType.MOVE, SID, byteArrayOf(1), now)
        assertEquals(1, a.pendingCount())
        val fromPeer = Envelope(MsgType.MOVE, SID, 0, byteArrayOf(2)).encode()
        a.receive(fromPeer, now + 10)
        assertEquals(1, a.pendingCount())
    }

    @Test
    fun sentinelCumAckStillAppliesBitmapBits() {
        // При «ничего не подтверждено» bitmap отсчитывается от seq 0:
        // бит 0 подтверждает наш seq 0, остальные кадры живут.
        val a = channel()
        val now = 0L
        a.send(MsgType.MOVE, SID, byteArrayOf(1), now)
        a.send(MsgType.MOVE, SID, byteArrayOf(2), now)
        assertEquals(2, a.pendingCount())
        val frame = Envelope(MsgType.MOVE, SID, 0, byteArrayOf(9), cumAck = null, bitmap = 0b1).encode()
        a.receive(frame, now + 10)
        assertEquals(1, a.pendingCount())
        assertEquals(1, a.tick(now + ReliableChannel.RETRY_GAPS_MS[0]).size)
    }

    @Test
    fun restoreResumesScheduleAfterRestart() {
        val a = channel()
        val now = 0L
        a.send(MsgType.MOVE, SID, byteArrayOf(5), now)
        a.send(MsgType.MOVE, SID, byteArrayOf(6), now)
        val snap = a.snapshotUnacked()
        assertEquals(2, snap.size)

        // рестарт: первый повтор — через гэп (без взрывной пересылки сразу)
        val a2 = channel()
        a2.restore(a.currentSeq(), a.rxNextSeq(), snap, now)
        assertEquals(2, a2.pendingCount())
        assertTrue(a2.tick(now).isEmpty())
        assertEquals(2, a2.tick(now + ReliableChannel.RETRY_GAPS_MS[0] + 1).size)
    }

    @Test
    fun restoreKeepsExhaustedFramesInEternalMode() {
        // рестарт с исчерпанным базовым расписанием: кадр не отброшен
        // (иначе у пира вечная дырка в rxNext), а живёт в вечном режиме —
        // первый повтор через гэп, дальше по расписанию до подтверждения
        val a2 = channel()
        a2.restore(1, 0, listOf(PendingFrame(MsgType.MOVE.id, 0, byteArrayOf(1), attempts = 99)), nowMs = 0L)
        assertEquals(1, a2.pendingCount())
        assertTrue(a2.tick(ReliableChannel.ETERNAL_GAP_MS - 1).isEmpty())
        assertEquals(1, a2.tick(ReliableChannel.ETERNAL_GAP_MS + 1).size)
    }

    @Test
    fun eternalRepeatsStopOnCumAckFromPeer() {
        // Кадр доставлен пиру, но тот молчит (его ответ тонет / экран
        // спит): базовое расписание + вечные повторы уходят впустую,
        // кадр жив — пока пир не подтвердит piggyback-cumAck'ом.
        val a = channel()
        val b = channel()
        val now = 0L
        val f = a.send(MsgType.MOVE, SID, byteArrayOf(1), now)
        assertEquals(1, b.receive(f.frame, now).deliveries.size)

        var t = now
        for (g in ReliableChannel.RETRY_GAPS_MS) t += g
        a.tick(t)
        assertEquals(1, a.pendingCount())
        a.tick(t + ReliableChannel.ETERNAL_GAP_MS)
        assertEquals(1, a.pendingCount())

        // пир ожил и ответил — его cumAck погасил расписание, эфир чист
        val reply = b.send(MsgType.MOVE, SID, byteArrayOf(2), now)
        a.receive(reply.frame, now)
        assertEquals(0, a.pendingCount())
        assertTrue(a.tick(t + 10 * ReliableChannel.ETERNAL_GAP_MS).isEmpty())
    }

    @Test
    fun forceSeqReusesZeroAndKeepsCounterForward() {
        val a = channel()
        val now = 0L
        val first = a.send(MsgType.INVITE, SID, byteArrayOf(1), now)
        assertEquals(0, Envelope.decode(first.frame)!!.seq)
        assertEquals(1, a.currentSeq())

        // рестарт с пустым снимком неподтверждённых: nextSeq=1 сохраняется
        a.restore(1, 0, emptyList(), now)
        assertEquals(0, a.pendingCount())

        // переотправка INVITE с переиспользованным seq=0
        val again = a.send(MsgType.INVITE, SID, byteArrayOf(1), now, forceSeq = 0)
        assertEquals(0, Envelope.decode(again.frame)!!.seq)
        assertEquals(1, a.currentSeq()) // указатель не откатился назад
        assertEquals(1, a.pendingCount())

        // следующий кадр получает seq=1 — «дырки» в пространстве нет
        val move = a.send(MsgType.MOVE, SID, byteArrayOf(2), now)
        assertEquals(1, Envelope.decode(move.frame)!!.seq)
    }

    @Test
    fun forcedInviteDuplicatesAndCumAckCancelSchedule() {
        // пир уже получил исходный INVITE (rxNext=1): повтор с тем же seq=0
        // идёт в дедуп, а INVITE_RESP с cumAck=0 гасит расписание повторов
        val a = channel()
        val b = channel()
        val now = 0L
        val inv = a.send(MsgType.INVITE, SID, byteArrayOf(1), now)
        assertEquals(1, b.receive(inv.frame, now).deliveries.size)

        a.restore(1, 1, emptyList(), now) // рестарт: в снимке неподтверждённых пусто
        val again = a.send(MsgType.INVITE, SID, byteArrayOf(1), now, forceSeq = 0)
        val dup = b.receive(again.frame, now)
        assertTrue(dup.deliveries.isEmpty()) // дубликат: состояние не меняется
        assertTrue(dup.toSend.isEmpty())     // и трафика не порождает

        // ответ пира несёт cumAck=0 → переотправка «погашена»
        val resp = b.send(MsgType.INVITE_RESP, SID, byteArrayOf(1), now)
        a.receive(resp.frame, now)
        assertEquals(0, a.pendingCount())
        assertTrue(a.tick(now + 10_000_000).isEmpty())
    }

    @Test
    fun clearUnackedDropsSchedule() {
        val a = channel()
        val now = 0L
        a.send(MsgType.INVITE, SID, byteArrayOf(1), now)
        assertEquals(1, a.pendingCount())
        a.clearUnacked()
        assertEquals(0, a.pendingCount())
        assertTrue(a.tick(now + 1_000_000).isEmpty())
    }

    @Test
    fun seqWrapsSafely() {
        assertTrue(ReliableChannel.u16Before(65535, 0))
        assertTrue(ReliableChannel.u16Before(0, 1))
        assertTrue(!ReliableChannel.u16Before(0, 65535))
        assertTrue(ReliableChannel.u16BeforeOrEqual(5, 5))
        assertTrue(!ReliableChannel.u16BeforeOrEqual(6, 5))
    }

    /** Оценка латентности: замер по piggyback-cumAck, сброс в [ReliableChannel.restore]. */
    @Test
    fun rttMeasuredFromPiggybackAckAndResetOnRestore() {
        val ch = channel()
        // Кадр ушёл в 1000 мс — подтверждения пока нет.
        ch.send(MsgType.MOVE, SID, byteArrayOf(1), nowMs = 1_000)
        assertNull(ch.rttMs.value)

        // Ответ пира (cumAck покрывает seq=0) пришёл в 5400 мс:
        // замер — от ПЕРВОЙ отправки кадра, а не от ближайшего повтора.
        val reply = Envelope(MsgType.MOVE, SID, 0, byteArrayOf(2), cumAck = 0, bitmap = 0).encode()
        ch.receive(reply, nowMs = 5_400)
        assertEquals(4_400L, ch.rttMs.value)

        // Прямой путь (SYNC_RES → acknowledgeUpTo): свежий замер перекрывает.
        ch.send(MsgType.MOVE, SID, byteArrayOf(3), nowMs = 10_000)
        ch.acknowledgeUpTo(cumAck = 1, bitmap = 0, nowMs = 13_000)
        assertEquals(3_000L, ch.rttMs.value)

        // Рестарт и закрытие: прошлые замеры не наследуются.
        ch.restore(ch.currentSeq(), ch.rxNextSeq(), ch.snapshotUnacked(), nowMs = 20_000)
        assertNull(ch.rttMs.value)
        ch.send(MsgType.MOVE, SID, byteArrayOf(4), nowMs = 20_000)
        ch.clear()
        assertNull(ch.rttMs.value)
    }
}
