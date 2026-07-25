package com.rover.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityManager {

    private const val PREFS_NAME = "secure_prefs"
    private const val KEY_BIOMETRICS_ENABLED = "biometrics_enabled"
    private const val KEY_LAST_AUTH_TIME = "last_auth_time"
    private const val SIX_HOURS_MS = 6 * 60 * 60 * 1000L

    private lateinit var encryptedPrefs: SharedPreferences

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }


    var isBiometricsEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_BIOMETRICS_ENABLED, true)
        set(value) {
            encryptedPrefs.edit().putBoolean(KEY_BIOMETRICS_ENABLED, value).apply()
        }

    fun markAuthenticated() {
        encryptedPrefs.edit().putLong(KEY_LAST_AUTH_TIME, System.currentTimeMillis()).apply()
    }

    fun requiresBiometricAuth(): Boolean {
        if (!isBiometricsEnabled) return false
        
        val lastAuth = encryptedPrefs.getLong(KEY_LAST_AUTH_TIME, 0L)
        val now = System.currentTimeMillis()
        return (now - lastAuth) > SIX_HOURS_MS
    }
}
