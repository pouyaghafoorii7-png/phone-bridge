package com.phonebridge.android

/**
 * The ways a call's sound can go through the phone, named like iTell's method picker so you can
 * compare: if iTell works on your phone with "Panda", pick Panda here. The letter codes are the same
 * ones iTell uses inside. Four of its methods are hidden variants without an animal name.
 *
 * Not offered: Lion (u3) and Tiger (u8). They need root and a native sound library that talks to the
 * phone's sound hardware directly; that cannot be built from the public Android API.
 */
object LineMethods {
    class Info(val code: String, val name: String, val how: String)

    val all: List<Info> = listOf(
        Info("k7", "Panda", "Android call redirection"),
        Info("q2", "Koala", "call redirection, phone mic and earpiece closed"),
        Info("w5", "Penguin", "call redirection with a virtual headset"),
        Info("h9", "Otter", "call redirection with a virtual headset (wired type)"),
        Info("d4", "Dolphin", "telephony output"),
        Info("r3", "Rabbit", "telephony output, plain"),
        Info("y1", "Squirrel", "redirection voice in, downlink capture out"),
        Info("e5", "Hedgehog", "Android call-audio interception API"),
        Info("b7", "Cat", "MediaTek background sound"),
        Info("s6", "Giraffe", "Samsung VOICE_TX track"),
        Info("c9", "Elephant", "Qualcomm in-call music"),
        Info("f6", "Flamingo", "telephony output in its own format"),
        Info("p5", "Pig", "speaker and microphone (low quality)"),
        Info("g8", "Owl", "call forwarding switch (Samsung Exynos, MediaTek)"),
        Info("m4", "Zebra", "Qualcomm in-call music, stereo"),
        Info("v2", "Parrot", "call forwarding switch, downlink capture"),
        Info("j3", "Peacock", "call forwarding, first switch only"),
        Info("t4", "Swan", "call forwarding, voice from its own process"),
        Info("x6", "Turtle", "call forwarding, downlink capture, own process"),
        Info("l5", "Leopard", "MediaTek background sound, stereo"),
        // Hidden in iTell's picker; kept so every one of its 26 codes exists here.
        Info("z8", "z8", "telephony output, mic closed (hidden variant)"),
        Info("y6", "y6", "telephony output, mic closed (hidden variant)"),
        Info("n2", "n2", "redirection voice in, downlink capture out (hidden variant)"),
        Info("a4", "a4", "downlink capture and speaker (hidden variant)"),
    )

    private val byCode = all.associateBy { it.code }

    /** "Panda", or the code for the hidden ones. */
    fun label(code: String): String = byCode[code]?.name ?: code

    /** One line for the picker: "Panda — Android call redirection". */
    fun pickerLabel(code: String): String {
        val i = byCode[code] ?: return code
        return if (i.name == i.code) "${i.code} — ${i.how}" else "${i.name} — ${i.how}"
    }
}
