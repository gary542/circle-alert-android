package uk.co.agentengines.circlealert

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal enum class Page(val label: String) { Home("My button"), People("My people"), Inbox("Alert inbox") }
internal enum class Auth(val path: String) { Login("login"), Register("register"), Reset("reset") }
internal const val holdMillis = 1500L
internal const val permissionDenied = "denied"
internal sealed interface Fix {
    data class Missing(val explanation: String) : Fix
    data class Position(val lat: Double, val lon: Double, val accuracy: Float, val time: Long) : Fix
}
internal fun localTime(ms: Long) = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.UK)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

internal class CircleModel(app: Application) : AndroidViewModel(app) {
    private var api = CircleApi(serviceOrigin, SessionVault(app))
    private val locator = OneShotLocation(app)
    private var pendingLocation: CompletableDeferred<Fix>? = null
    private var permissionInFlight = false
    private val lock = Mutex()
    private val pending = app.getSharedPreferences("pending-alert", Application.MODE_PRIVATE)
    var snapshot by mutableStateOf<JSONObject?>(null)
        private set
    var page by mutableStateOf(Page.Home)
        private set
    var auth by mutableStateOf(Auth.Login)
        private set
    var busy by mutableStateOf(false)
        private set
    var online by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set
    var inviteCode by mutableStateOf("")
        private set
    var recovery by mutableStateOf("")
        private set
    val origin get() = api.origin
    val signedIn get() = snapshot != null
    private var poll: Job? = null

    fun show(message: String) { notice = message }
    fun authMode(value: Auth) { auth = value; notice = ""; recovery = "" }
    fun savedCode() { recovery = "" }
    fun go(next: Page) { page = next; notice = ""; if (next == Page.Inbox) refresh() }

    fun foreground() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (isActive) { refreshNow(); delay(5000) }
        }
    }
    fun background() { poll?.cancel(); poll = null }
    fun refresh() { viewModelScope.launch { refreshNow() } }
    private suspend fun refreshNow() {
        lock.withLock {
            try { readState() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { handle(e, quietAuth = true) }
        }
    }

    private suspend fun readState() {
        val next = api.request("/state")
        snapshot = next
        online = true
        if (page == Page.Inbox) {
            val uid = next.getJSONObject("user").getString("id")
            for (a in next.items("inbox")) {
                val mine = a.items("recipients").firstOrNull { it.optString("uid") == uid }
                if (mine != null && mine.isNull("shown")) api.request("/alerts/${a.getString("id")}/shown", JSONObject())
            }
        }
    }
    private fun clearSession() {
        api.forget()
        snapshot = null
        inviteCode = ""
        recovery = ""
        page = Page.Home
        pending.edit().clear().apply()
        locator.cancel()
        pendingLocation?.cancel()
        pendingLocation = null
    }

    private fun handle(error: Exception, quietAuth: Boolean = false) {
        if (error is ApiError && error.status == 401) {
            val previous = signedIn
            if (previous) { clearSession(); auth = Auth.Login }
            online = true
            if (!quietAuth || previous) notice = error.message.orEmpty()
        } else if (error is IOException) {
            online = false
            notice = "No connection. This action has not been confirmed. Reconnect and refresh."
        } else {
            if (error is ApiError && error.status >= 500) online = false
            notice = error.message ?: "Please retry."
        }
    }

    private fun act(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { lock.withLock { work() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { handle(e) }
            finally { busy = false }
        }
    }
    fun authenticate(body: JSONObject) = act {
        val result = api.request("/auth/" + auth.path, body)
        recovery = result.optString("recoveryCode")
        notice = ""
        if (auth == Auth.Reset) { auth = Auth.Login; notice = "Password reset. Save your new recovery code." }
        else { page = if (recovery.isEmpty()) Page.Home else Page.People; readState() }
    }
    fun logout() = act {
        api.request("/auth/logout", JSONObject())
        clearSession()
        auth = Auth.Login
        notice = "Signed out."
    }

    fun mutate(path: String, payload: JSONObject, message: String, after: (JSONObject) -> Unit = {}) = act {
        val response = api.request(path, payload)
        after(response)
        notice = message
        readState()
    }
    fun invite(name: String, phone: String) = mutate("/contacts/invite", fields("name" to name, "phone" to phone),
        "Invitation created. Share it privately with your friend.") { inviteCode = it.getString("code") }

    fun send(test: Boolean, requestPermission: () -> Unit) {
        val current = snapshot ?: return
        if (busy || !online || current.items("contacts").count { it.optString("status") == "accepted" } != 3 ||
            current.items("sent").any { it.optBoolean("active") }) return
        act {
            val user = current.getJSONObject("user").getString("id")
            val key = "${api.origin}|$user"
            val requestId = pending.getString(key, null) ?: UUID.randomUUID().toString().also {
                check(pending.edit().putString(key, it).commit()) { "Could not prepare this alert. Please retry." }
            }
            val response = api.request("/alerts", fields("requestId" to requestId, "test" to test))
            val alertId = response.getString("id")
            pending.edit().remove(key).apply()
            notice = "Alert saved for your three contacts. Waiting for their response."
            readState()
            locate(alertId, user, requestPermission)
        }
    }
    private fun locate(alertId: String, uid: String, requestPermission: () -> Unit) {
        locator.cancel()
        pendingLocation?.cancel()
        val fix = CompletableDeferred<Fix>()
        pendingLocation = fix
        if (locator.isAllowed()) locator.capture { fix.complete(it) }
        else if (!permissionInFlight) { permissionInFlight = true; requestPermission() }
        viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(12_000) { fix.await() } ?: Fix.Missing("timeout")
                if (pendingLocation !== fix) return@launch
                val body = when (result) {
                    is Fix.Position -> fields("location" to fields("lat" to result.lat, "lon" to result.lon,
                        "accuracy" to result.accuracy.toDouble(), "timestamp" to result.time))
                    is Fix.Missing -> fields("status" to result.explanation)
                }
                lock.withLock {
                    if (snapshot?.optJSONObject("user")?.optString("id") == uid) {
                        api.request("/alerts/$alertId/location", body)
                        readState()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "Alert saved, but location could not be uploaded. Check the connection." }
            finally { if (pendingLocation === fix) { locator.cancel(); pendingLocation = null } }
        }
    }

    fun permissionResult() {
        permissionInFlight = false
        val fix = pendingLocation ?: return
        if (locator.isAllowed()) locator.capture { fix.complete(it) } else fix.complete(Fix.Missing("denied"))
    }

    // Instrumentation can supply an isolated loopback server. There is no incoming intent, UI or exported setter.
    internal suspend fun useTestServer(client: CircleApi) {
        background()
        lock.withLock { api = client; snapshot = null; notice = ""; recovery = ""; inviteCode = ""; auth = Auth.Login; page = Page.Home }
    }
    override fun onCleared() { locator.cancel(); pendingLocation?.cancel(); background() }
}
