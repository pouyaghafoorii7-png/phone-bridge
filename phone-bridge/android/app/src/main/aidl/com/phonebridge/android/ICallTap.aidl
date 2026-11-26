package com.phonebridge.android;

// Runs inside the Shizuku user service with the shell identity (uid 2000). See CallTapService.kt.
// Both directions of the phone call, the iTell way: the caller's voice comes out of the call line,
// the iPhone user's voice goes into the call line. No speaker, no microphone.
interface ICallTap {
    // Called by Shizuku when the service must stop. The code is fixed by Shizuku.
    void destroy() = 16777114;

    // The ways this phone may do it, best first, comma separated (see CallTapService.order()).
    String methods() = 1;

    // Opens the call line with one method. 16-bit mono PCM little-endian at `rate` Hz.
    // `down`: the service writes the caller's voice here. `up`: the service reads the iPhone
    // user's voice from here and puts it into the call (null = capture only).
    // Returns "" when it works, otherwise a short reason.
    String open(String method, int rate, in ParcelFileDescriptor down, in ParcelFileDescriptor up) = 2;

    // Stops both directions and puts the phone's audio settings back.
    void close() = 3;

    // The uid of the service process (2000 = shell).
    int uid() = 4;

    // One line about this phone, for the status screen and bug reports.
    String info() = 5;
}
