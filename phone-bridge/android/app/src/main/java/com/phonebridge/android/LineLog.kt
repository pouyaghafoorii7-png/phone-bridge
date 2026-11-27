package com.phonebridge.android

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the app itself decided about the call sound: call states, which method was tried, whether it was
 * silent. The service keeps its own log (what the phone's audio system said). The screen "Call line log"
 * shows both, so one copy of the text tells what happened in a call.
 */
object LineLog {
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        Log.i("PB-Line", msg)
        lines.addLast(clock.format(Date()) + " " + msg)
        while (lines.size > 140) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
