package com.example.filebridge

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** 把每一次请求的方法、路径、状态码、耗时、异常都写进开发者日志（URL 会自动脱敏） */
class DevLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        val fullPath = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val t0 = System.nanoTime()
        DevLog.d("HTTP", "→ ${req.method} ${DevLog.sanitize(url.toString())}")
        return try {
            val resp = chain.proceed(req)
            val ms = (System.nanoTime() - t0) / 1_000_000L
            DevLog.http(req.method, fullPath, resp.code, ms, null)
            resp
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            DevLog.http(req.method, fullPath, -1, ms, e.toString())
            DevLog.e("HTTP", "请求失败 ${req.method} $fullPath · ${e.message}", e)
            throw e
        }
    }
}

object ApiClient {
    fun create(baseUrl: String): FileApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val client = OkHttpClient.Builder()
            .addInterceptor(DevLogInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FileApi::class.java)
    }
}
