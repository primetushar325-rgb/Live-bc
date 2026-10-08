package com.videolive.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stream key storage. Backed by Android Keystore via EncryptedSharedPreferences.
 * If a device cannot provide a Keystore-backed key (rare), we fall back to
 * normal private SharedPreferences rather than breaking the app — the key still
 * never leaves the device and is never logged.
 */
object SecurePrefs {

    private const val FILE = "vl_secure_prefs"
    private const val PLAIN_FILE = "vl_secure_prefs_plain"
    private const val KEY_STREAM_KEY = "stream_key"

    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = context.applicationContext
            val p = try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                try {
                    ctx.deleteSharedPreferences(FILE)
                    val masterKey = MasterKey.Builder(ctx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        ctx,
                        FILE,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (t2: Throwable) {
                    ctx.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
                }
            }
            cached = p
            return p
        }
    }

    fun saveStreamKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_STREAM_KEY, key).apply()
    }

    fun getStreamKey(context: Context): String? =
        try {
            prefs(context).getString(KEY_STREAM_KEY, null)
        } catch (t: Throwable) {
            null
        }
}
