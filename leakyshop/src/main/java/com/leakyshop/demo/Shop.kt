package com.leakyshop.demo

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * LeakyShop is the demo "victim" app for DPDP X-Ray.
 * It does NOT contain real tracker SDKs and sends no data: it performs the same DNS lookups those SDKs make when they
 * initialise, which is exactly the evidence X-Ray captures. The `leaky` flavor behaves like a typical non-compliant app;
 * the `fixed` flavor shows the DPDP-compliant pattern.
 */
object SdkSimulator {
    const val FIREBASE_ANALYTICS = "app-measurement.com"
    const val CRASHLYTICS = "firebase-settings.crashlytics.com"
    const val FACEBOOK = "graph.facebook.com"
    const val APPSFLYER = "launches.appsflyersdk.com"
    const val ADS = "googleads.g.doubleclick.net"
    const val CLEVERTAP = "in1.clevertap-prod.com"
    const val OWN_API = "api.leakyshop.example"

    /** Resolve each host on a background thread, like an SDK warming up its endpoint. No payload is ever sent. */
    fun touch(vararg hosts: String) = hosts.forEach { h -> thread { runCatching { InetAddress.getAllByName(h) } } }
}

object Consent {
    private fun prefs(c: Context) = c.getSharedPreferences("consent", Context.MODE_PRIVATE)
    fun decided(c: Context) = prefs(c).contains("choice")
    fun accepted(c: Context) = prefs(c).getString("choice", null) == "accept"
    fun save(c: Context, accept: Boolean) = prefs(c).edit().putString("choice", if (accept) "accept" else "reject").apply()
    fun withdraw(c: Context) = prefs(c).edit().remove("choice").apply()
}

class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SdkSimulator.touch(SdkSimulator.OWN_API)
        if (!BuildConfig.COMPLIANT) {
            // The classic mistake: SDKs auto-initialise in Application.onCreate, before any consent.
            SdkSimulator.touch(SdkSimulator.FIREBASE_ANALYTICS, SdkSimulator.FACEBOOK, SdkSimulator.CRASHLYTICS, SdkSimulator.APPSFLYER)
        } else if (Consent.accepted(this)) {
            SdkSimulator.touch(SdkSimulator.FIREBASE_ANALYTICS, SdkSimulator.FACEBOOK)
        }
    }
}

class ShopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF5B3CC4))) {
                var showConsent by remember { mutableStateOf(!Consent.decided(this)) }
                Shop(onPrivacy = { showConsent = true })
                if (showConsent) {
                    if (BuildConfig.COMPLIANT) FixedConsent { showConsent = false } else LeakyConsent { showConsent = false }
                }
            }
        }
    }

    @Composable
    private fun LeakyConsent(onDone: () -> Unit) {
        var manage by remember { mutableStateOf(false) }
        var share by remember { mutableStateOf(true) } // pre-ticked: a dark pattern
        if (!manage) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("We value your privacy") },
                text = {
                    Column {
                        Text("We use your data to personalise offers, measure the app and show relevant ads.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = share, onCheckedChange = { share = it })
                            Text("Share usage data to improve offers")
                        }
                        Text("Manage", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.clickable { manage = true }.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        Consent.save(this, true)
                        SdkSimulator.touch(SdkSimulator.ADS, SdkSimulator.CLEVERTAP)
                        onDone()
                    }) { Text("Accept & Continue") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Privacy preferences") },
                text = { Text("Personalised offers, analytics and advertising partners are enabled to give you the best experience.") },
                confirmButton = { Button(onClick = { Consent.save(this, true); onDone() }) { Text("Save") } },
                dismissButton = {
                    TextButton(onClick = {
                        Consent.save(this, false)
                        // The bug X-Ray catches: refusal is stored but the SDKs keep running.
                        SdkSimulator.touch(SdkSimulator.FACEBOOK, SdkSimulator.FIREBASE_ANALYTICS, SdkSimulator.APPSFLYER)
                        onDone()
                    }) { Text("Reject all", fontSize = 12.sp) }
                },
            )
        }
    }

    @Composable
    private fun FixedConsent(onDone: () -> Unit) {
        var hindi by remember { mutableStateOf(false) }
        var analytics by remember { mutableStateOf(false) }
        var offers by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (hindi) "आपकी गोपनीयता के विकल्प" else "Your privacy choices") },
            text = {
                Column {
                    Text(
                        if (hindi) "हम आपका ईमेल, फ़ोन नंबर और ऑर्डर इतिहास सिर्फ़ डिलीवरी के लिए लेते हैं। नीचे दिए विकल्प वैकल्पिक हैं।"
                        else "We collect your email, phone number and order history to deliver orders. The choices below are optional.",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hindi) "ऐप सुधार के लिए एनालिटिक्स" else "Analytics to improve the app", Modifier.weight(1f))
                        Switch(checked = analytics, onCheckedChange = { analytics = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hindi) "निजी ऑफ़र" else "Personalised offers", Modifier.weight(1f))
                        Switch(checked = offers, onCheckedChange = { offers = it })
                    }
                    Text(
                        if (hindi) "आप सेटिंग्स › गोपनीयता में कभी भी सहमति वापस ले सकते हैं।"
                        else "You can withdraw consent anytime in Settings › Privacy, or complain to the Data Protection Board.",
                        fontSize = 12.sp,
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("English", Modifier.clickable { hindi = false }, fontWeight = if (!hindi) FontWeight.Bold else null)
                        Text("हिन्दी", Modifier.clickable { hindi = true }, fontWeight = if (hindi) FontWeight.Bold else null)
                        Text("ಕನ್ನಡ")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    Consent.save(this, true)
                    if (analytics) SdkSimulator.touch(SdkSimulator.FIREBASE_ANALYTICS)
                    if (offers) SdkSimulator.touch(SdkSimulator.FACEBOOK)
                    onDone()
                }) { Text(if (hindi) "स्वीकार करें" else "Accept") }
            },
            dismissButton = {
                OutlinedButton(onClick = { Consent.save(this, false); onDone() }) { Text(if (hindi) "अस्वीकार करें" else "Reject") }
            },
        )
    }

    @Composable
    private fun Shop(onPrivacy: () -> Unit) {
        val products = listOf("Cotton kurta" to 799, "Steel bottle" to 349, "Wireless earbuds" to 1499, "Notebook pack" to 199, "Desk lamp" to 899, "Backpack" to 1199)
        Column(Modifier.fillMaxSize().background(Color(0xFFF6F4FB)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(getString(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { Consent.withdraw(this@ShopActivity); onPrivacy() }) { Text("Privacy") }
            }
            Text("Trending today", color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(products) { (name, price) ->
                    Column(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFE6E0F8), RoundedCornerShape(8.dp)))
                        Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Text("₹$price", color = Color(0xFF5B3CC4))
                    }
                }
            }
        }
    }
}
