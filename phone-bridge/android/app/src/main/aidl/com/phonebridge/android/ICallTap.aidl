package com.phonebridge.android;

// Runs inside the shell process (uid 2000), started by built-in ADB or by Shizuku. See CallTapService.kt.
// Both directions of the phone call, the iTell way: the caller's voice comes out of the call,
// the iPhone user's voice goes into it. Which way depends on the phone; see LineMethods.kt.
interface ICallTap {
    // Called by Shizuku when the service must stop. The code is fixed by Shizuku.
    void destroy() = 16777114;

    // The methods that can work on this phone, best first, comma separated (iTell's letter codes).
    String methods() = 1;

    // Opens the call line with one method. 16-bit mono PCM little-endian at `rate` Hz.
    // `down`: the service writes the caller's voice here. `up`: the service reads the iPhone
    // user's voice from here and puts it into the call (or plays it, for the speaker methods).
    // Returns "" when it works, otherwise a short reason.
    String open(String method, int rate, in ParcelFileDescriptor down, in ParcelFileDescriptor up) = 2;

    // Stops both directions and puts the phone's audio settings back.
    void close() = 3;

    // The uid of the service process (2000 = shell).
    int uid() = 4;

    // One line about this phone, for the status screen and bug reports.
    String info() = 5;

    // Where the iPhone voice goes for the open method: "direct" (into the call) or "speaker".
    String route() = 6;

    // The service's own recent log lines, for bug reports.
    String log() = 7;
}
