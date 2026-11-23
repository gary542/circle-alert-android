package uk.co.agentengines.circlealert

import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import android.view.ViewTreeObserver
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun HoldButton(enabled: Boolean, onSend: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(onSend)
    val view = LocalView.current
    DisposableEffect(view) {
        val observer = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) held = false }
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(observer) }
    }
    LaunchedEffect(held, enabled) {
        if (held && enabled) { delay(holdMillis); if (held) send() }
    }
    val fill by animateFloatAsState(if (held && enabled) 1f else 0f,
        tween(if (held && enabled) holdMillis.toInt() else 0, easing = LinearEasing), label = "Hold progress")
    Box(Modifier.sizeIn(maxWidth = 250.dp).fillMaxWidth().aspectRatio(1f)
        .testTag("alert-hold")
        .clip(CircleShape).background(if (enabled) Color(0xfffff3f3) else Color(0xfff0f4f7))
        .padding(10.dp).clip(CircleShape).background(if (enabled) Color(0xffffe2e2) else Color(0xffdce4e9))
        .padding(10.dp).clip(CircleShape).background(if (enabled) AlertRed else Color(0xff677d8e))
        .clearAndSetSemantics {
            role = Role.Button
            contentDescription = "Hold for one and a half seconds to send an alert to your three contacts"
            if (enabled) onLongClick("Send alert") { send(); true } else disabled()
        }
        .onFocusChanged { if (!it.hasFocus) held = false }
        .onPreviewKeyEvent {
            if (!enabled || it.key !in listOf(Key.Spacebar, Key.Enter, Key.NumPadEnter)) false
            else { if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) held = true
                if (it.type == KeyEventType.KeyUp) held = false
                true }
        }.focusable(enabled).pointerInput(enabled) {
            if (enabled) awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                first.consume()
                held = true
                try { waitForUpOrCancellation()?.consume() } finally { held = false }
            }
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill)
            .background(Color(0x668f0c22)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Copy("!", 43, White, FontWeight.Bold, line = 43)
            Gap(8)
            Copy("Send alert", 27, White, FontWeight.Bold, line = 33)
            Gap(6)
            Copy("Hold to send", 15, White, FontWeight.Medium)
        }
    }
}

internal enum class CardTone(val bg: Color, val border: Color) {
    Plain(White, Color(0xffd7e2ea)), Alert(Color(0xfffff7f7), Color(0xffefb0b8)), Success(Color(0xfff0faf5), Color(0xffb7dfcd))
}

@Composable
internal fun InfoCard(tone: CardTone, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(shape(14)).background(tone.bg).border(1.dp, tone.border, shape(14))
        .padding(17.dp), content = content)
    Gap(15)
}

@Composable
internal fun CardTitle(value: String) { Copy(value, 18, weight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }

@Composable
internal fun CardBody(value: String) { Gap(6); Copy(value, color = Muted); Gap(7) }


@Composable
internal fun HomeScreen(model: CircleModel, requestPermission: () -> Unit, openMap: (Fix.Position) -> Unit) {
    val state = model.snapshot ?: return
    val sent = state.items("sent")
    val alert = sent.firstOrNull { it.optBoolean("active") }
    val ready = state.items("contacts").count { it.optString("status") == "accepted" } == 3
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Title(if (alert == null) "Your safety button" else "Alert in progress") }
        Copy(if (model.online) "Connected" else "Offline", 14, Color(0xff236850),
            modifier = Modifier.background(Color(0xffe5f5ef), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
    }
    Gap(18)
    if (alert == null) {
        Copy("Press and hold for 1.5 seconds.", color = Muted)
        Gap(30)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HoldButton(ready && model.online && !model.busy) { model.send(false, requestPermission) }
            Gap(14)
            Copy(if (ready) "To your three accepted contacts" else "Connect and confirm three contacts first", 14, Muted, align = TextAlign.Center)
        }
        Gap(22)
        Action("Send a test alert", enabled = ready && model.online && !model.busy) { model.send(true, requestPermission) }
        Gap(10)
        Copy("Your phone requests location permission with each alert. If location is unavailable, the alert still goes to your contacts.", 14, Muted)
    } else {
        val answered = alert.items("recipients").any { !it.isNull("acknowledged") }
        InfoCard(if (answered) CardTone.Success else CardTone.Alert) {
            CardTitle((if (alert.optBoolean("test")) "Test alert" else "Alert") + " · " +
                (if (answered) "Someone is responding" else "Waiting for a response"))
            CardBody(localTime(alert.getLong("time")))
        }
        LocationCard(alert, openMap)
        RecipientRows(alert)
        Gap(10)
        Copy("Push acceptance is not proof that a person saw the alert. Wait for their acknowledgement.", 14, Muted)
        Gap(10)
        Action("I’m safe now", true, model.online && !model.busy) {
            model.mutate("/alerts/${alert.getString("id")}/safe", JSONObject(), "Your contacts can now see that you’re safe.")
        }
    }
    Gap(15)
    Notifications(model)
    if (sent.isNotEmpty()) {
        CardTitle("Recent alerts")
        sent.take(3).forEach { a ->
            Copy(localTime(a.getLong("time")) + " · " + (if (a.optBoolean("test")) "Test" else "Alert") + " · " +
                (if (a.optBoolean("active")) "Active" else "Marked safe"), 14, Muted)
            Gap(6)
        }
    }
}

@Composable
private fun LocationCard(alert: JSONObject, openMap: (Fix.Position) -> Unit) {
    val l = alert.optJSONObject("location")
    if (l == null) {
        val status = alert.optString("locationStatus", "unavailable")
        Copy(if (status == "finding") "Location has not arrived yet." else "No location shared ($status).", 14, Muted)
        Gap(10)
        return
    }
    val fix = Fix.Position(l.getDouble("lat"), l.getDouble("lon"), l.getDouble("accuracy").toFloat(), l.getLong("timestamp"))
    InfoCard(CardTone.Plain) {
        CardTitle("Alert location")
        CardBody(String.format(Locale.UK, "%.5f, %.5f", fix.lat, fix.lon) +
            "\nAccuracy: approximately ${fix.accuracy.roundToInt()} metres\nCaptured " + localTime(fix.time))
        Action("Open location in Maps", primary = true) { openMap(fix) }
        Gap(7)
        Copy("An alert-time position, not live tracking. Opening Maps shares these coordinates with Google.", 14, Muted)
    }
}

@Composable
private fun RecipientRows(alert: JSONObject) {
    alert.items("recipients").forEach { person ->
        val responded = !person.isNull("acknowledged")
        val name = person.getString("name")
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(39.dp).background(Color(0xffe5f1f0), CircleShape), contentAlignment = Alignment.Center) {
                Copy(name.take(1), color = Teal, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Copy(name, weight = FontWeight.Bold)
                Copy(when {
                    responded -> "Acknowledged " + localTime(person.getLong("acknowledged"))
                    !person.isNull("shown") -> "Alert opened"
                    else -> when (person.optString("pushState")) {
                        "provider-accepted" -> "Push service accepted"
                        "queued" -> "Notification queued"
                        "failed" -> "Push failed — check inbox"
                        "no-device" -> "No phone notifications"
                        else -> "Awaiting response"
                    }
                }, 14, Muted)
            }
            Copy(if (responded) "Responding" else "Waiting", 14, if (responded) Teal else Muted)
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
internal fun InboxScreen(model: CircleModel, openMap: (Fix.Position) -> Unit) {
    val state = model.snapshot ?: return
    Title("Your alert inbox")
    Gap(8)
    Copy("Real alerts from people you’ve connected with.", color = Muted)
    Gap(18)
    val inbox = state.items("inbox")
    if (inbox.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CardTitle("No alerts yet"); CardBody("Ask a connected friend to send a test alert.")
        }
    }
    inbox.forEach { a ->
        val active = a.optBoolean("active")
        val mine = a.items("recipients").find { it.optString("uid") == state.getJSONObject("user").optString("id") }
        InfoCard(if (active) CardTone.Alert else CardTone.Success) {
            Copy((if (a.optBoolean("test")) "TEST ALERT" else "ALERT") + " · " + if (active) "ACTIVE" else "MARKED SAFE",
                14, Teal, FontWeight.Bold)
            CardTitle(a.optString("senderName") + if (active) " is asking for help" else " marked themselves safe")
            CardBody(localTime(a.getLong("time")))
            LocationCard(a, openMap)
            if (active && mine != null) {
                val responded = !mine.isNull("acknowledged")
                Action(if (responded) "You’re responding" else "I’m responding", true, !responded && model.online && !model.busy) {
                    model.mutate("/alerts/${a.getString("id")}/ack", JSONObject(), "Your friend can see that you’re responding.")
                }
            }
            RecipientRows(a)
        }
    }
    Notifications(model)
}

@Composable
internal fun Notifications(model: CircleModel) {
    val context = LocalContext.current
    InfoCard(CardTone.Plain) {
        CardTitle("Notifications on this phone")
        if (model.snapshot?.optBoolean("pushReady") != true) {
            CardBody("The owner needs to finish notification setup.")
        } else {
            CardBody("Circle Alert delivers background notifications through your phone’s browser. Sign in there with the same account, then tap Enable notifications.")
            Action("Set up phone notifications") {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.origin))) }
                catch (_: android.content.ActivityNotFoundException) { model.show("Install a browser that supports notifications to continue.") }
            }
        }
        Gap(8)
        Copy("Keep the browser signed in for notifications. Tapping a notification opens its web inbox. Sign out there to disconnect it. Phone settings and Do Not Disturb can affect delivery.", 14, Muted)
    }
}
