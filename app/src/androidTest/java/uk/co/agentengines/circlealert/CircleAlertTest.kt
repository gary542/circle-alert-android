package uk.co.agentengines.circlealert

import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// May only connect to a loopback test server with an explicit throwaway CA.
@RunWith(AndroidJUnit4::class)
class CircleAlertTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val tls by lazy {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(requireNotNull(args.getString("testCa")), Base64.DEFAULT).inputStream())
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
    private fun client() = CircleApi("https://127.0.0.1:41892", null, tls)
    private val model get() = ViewModelProvider(ui.activity)[CircleModel::class.java]
    private fun press(text: String) = ui.onNodeWithText(text).performScrollTo().performClick()
    private fun visible(text: String) {
        ui.waitUntil(35_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    private fun denySystemLocationPrompt() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun deny(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.viewIdResourceName?.endsWith(":id/permission_deny_button") == true ||
                (node.text?.toString()?.lowercase() in setOf("deny", "don’t allow", "don't allow") && node.isClickable))
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return (0 until node.childCount).any { deny(node.getChild(it)) }
        }
        val end = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < end) {
            if (deny(automation.rootInActiveWindow)) return
            SystemClock.sleep(100)
        }
    }
    private fun fill(tag: String, value: String) = ui.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun withModel(work: () -> Unit) { ui.runOnIdle { work() } }

    @Test fun nativeSignInInvitationsAlertAcknowledgementAndSafeClosure() = runBlocking {
        // All four accounts live only in the disposable service started by the test script.
        val owner = client()
        val friends = List(3) { client() }
        val prefix = "u" + UUID.randomUUID().toString().replace("-", "").take(8)
        val testPassword = "test-" + UUID.randomUUID()
        val access = requireNotNull(args.getString("testAccess"))
        fun info(i: Int) = fields("login" to "$prefix$i", "name" to "Person $i", "password" to testPassword,
            "phone" to "+44770090010$i", "accessCode" to access)
        owner.request("/auth/register", info(0))
        friends.forEachIndexed { index, friend ->
            val i = index + 1
            val invite = owner.request("/contacts/invite", fields("name" to "Friend $i", "phone" to "+44770090010$i"))
            friend.request("/auth/register", info(i).put("accessCode", invite.getString("code")))
            friend.request("/contacts/claim", fields("code" to invite.getString("code")))
            if (i < 3) owner.request("/contacts/${invite.getString("id")}/confirm", fields())
        }

        withContext(Dispatchers.Main) { model.useTestServer(client()) }
        visible("Welcome to Circle Alert")
        press("Create account"); visible("Create your account")
        press("Back to sign in"); press("Use recovery code"); visible("Recover your account"); press("Back to sign in")
        fill("username", prefix + "0")
        fill("password", testPassword)
        press("Sign in"); visible("Your safety button")
        ui.onNodeWithText("Send a test alert").assertIsNotEnabled()
        press("My people"); visible("Confirm this is my friend")
        press("Confirm this is my friend"); visible("Contact confirmed.")
        press("My button")
        val hold = ui.onNodeWithContentDescription("Hold for one and a half seconds to send an alert to your three contacts")
        hold.performScrollTo().performTouchInput { click() }
        ui.mainClock.advanceTimeBy(1800)
        assertEquals(0, owner.request("/state").items("sent").size)
        // Keep the synthetic pointer down across a real Android frame, then advance the Compose clock.
        hold.performTouchInput { down(center) }
        ui.runOnIdle { }
        ui.mainClock.advanceTimeBy(1800)
        denySystemLocationPrompt()
        visible("Alert in progress")
        ui.onRoot().performTouchInput { up() }
        // Keep location out of this consent scenario. Android's permission result is denied on this test device.
        visible("No location shared (denied).")
        val a = owner.request("/state").items("sent").single()
        val id = a.getString("id")
        assertFalse(a.getBoolean("test"))
        for (friend in friends) assertEquals(id, friend.request("/state").items("inbox").single().getString("id"))
        friends[0].request("/alerts/$id/ack", fields())
        withModel { model.refresh() }
        visible("Alert · Someone is responding")
        press("I’m safe now"); visible("Your safety button")
        for (friend in friends) assertFalse(friend.request("/state").items("inbox").single().getBoolean("active"))
        // Sign in as a recipient and see the actual sender and retained closed alert.
        press("Sign out"); visible("Welcome to Circle Alert")
        fill("username", prefix + "1"); fill("password", testPassword); press("Sign in"); visible("Your safety button")
        press("Alert inbox"); visible("Person 0 marked themselves safe")
        ui.activityRule.scenario.recreate()
        visible("Person 0 marked themselves safe")
        Unit
    }

    @Test fun testAlertLocationAndRecoveryUseTheReferenceProtocol() = runBlocking {
        val owner = client()
        val friends = List(3) { client() }
        val prefix = "u" + UUID.randomUUID().toString().replace("-", "").take(10)
        val password = "test-" + UUID.randomUUID()
        val access = requireNotNull(args.getString("testAccess"))
        fun info(i: Int) = fields("login" to "$prefix$i", "name" to "Pilot $i", "password" to password,
            "phone" to "+44770090020$i", "accessCode" to access)
        val signed = owner.request("/auth/register", info(0))
        friends.forEachIndexed { index, friend ->
            val i = index + 1
            val inv = owner.request("/contacts/invite", fields("name" to "Pilot $i", "phone" to "+44770090020$i"))
            friend.request("/auth/register", info(i))
            friend.request("/contacts/claim", fields("code" to inv.getString("code")))
            owner.request("/contacts/${inv.getString("id")}/confirm", fields())
        }
        val request = fields("requestId" to UUID.randomUUID().toString(), "test" to true)
        val id = owner.request("/alerts", request).getString("id")
        assertEquals(id, owner.request("/alerts", request).getString("id"))
        owner.request("/alerts/$id/location", fields("location" to fields("lat" to 51.5, "lon" to -0.12, "accuracy" to 8, "timestamp" to System.currentTimeMillis())))
        for (friend in friends) {
            val alert = friend.request("/state").items("inbox").single()
            assertEquals(51.5, alert.getJSONObject("location").getDouble("lat"), 0.00001)
            assertTrue(alert.getBoolean("test"))
        }
        friends.first().request("/alerts/$id/shown", fields())
        friends.first().request("/alerts/$id/ack", fields())
        owner.request("/alerts/$id/safe", fields())
        val newPassword = "again-$password"
        val reset = client().request("/auth/reset", fields("login" to prefix+"0", "password" to newPassword, "recoveryCode" to signed.getString("recoveryCode")))
        assertNotEquals(signed.getString("recoveryCode"), reset.getString("recoveryCode"))
        try { owner.request("/state"); fail("Old session remained signed in") } catch (e: ApiError) { assertEquals(401, e.status) }
        val restored = client()
        restored.request("/auth/login", fields("login" to prefix+"0", "password" to newPassword))
        assertEquals(id, restored.request("/state").items("sent").single().getString("id"))
        Unit
    }
}
