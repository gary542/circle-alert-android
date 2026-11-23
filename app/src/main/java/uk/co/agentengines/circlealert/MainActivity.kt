package uk.co.agentengines.circlealert

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[CircleModel::class.java] }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionResult()
    }

    override fun onStart() { super.onStart(); model.foreground() }
    override fun onStop() { model.background(); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent {
            CircleApp(model, requestPermission = {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, openMap = { position ->
                val uri = Uri.parse("https://www.google.com/maps/search/").buildUpon()
                    .appendQueryParameter("api", "1").appendQueryParameter("query", "${position.lat},${position.lon}").build()
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                catch (_: ActivityNotFoundException) { model.show("No Maps app or browser is available to open this location.") }
            })
        }
    }
}

internal val Ink = Color(0xff102a43)
internal val Muted = Color(0xff526b80)
internal val Teal = Color(0xff24716b)
internal val Background = Color(0xffedf3f8)
internal val Secondary = Color(0xffeef3f7)
internal val Line = Color(0xffe5ecf2)
internal val AlertRed = Color(0xffd5293c)
internal val White = Color.White
internal fun shape(radius: Int = 12) = RoundedCornerShape(radius.dp)

@Composable
internal fun Copy(
    value: String, size: Int = 16, color: Color = Ink, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, align: TextAlign? = null, line: Int = (size * 1.45).toInt(),
    spacing: TextUnit = 0.sp
) {
    Text(value, modifier, color = color, fontSize = size.sp, fontWeight = weight,
        textAlign = align, lineHeight = line.sp, letterSpacing = spacing)
}

@Composable
internal fun Gap(dp: Int) { Spacer(Modifier.height(dp.dp)) }

@Composable
internal fun Title(value: String) {
    Copy(value, size = 24, weight = FontWeight.Bold, line = 29, spacing = (-0.5).sp,
        modifier = Modifier.semantics { heading() })
}

@Composable
internal fun Action(
    label: String, primary: Boolean = false, enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit
) {
    val bg = if (primary) Ink else Secondary
    val fg = if (primary) White else Ink
    Box(modifier.heightIn(min = 48.dp).clip(shape()).background(if (enabled) bg else bg.copy(alpha = .55f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Copy(label, weight = FontWeight.SemiBold, color = fg, align = TextAlign.Center, line = 21)
    }
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit, size: Int = 16) {
    Box(Modifier.heightIn(min = 44.dp).clip(shape()).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Copy(label, size, Teal, FontWeight.SemiBold)
    }
}

@Composable
private fun CircleApp(model: CircleModel, requestPermission: () -> Unit, openMap: (Fix.Position) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    BackHandler(model.page != Page.Home || (!model.signedIn && model.auth != Auth.Login)) {
        if (model.signedIn) model.go(Page.Home) else model.authMode(Auth.Login)
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Teal, background = Background,
        surface = White, onSurface = Ink, onPrimary = White)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth > 850.dp
            Column(Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 1150.dp).fillMaxWidth().padding(horizontal = if (wide) 30.dp else 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.clip(shape()).clickable(role = Role.Button) {
                        focus.clearFocus()
                        model.go(Page.Home)
                        scope.launch { scroll.scrollTo(0) }
                    }.semantics { contentDescription = "Circle Alert home" }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(shape(13)).background(Ink), contentAlignment = Alignment.Center) {
                            Copy("C", 25, White, FontWeight.Bold, line = 30)
                            Box(Modifier.align(Alignment.TopEnd).padding(end = 5.dp, top = 7.dp).size(6.dp)
                                .background(Color(0xff73dbcc), CircleShape))
                        }
                        Spacer(Modifier.width(12.dp))
                        Copy("Circle Alert", if (wide) 21 else 18, weight = FontWeight.Bold)
                    }
                    Copy("Friends testing", if (wide) 14 else 12, modifier = Modifier.border(1.dp, Color(0xffb8c9d7), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Box(Modifier.widthIn(max = if (wide) 1100.dp else 530.dp).fillMaxWidth().padding(horizontal = if (wide) 30.dp else 18.dp)) {
                    if (wide) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Intro(true); Gap(46); Notes() }
                            Spacer(Modifier.width(50.dp))
                            AppPanel(model, requestPermission, openMap, Modifier.width(490.dp))
                        }
                    } else {
                        Column {
                            Gap(8)
                            Column(Modifier.padding(horizontal = 6.dp)) { Intro(false) }
                            Gap(24)
                            AppPanel(model, requestPermission, openMap, Modifier.fillMaxWidth())
                            Gap(25)
                            Column(Modifier.padding(horizontal = 7.dp)) { Notes() }
                        }
                    }
                }
                Gap(25)
                Copy("Circle Alert contacts your trusted people. It does not contact emergency services. Notification delivery depends on connectivity and phone settings.",
                    14, Muted, modifier = Modifier.widthIn(max = 1050.dp).padding(horizontal = 20.dp),
                    align = TextAlign.Center, line = 22)
                Gap(25)
            }
        }
    }
}

@Composable
private fun Intro(wide: Boolean) {
    Copy("YOUR PEOPLE. ONE BUTTON.", if (wide) 14 else 12, Color(0xff327871), FontWeight.Bold, spacing = 2.sp)
    Gap(10)
    Copy(if (wide) "A little closer,\nwhen it matters." else "A little closer, when it matters.",
        if (wide) 52 else 35, weight = FontWeight.Bold, line = if (wide) 58 else 38,
        spacing = (-1).sp, modifier = Modifier.semantics { heading() })
    Gap(12)
    Copy("Send an alert to your three trusted people. See when someone is responding.",
        if (wide) 19 else 16, Color(0xff4a6278), line = if (wide) 30 else 24)
}

@Composable
private fun Notes() {
    Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.size(26.dp).border(1.dp, Color(0xff9db3c4), CircleShape), contentAlignment = Alignment.Center) {
            Copy("i", 16, weight = FontWeight.SemiBold)
        }
        Column {
            Copy("Set up together", 17, weight = FontWeight.Bold)
            Gap(7)
            Copy("Create an account, connect your three friends and enable notifications on each phone. Send a test alert before relying on the connection.", color = Muted, line = 25)
        }
    }
}

@Composable
private fun AppPanel(model: CircleModel, requestPermission: () -> Unit, openMap: (Fix.Position) -> Unit, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val panel = shape(26)
    Column(modifier.shadow(8.dp, panel, ambientColor = Color(0x27133a5c), spotColor = Color(0x27133a5c))
        .clip(panel).background(White).border(1.dp, Color(0xffd4e0ea), panel)) {
        Copy("Real alerts · Set up your three contacts first.", 14, Color(0xff785114),
            FontWeight.SemiBold, Modifier.fillMaxWidth().background(Color(0xfffff4dc)).padding(horizontal = 10.dp, vertical = 13.dp), TextAlign.Center, 19)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Page.entries.forEach { page ->
                val selected = model.page == page
                Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape(10))
                    .background(if (selected) Ink else Color(0xfff0f4f7))
                    .selectable(selected, enabled = model.signedIn, role = Role.Tab) { focus.clearFocus(); model.go(page) }
                    .padding(horizontal = 3.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Copy(page.label, 14, if (selected) White else Color(0xff4a6278), FontWeight.SemiBold,
                        align = TextAlign.Center, line = 18)
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 440.dp).padding(horizontal = 20.dp, vertical = 22.dp)) {
            if (model.signedIn && !model.online) Copy("Connection unavailable. Alert status may be out of date.", 14, Color(0xffa51e30))
            if (model.notice.isNotBlank()) {
                Copy(model.notice, 14, if (model.online) Teal else Color(0xffa51e30),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Gap(12)
            }
            RecoveryCard(model)
            if (!model.signedIn) AuthScreen(model)
            else when (model.page) {
                Page.Home -> HomeScreen(model, requestPermission, openMap)
                Page.People -> PeopleScreen(model)
                Page.Inbox -> InboxScreen(model, openMap)
            }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Copy(model.snapshot?.optJSONObject("user")?.let { "Signed in · " + it.optString("name") } ?: "Not signed in", 14, Muted, modifier = Modifier.weight(1f))
            if (model.signedIn) TextAction("Sign out", model::logout, size = 14)
        }
    }
}
