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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TurnstileTimeout(message: String) : Exception(message)
class TurnstileVerificationFailed(message: String) : Exception(message)

class TurnstileAuthenticator(private val context: Context) {
    
    private val client = OkHttpClient()
    private val prefs = context.getSharedPreferences("turnstile_prefs", Context.MODE_PRIVATE)

    fun getCachedToken(): String? {
        val savedTime = prefs.getLong("cached_token_timestamp", 0L)
        if (System.currentTimeMillis() - savedTime > 60_000L) {
            clearCachedToken()
            return null
        }
        return prefs.getString("cached_token", null)?.takeIf { it.isNotBlank() }
    }

    fun saveCachedToken(token: String) {
        prefs.edit()
            .putString("cached_token", token)
            .putLong("cached_token_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun clearCachedToken() {
        prefs.edit()
            .remove("cached_token")
            .remove("cached_token_timestamp")
            .apply()
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    fun getTurnstileTokenFromWebView(): Flow<String> = callbackFlow {
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
                            FileLogger.e("TurnstileAuthenticator", "Error in JS interface postMessage", e)
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

    suspend fun getTurnstileToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            val cached = getCachedToken()
            if (!cached.isNullOrEmpty()) {
                return cached
            }
        }

        val token = withTimeoutOrNull(30_000L) {
            getTurnstileTokenFromWebView().first()
        } ?: throw TurnstileTimeout("Failed to get Turnstile token within timeout")

        saveCachedToken(token)
        return token
    }
    
    suspend fun verifyToken(token: String): String = withContext(Dispatchers.IO) {
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
                    val body = response.body?.string()
                        ?: throw TurnstileVerificationFailed("Empty body returned from turnstile verification")
                    val resJson = JSONObject(body)
                    val jwt = resJson.optString("jwt").takeIf { it.isNotEmpty() }
                    jwt ?: throw TurnstileVerificationFailed("JWT missing in turnstile verification response")
                } else {
                    throw TurnstileVerificationFailed("Turnstile verification HTTP error: ${response.code}")
                }
            }
        } catch (e: TurnstileVerificationFailed) {
            throw e
        } catch (e: Exception) {
            FileLogger.e("TurnstileAuthenticator", "Turnstile verification failed with exception", e)
            throw TurnstileVerificationFailed("Turnstile token verification failed: ${e.message}")
        }
    }
}

