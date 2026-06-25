package com.example.umbrella

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val TAG = "RetrofitClient"

    // 로그인 토큰을 모든 요청 헤더에 자동으로 실어 보낸다
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val token = AppSession.jwtToken
        val request = if (!token.isNullOrEmpty()) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            Log.w(TAG, "토큰 없이 요청 — 인증이 필요한 API는 401 반환됨")
            original
        }
        chain.proceed(request)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(AppSession.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: UmbrellaApiService = retrofit.create(UmbrellaApiService::class.java)
}
