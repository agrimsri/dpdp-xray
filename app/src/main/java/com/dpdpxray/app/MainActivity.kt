package com.dpdpxray.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.screens.AiLabScreen
import com.dpdpxray.app.ui.screens.AppPickerScreen
import com.dpdpxray.app.ui.screens.CameraCheckScreen
import com.dpdpxray.app.ui.screens.FindingDetailScreen
import com.dpdpxray.app.ui.screens.FixesScreen
import com.dpdpxray.app.ui.screens.HomeScreen
import com.dpdpxray.app.ui.screens.LiveAuditScreen
import com.dpdpxray.app.ui.screens.ReportScreen
import com.dpdpxray.app.ui.screens.ResultsScreen
import com.dpdpxray.app.ui.screens.SettingsScreen
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.XrayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: XrayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.refreshPreflight() }
        }
        setContent {
            val settings by vm.settings.collectAsState()
            val toast by vm.toast.collectAsState()
            LaunchedEffect(toast) {
                toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show(); vm.toastShown() }
            }
            XrayTheme(stageMode = settings.stageMode) {
                Box(Modifier.fillMaxSize().background(Film.base).safeDrawingPadding()) {
                    val nav = rememberNavController()
                    val progress by vm.progress.collectAsState()
                    LaunchedEffect(Unit) {
                        // If an audit is still running when the UI comes back, show it.
                        if (progress is AuditProgress.Running) nav.navigate("live")
                    }
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                vm,
                                onAudit = { nav.navigate("pick") },
                                onOpen = { nav.navigate("results/$it") },
                                onLab = { nav.navigate("lab") },
                                onCamera = { nav.navigate("camera") },
                                onSettings = { nav.navigate("settings") },
                            )
                        }
                        composable("pick") { AppPickerScreen(vm, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("live") { popUpTo("home") } }) }
                        composable("live") {
                            LiveAuditScreen(
                                vm,
                                onDone = { id -> nav.navigate("results/$id") { popUpTo("home") } },
                                onBack = { nav.popBackStack("home", inclusive = false) },
                            )
                        }
                        composable("results/{id}") { e ->
                            val id = e.arguments?.getString("id").orEmpty()
                            ResultsScreen(
                                vm, id, onBack = { nav.popBackStack() },
                                onFinding = { nav.navigate("finding/$id/${it.name}") },
                                onFixes = { nav.navigate("fixes/$id") },
                                onReport = { nav.navigate("report/$id") },
                            )
                        }
                        composable("finding/{id}/{check}") { e ->
                            val id = e.arguments?.getString("id").orEmpty()
                            FindingDetailScreen(vm, id, e.arguments?.getString("check").orEmpty(), onBack = { nav.popBackStack() }, onFixes = { nav.navigate("fixes/$id") })
                        }
                        composable("fixes/{id}") { e -> FixesScreen(vm, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
                        composable("report/{id}") { e -> ReportScreen(vm, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
                        composable("lab") { AiLabScreen(vm, onBack = { nav.popBackStack() }) }
                        composable("camera") { CameraCheckScreen(vm, onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }
}
