package uk.co.agentengines.circlealert

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import org.json.JSONObject

@Composable
private fun Field(label: String, text: String, tag: String, keyboard: KeyboardType = KeyboardType.Text,
                  limit: Int = 128, hint: String = "", change: (String) -> Unit) {
    Copy(label, 14, weight = FontWeight.SemiBold)
    Gap(6)
    BasicTextField(value = text, onValueChange = { if (it.length <= limit) change(it) },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
        textStyle = TextStyle(color = Ink, fontSize = 16.sp), singleLine = true, cursorBrush = SolidColor(Teal),
        visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).border(1.dp, Color(0xffb9cad7), shape(9))
                .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty() && hint.isNotEmpty()) Copy(hint, color = Muted)
                inner()
            }
        })
    Gap(10)
}

@Composable
internal fun RecoveryCard(model: CircleModel) {
    if (model.recovery.isEmpty()) return
    InfoCard(CardTone.Plain) {
        CardTitle("Save your recovery code")
        SelectionContainer { Copy(model.recovery, weight = FontWeight.SemiBold) }
        CardBody("Keep it somewhere private. It is shown once during this session and lets you reset your password.")
        Action("I’ve saved it", onClick = model::savedCode)
    }
}

@Composable
internal fun AuthScreen(model: CircleModel) = key(model.auth) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val register = model.auth == Auth.Register
    val reset = model.auth == Auth.Reset
    val focus = LocalFocusManager.current
    Title(if (register) "Create your account" else if (reset) "Recover your account" else "Welcome to Circle Alert")
    Gap(8)
    Copy("Private testing with people you know." + if (register) " Numbers identify contacts; they are not verified by SMS." else "", color = Muted)
    Gap(18)
    if (register) {
        Field("Your name", name, "name", limit = 50) { name = it }
        Field("Your phone number", phone, "phone", KeyboardType.Phone, hint = "+44 7700 900123") { phone = it }
    }
    Field("Username", login, "username", limit = 50) { login = it }
    if (reset) Field("Recovery code", code, "recovery") { code = it }
    Field(if (reset) "New password" else "Password", password, "password", KeyboardType.Password) { password = it }
    if (register) {
        Field("Private testing or invitation code", code, "access-code") { code = it }
        Copy("Your account stores your name, number and accepted contacts. An alert shares your name and any permitted location with those contacts. Alert records expire after seven days.", 14, Muted)
        Gap(12)
    }
    Action(if (register) "Create account" else if (reset) "Reset password" else "Sign in", true, !model.busy) {
        focus.clearFocus()
        if (login.trim().length < 3 || password.isEmpty() || ((register || reset) && (password.length < 12 || code.isBlank())) ||
            (register && (name.isBlank() || phone.isBlank()))) {
            model.show(if (register || reset) "Complete each field and use a password of at least 12 characters." else "Enter your username and password.")
        } else {
            val data = fields("login" to login.trim(), "password" to password)
            if (register) { data.put("name", name); data.put("phone", phone); data.put("accessCode", code) }
            if (reset) data.put("recoveryCode", code)
            model.authenticate(data)
        }
    }
    Gap(18)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action(if (register || reset) "Back to sign in" else "Create account", modifier = Modifier.weight(1f), enabled = !model.busy) {
            model.authMode(if (register || reset) Auth.Login else Auth.Register)
        }
        if (!register && !reset) Action("Use recovery code", modifier = Modifier.weight(1f), enabled = !model.busy) { model.authMode(Auth.Reset) }
    }
}

@Composable
internal fun PeopleScreen(model: CircleModel) {
    val state = model.snapshot ?: return
    val user = state.getJSONObject("user")
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var name by remember(user.optString("name")) { mutableStateOf(user.optString("name")) }
    var friend by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val canAct = !model.busy && model.online
    Field("Your display name", name, "display-name", limit = 50) { name = it }
    Action("Save my name", enabled = canAct && name.isNotBlank()) {
        focus.clearFocus(); model.mutate("/profile", fields("name" to name), "Your name is updated.")
    }
    Gap(20)
    Title("Your three trusted people")
    Gap(8)
    Copy("Send each friend their own invitation code. They join, accept, and you confirm their identity.", color = Muted)
    Gap(18)
    if (model.inviteCode.isNotEmpty()) InfoCard(CardTone.Plain) {
        CardTitle("Share this invitation privately")
        SelectionContainer { Copy(model.inviteCode, weight = FontWeight.SemiBold) }
        CardBody("Give this code and the app address to your friend. It expires in seven days. No text message has been sent automatically.")
        Action("Share invitation") {
            val message = "Join me on Circle Alert: ${model.origin}\nYour private invitation code: ${model.inviteCode}\n" +
                "Create an account with your phone number, then accept this code in My people."
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message), "Share invitation privately"))
        }
    }
    val contacts = state.items("contacts")
    contacts.forEach { c ->
        val status = c.getString("status")
        val id = c.getString("id")
        InfoCard(CardTone.Plain) {
            CardTitle(c.getString("name"))
            val label = when (status) {
                "claimed" -> "Friend joined — confirm below"
                "accepted" -> "Connected"
                else -> if (c.optLong("expires") < System.currentTimeMillis()) "Invitation expired" else "Invitation pending"
            }
            CardBody(c.getString("phone") + " · " + label)
            c.optJSONObject("recipient")?.let {
                CardBody("Joined as " + it.optString("name") + " · " + it.optString("phone") + "\n" +
                    if (c.optInt("notifications") > 0) "Has a phone registered for notifications" else "No phone notifications registered")
            }
            if (status == "claimed") {
                Copy("Check with your friend directly before allowing alerts and location sharing.", 14, Muted)
                Gap(7)
                Action("Confirm this is my friend", true, canAct) {
                    model.mutate("/contacts/$id/confirm", JSONObject(), "Contact confirmed.")
                }
            }
            TextAction("Remove connection", {
                if (canAct) model.mutate("/contacts/$id/revoke", JSONObject(), "Connection removed. No new alerts will be shared through it.")
            })
        }
    }
    if (contacts.count { it.optString("status") != "pending" || it.optLong("expires") > System.currentTimeMillis() } < 3) {
        Field("Friend’s name", friend, "friend-name", limit = 50) { friend = it }
        Field("Friend’s phone number", number, "friend-phone", KeyboardType.Phone, hint = "+44 7700 900123") { number = it }
        Action("Create invitation", true, canAct && friend.isNotBlank() && number.isNotBlank()) {
            focus.clearFocus()
            model.invite(friend, number)
        }
        Gap(15)
    }
    InfoCard(CardTone.Plain) {
        CardTitle("Accept someone’s invitation")
        Gap(7)
        Field("Personal invitation code", code, "claim-code") { code = it }
        Action("Accept invitation", enabled = canAct && code.isNotBlank()) {
            focus.clearFocus()
            model.mutate("/contacts/claim", fields("code" to code.trim()), "Invitation accepted. Ask your friend to confirm you.") { code = "" }
        }
    }
    state.items("receiving").forEach { c ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Copy("Receiving from " + c.getJSONObject("sender").optString("name"), weight = FontWeight.Bold)
                Copy(if (c.optString("status") == "accepted") "Connection accepted" else "Waiting for their confirmation", 14, Muted)
            }
            TextAction("Leave", {
                if (canAct) model.mutate("/contacts/${c.getString("id")}/revoke", JSONObject(), "Connection removed. No new alerts will be shared through it.")
            })
        }
        HorizontalDivider(color = Line); Gap(10)
    }
    Notifications(model)
}
