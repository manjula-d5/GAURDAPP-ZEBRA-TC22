package com.storelense.gateBt.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenManager @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "gate_bt_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var accessToken:  String? get() = prefs.getString(KEY_ACCESS,   null); set(v) = prefs.edit().putString(KEY_ACCESS,   v).apply()
    var refreshToken: String? get() = prefs.getString(KEY_REFRESH,  null); set(v) = prefs.edit().putString(KEY_REFRESH,  v).apply()
    var username:     String? get() = prefs.getString(KEY_USERNAME, null); set(v) = prefs.edit().putString(KEY_USERNAME, v).apply()
    var storeId:      String? get() = prefs.getString(KEY_STORE,    null); set(v) = prefs.edit().putString(KEY_STORE,    v).apply()

    val isLoggedIn: Boolean get() = accessToken != null

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_ACCESS   = "access_token"
        private const val KEY_REFRESH  = "refresh_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_STORE    = "store_id"
    }
}
