package com.meshgames.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Простенькие звуки для игр: короткие тона синтезируются на лету (AudioTrack,
 * PCM 16-bit 44100 Гц) — без ассетов и без права RECORD_AUDIO.
 * Включаются настройкой «Звук» ([SettingsStore.sound] → [enabled]).
 */
object SoundPlayer {

    /** Игровые сигналы: последовательность (частота Гц, длительность мс). */
    enum class Cue(val tones: List<Pair<Int, Int>>) {
        CLICK(listOf(880 to 40)),
        MOVE(listOf(520 to 70)),
        OPPONENT(listOf(390 to 70)),
        INVITE(listOf(660 to 110, 880 to 150)),
        WIN(listOf(523 to 100, 659 to 100, 784 to 200)),
        LOSE(listOf(330 to 140, 247 to 240)),
        ALERT(listOf(740 to 90, 620 to 140)),
        TRASH(listOf(300 to 80, 180 to 140)),
    }

    @Volatile
    var enabled: Boolean = true

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "meshgames-sound").apply { isDaemon = true }
    }

    /** Проиграть сигнал (асинхронно, вне главного потока). */
    fun play(cue: Cue) {
        if (!enabled) return
        executor.execute { runCatching { synth(cue) } }
    }

    private fun synth(cue: Cue) {
        for ((freq, ms) in cue.tones) tone(freq, ms)
    }

    private fun tone(freq: Int, ms: Int) {
        val rate = 44100
        val n = rate * ms / 1000
        if (n <= 0) return
        val samples = ShortArray(n)
        val attack = ((rate * 0.01).toInt()).coerceAtLeast(1)
        for (i in 0 until n) {
            val env = when {
                i < attack -> i.toFloat() / attack
                i > n - attack -> (n - i).toFloat() / attack
                else -> 1f
            }
            samples[i] = (sin(2.0 * PI * freq * i / rate) * env * 8000).toInt().toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, n * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, n)
            track.play()
            Thread.sleep(ms + 30L)
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }
}
