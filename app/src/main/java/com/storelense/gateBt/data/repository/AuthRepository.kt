package com.storelense.gateBt.data.repository

import com.storelense.gateBt.data.remote.ApiService
import com.storelense.gateBt.data.remote.TokenManager
import com.storelense.gateBt.data.remote.dto.LoginRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api:                 ApiService,
    private val tokenManager:        TokenManager,
    private val storeConfigRepo:     StoreConfigRepository
) {
    suspend fun login(username: String, password: String): Result<Unit> = try {
        val resp = api.login(LoginRequest(username, password))
        val body = resp.body()
        if (resp.isSuccessful && body?.success == true) {
            val data = body.data!!
            tokenManager.accessToken  = data.accessToken
            tokenManager.refreshToken = data.refreshToken
            tokenManager.username     = data.username
            tokenManager.storeId      = data.storeId
            storeConfigRepo.fetchAndCache()
            Result.Success(Unit)
        } else {
            Result.Error(body?.message ?: "Login failed")
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "Network error")
    }

    fun logout() {
        storeConfigRepo.reset()
        tokenManager.clear()
    }

    val isLoggedIn: Boolean get() = tokenManager.isLoggedIn
    val username:   String? get() = tokenManager.username
    val storeId:    String? get() = tokenManager.storeId
}
