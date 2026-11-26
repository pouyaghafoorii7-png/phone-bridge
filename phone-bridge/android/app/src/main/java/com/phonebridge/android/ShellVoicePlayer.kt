package com.phonebridge.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.io.EOFException
import kotlin.system.exitProcess

/**
 * A tiny separate process that only plays voice: it reads 16-bit mono PCM from its standard input and
 * writes it to its own AudioTrack. CallTapService starts it with `app_process` for the call-forwarding
 * methods (Swan, Turtle, Peacock), where the phone's audio HAL forwards only sound that comes from
 * a process of its own.
 *
 *   app_process -Djava.class.path=<apk> /system/bin com.phonebridge.android.ShellVoicePlayer <rate> <usage>
 *
 * It prints "ready ..." on its first line once the track plays, and exits when its input closes.
 */
object ShellVoicePlayer {
    @JvmStatic
    fun main(args: Array<String>) {
        val rate = args.getOrNull(0)?.toIntOrNull() ?: 16000
        val usage = args.getOrNull(1)?.toIntOrNull() ?: AudioAttributes.USAGE_VOICE_COMMUNICATION
        val track = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, rate / 50 * 2 * 8))
                .build()
        } catch (e: Throwable) {
            println("error ${e.javaClass.simpleName}: ${e.message}")
            System.out.flush()
            exitProcess(1)
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            println("error track not initialized")
            System.out.flush()
            exitProcess(1)
        }
        track.play()
        println("ready rate=$rate usage=$usage")
        System.out.flush()
        val input = DataInputStream(System.`in`)
        val buf = ByteArray(1280) // 40 ms at 16 kHz
        try {
            while (true) {
                input.readFully(buf)
                track.write(buf, 0, buf.size)
            }
        } catch (_: EOFException) {
            // The service closed our input: the call line is closed.
        } catch (_: Throwable) {
        }
        try { track.stop() } catch (_: Throwable) {}
        track.release()
        exitProcess(0)
    }
}
