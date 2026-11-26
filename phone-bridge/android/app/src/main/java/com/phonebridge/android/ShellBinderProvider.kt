package com.phonebridge.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Receives the call-line service's binder from the ADB-started shell process (ShellMain). Declared
 * in the manifest with the INTERACT_ACROSS_USERS_FULL permission, which the shell identity holds, so
 * only a shell process (uid 2000) can reach it.
 */
class ShellBinderProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "attach" || extras == null || Binder.getCallingUid() != SHELL_UID) return null
        if (extras.getString("token") != AdbShell.token(context)) {
            return Bundle().apply { putBoolean("stale", true) } // an older copy: tell it to exit
        }
        val binder = extras.getBinder("binder") ?: return null
        AdbShell.onShellBinder(ICallTap.Stub.asInterface(binder))
        return Bundle()
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0

    private companion object { const val SHELL_UID = 2000 }
}
