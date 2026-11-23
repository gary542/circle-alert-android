package uk.co.agentengines.circlealert

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val serviceOrigin = "https://springgreen-tarsier-821453.hostingersite.com"
internal class ApiError(val status: Int, message: String) : Exception(message)
internal fun fields(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
internal fun JSONObject.items(key: String): List<JSONObject> {
    val a = optJSONArray(key) ?: return emptyList()
    return List(a.length()) { a.getJSONObject(it) }
}

// Only the session cookie crosses app launches. The Android Keystore key is non-exportable.
internal class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "circle-alert.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun read(): String? {
        val value = prefs.getString("cookie", null) ?: return null
        return try {
            val data = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            cipher.doFinal(data, 12, data.size - 12).toString(Charsets.UTF_8)
        } catch (_: Exception) { clear(); null }
    }
    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        if (!prefs.edit().putString("cookie", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
            throw IOException("Could not save this sign-in on the phone.")
    }
    fun clear() { prefs.edit().clear().commit() }
}

internal class CircleApi(val origin: String, private val vault: SessionVault?, private val testTls: SSLSocketFactory? = null) {
    init {
        val uri = URI(origin)
        require(uri.host != null && uri.rawPath.isNullOrEmpty() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.scheme == "https" && (testTls == null || uri.host == "127.0.0.1"))
    }
    private var cookie = vault?.read()
    var csrf = ""
        private set
    fun forget() { cookie = null; csrf = ""; vault?.clear() }

    suspend fun request(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.contains("..") && !path.contains('?'))
        val connection = URL(origin + "/api" + path).openConnection() as HttpsURLConnection
        if (testTls != null) connection.sslSocketFactory = testTls
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            cookie?.let { connection.setRequestProperty("Cookie", "circle_session=$it") }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Origin", origin)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-CSRF-Token", csrf)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val data = try { JSONObject(text) } catch (_: Exception) {
                throw ApiError(status, "The server returned an unexpected response.")
            }
            if (status !in 200..299) throw ApiError(status, data.optString("error", "Please retry."))
            val header = connection.getHeaderField("Set-Cookie")
            if (header != null) {
                val session = HttpCookie.parse(header).firstOrNull { it.name == "circle_session" }
                if (session != null) {
                    if (session.value.isNullOrEmpty() || session.maxAge == 0L) forget()
                    else { vault?.write(session.value); cookie = session.value }
                }
            }
            if (data.has("csrf")) csrf = data.getString("csrf")
            data
        } finally { connection.disconnect() }
    }
}
