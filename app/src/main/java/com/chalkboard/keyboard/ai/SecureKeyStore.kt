package com.chalkboard.keyboard.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys at rest, encrypted with a key held in the Android keystore.
 *
 * Rules that must not be relaxed:
 *  - a key value is never logged, at any level, not even truncated;
 *  - keys are never included in crash reports, analytics or backups
 *    (`allowBackup="false"` in the manifest);
 *  - nothing here is transmitted anywhere. The key goes to the provider the user chose
 *    and nowhere else.
 */
class SecureKeyStore private constructor(private val prefs: SharedPreferences) {

    fun keyFor(providerId: String): String? =
        prefs.getString(providerId, null)?.takeIf { it.isNotBlank() }

    fun store(providerId: String, apiKey: String) {
        prefs.edit().putString(providerId, apiKey.trim()).apply()
    }

    fun remove(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }

    fun hasKey(providerId: String): Boolean = keyFor(providerId) != null

    /** Provider ids with a stored key. */
    fun configuredProviders(): Set<String> =
        prefs.all.keys.filter { hasKey(it) }.toSet()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "SecureKeyStore"
        private const val FILE = "provider_keys"

        fun create(context: Context): SecureKeyStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = try {
                EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                // A rotated or invalidated master key makes the existing file unreadable.
                // Dropping it costs the user a re-entry; refusing to start costs them the
                // keyboard. Note the failure without naming anything it contained.
                Log.w(TAG, "Key store unreadable, recreating", e)
                context.deleteSharedPreferences(FILE)
                EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return SecureKeyStore(prefs)
        }
    }
}
