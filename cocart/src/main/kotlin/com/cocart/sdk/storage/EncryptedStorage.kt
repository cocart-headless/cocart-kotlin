package com.cocart.sdk.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedStorage(context: Context) : CoCartStorage {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cocart_secure_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override suspend fun get(key: String): String? = prefs.getString(key, null)

    override suspend fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }
}
