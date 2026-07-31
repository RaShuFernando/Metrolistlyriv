package com.metrolist.music.lyrics.vault

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TurnstileAuthenticator(private val context: Context) {
    
    private val client = OkHttpClient()
    
    @SuppressLint("SetJavaScriptEnabled")
    fun getTurnstileToken(): Flow<String> = callbackFlow {
        var webView: WebView? = null
        
        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun postMessage(message: String) {
                        try {
                            val json = JSONObject(message)
                            if (json.optString("type") == "turnstile-token") {
                                val token = json.optString("token")
                                if (token.isNotEmpty()) {
                                    trySend(token)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }, "AndroidInterface")
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            """
                            window.addEventListener('message', function(event) {
                                if (event.data && event.data.type === 'turnstile-token') {
                                    AndroidInterface.postMessage(JSON.stringify(event.data));
                                }
                            });
                            """.trimIndent(), null
                        )
                    }
                }
                
                loadUrl("https://lyrics.api.dacubeking.com/challenge")
            }
        }
        
        awaitClose {
            kotlinx.coroutines.MainScope().launch {
                webView?.destroy()
            }
        }
    }
    
    suspend fun verifyToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("token", token)
            }
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://lyrics.api.dacubeking.com/verify-turnstile")
                .post(requestBody)
                .build()
                
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val resJson = JSONObject(body)
                    resJson.optString("jwt").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
