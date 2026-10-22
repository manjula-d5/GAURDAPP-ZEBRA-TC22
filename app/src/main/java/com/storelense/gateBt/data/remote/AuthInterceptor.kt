package com.storelense.gateBt.data.remote

import com.storelense.gateBt.data.remote.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {

    @Volatile private var isRefreshing = false
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenManager.accessToken
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }

        val response = chain.proceed(request)

        if (response.code == 401 || response.code == 403) {
            val refreshToken = tokenManager.refreshToken ?: return response

            synchronized(lock) {
                val newToken = tokenManager.accessToken
                if (newToken != null && newToken != token) {
                    response.close()
                    return chain.proceed(
                        chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $newToken")
                            .build()
                    )
                }

                if (isRefreshing) return response
                isRefreshing = true

                try {
                    val refreshResult = runBlocking { tryRefresh(refreshToken) }
                    isRefreshing = false

                    return if (refreshResult != null) {
                        tokenManager.accessToken  = refreshResult.accessToken
                        tokenManager.refreshToken = refreshResult.refreshToken
                        response.close()
                        chain.proceed(
                            chain.request().newBuilder()
                                .addHeader("Authorization", "Bearer ${refreshResult.accessToken}")
                                .build()
                        )
                    } else {
                        tokenManager.clear()
                        response
                    }
                } catch (e: Exception) {
                    isRefreshing = false
                    Timber.e(e, "AuthInterceptor: Exception during refresh flow")
                    tokenManager.clear()
                    return response
                }
            }
        }

        return response
    }

    private suspend fun tryRefresh(refreshToken: String): com.storelense.gateBt.data.remote.dto.RefreshData? {
        return try {
            val plainRetrofit = Retrofit.Builder()
                .baseUrl(com.storelense.gateBt.BuildConfig.BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            val api = plainRetrofit.create(ApiService::class.java)
            val resp = api.refresh(RefreshRequest(refreshToken))
            if (resp.isSuccessful) resp.body()?.data else null
        } catch (e: Exception) {
            Timber.e(e, "AuthInterceptor: network error during tryRefresh")
            null
        }
    }
}
