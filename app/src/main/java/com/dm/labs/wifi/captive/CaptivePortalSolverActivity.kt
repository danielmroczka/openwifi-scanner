package com.dm.labs.wifi.captive

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dm.labs.wifi.data.AppDatabase
import com.dm.labs.wifi.data.CaptivePortalStepEntity
import com.dm.labs.wifi.data.RoomCaptivePortalSolutionRepository
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.ui.theme.WIFITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptivePortalSolverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ssid = intent.getStringExtra(EXTRA_SSID) ?: "Unknown"
        val replaySolutionId = intent.getLongExtra(EXTRA_REPLAY_SOLUTION_ID, -1L)

        setContent {
            WIFITheme {
                CaptivePortalSolverScreen(
                    ssid = ssid,
                    replaySolutionId = replaySolutionId,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val PORTAL_CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"
        const val EXTRA_SSID = "extra_ssid"
        const val EXTRA_REPLAY_SOLUTION_ID = "extra_replay_solution_id"

        fun launch(context: Context, ssid: String = "Unknown") {
            context.startActivity(
                Intent(context, CaptivePortalSolverActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_SSID, ssid)
                }
            )
        }

        fun launchReplay(context: Context, ssid: String, solutionId: Long) {
            context.startActivity(
                Intent(context, CaptivePortalSolverActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_SSID, ssid)
                    putExtra(EXTRA_REPLAY_SOLUTION_ID, solutionId)
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun CaptivePortalSolverScreen(
    ssid: String,
    replaySolutionId: Long,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Loading portal page…") }
    val scope = rememberCoroutineScope()

    val db = remember { AppDatabase.getDatabase(context) }
    val solutionRepo = remember { RoomCaptivePortalSolutionRepository(db.captivePortalSolutionDao()) }
    val recorder = remember { CaptivePortalRecorder(solutionRepo, scope) }

    var isRecording by remember { mutableStateOf(false) }
    val isReplayMode = replaySolutionId > 0
    var replaySteps by remember { mutableStateOf<List<CaptivePortalStepEntity>>(emptyList()) }

    // Load replay steps if in replay mode
    LaunchedEffect(replaySolutionId) {
        if (isReplayMode) {
            val data = withContext(Dispatchers.IO) {
                solutionRepo.getSolutionWithSteps(replaySolutionId)
            }
            replaySteps = data?.steps ?: emptyList()
            status = "Replay mode: ${replaySteps.size} steps loaded"
        }
    }

    // Monitor for internet connectivity
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        while (isActive) {
            delay(2_000)
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                ScanLogManager.log("Captive portal resolved via solver.")
                if (isRecording) {
                    recorder.stopRecording()
                    isRecording = false
                }
                status = "Internet connected! Closing…"
                delay(1_000)
                onClose()
                return@LaunchedEffect
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Captive Portal Solver", style = MaterialTheme.typography.titleMedium)
                        Text("Network: $ssid", style = MaterialTheme.typography.bodySmall)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = {
                        if (isRecording) {
                            recorder.stopRecording()
                            isRecording = false
                        }
                        onClose()
                    }) { Text("Close") }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isReplayMode) {
                        Button(
                            onClick = {
                                if (isRecording) {
                                    recorder.stopRecording()
                                    isRecording = false
                                    status = "Recording saved!"
                                } else {
                                    recorder.startRecording(ssid, CaptivePortalSolverActivity.PORTAL_CHECK_URL)
                                    isRecording = true
                                    status = "\uD83D\uDD34 Recording steps…"
                                }
                            },
                            colors = if (isRecording) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            Text(if (isRecording) "\u23F9 Stop Recording" else "\u23FA Record Steps")
                        }
                    }
                }
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true

                    // Add the recorder bridge. Wrap the recorder in a simple JavaScript interface
                    // object so lint/static analysis can see @JavascriptInterface-annotated methods.
                    val androidRecorderBridge = object {
                        @android.webkit.JavascriptInterface
                        fun onNavigation(url: String) {
                            recorder.onNavigation(url)
                        }

                        @android.webkit.JavascriptInterface
                        fun onClick(cssSelector: String, url: String, elementId: String, elementName: String, elementType: String) {
                            recorder.onClick(cssSelector, url, elementId, elementName, elementType)
                        }

                        @android.webkit.JavascriptInterface
                        fun onInput(cssSelector: String, value: String, elementId: String, elementName: String, elementType: String) {
                            recorder.onInput(cssSelector, value, elementId, elementName, elementType)
                        }

                        @android.webkit.JavascriptInterface
                        fun onFormSubmit(url: String, formData: String, cssSelector: String) {
                            recorder.onFormSubmit(url, formData, cssSelector)
                        }
                    }

                    addJavascriptInterface(androidRecorderBridge, "AndroidRecorder")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)

                            view?.let { wv ->
                                if (isReplayMode && replaySteps.isNotEmpty()) {
                                    // Replay mode: inject replay script
                                    status = "Replaying ${replaySteps.size} steps…"
                                    val replayJs = CaptivePortalRecorder.getReplayJs(replaySteps)
                                    wv.evaluateJavascript(replayJs, null)
                                } else {
                                    // Record navigation if recording
                                    if (isRecording && url != null) {
                                        recorder.onNavigation(url)
                                    }

                                    // Inject recording JS
                                    wv.evaluateJavascript(
                                        CaptivePortalRecorder.getRecordingJs(), null
                                    )

                                    // Also try auto-solve
                                    status = if (isRecording) "\uD83D\uDD34 Recording… Auto-solving…" else "Auto-solving…"
                                    injectSolverJs(wv)
                                    scope.launch {
                                        delay(3_000)
                                        injectSolverJs(wv)
                                    }
                                }
                            }
                        }
                    }

                    loadUrl(CaptivePortalSolverActivity.PORTAL_CHECK_URL)
                }
            }
        )
    }
}

private fun injectSolverJs(webView: WebView) {
    @Suppress("MaxLineLength")
    val js = """
    (function() {
        document.querySelectorAll('input[type="checkbox"]').forEach(function(cb) {
            if (!cb.checked) {
                cb.checked = true;
                cb.dispatchEvent(new Event('change', {bubbles:true}));
                cb.dispatchEvent(new Event('click', {bubbles:true}));
            }
        });
        var radioGroups = {};
        document.querySelectorAll('input[type="radio"]').forEach(function(r) {
            if (!radioGroups[r.name]) {
                r.checked = true;
                r.dispatchEvent(new Event('change', {bubbles:true}));
                radioGroups[r.name] = true;
            }
        });
        var keywords = [
            'accept','agree','connect','continue','submit','login','enter','join',
            'start','access','proceed','confirm','free','surf','go online',
            'get online','i agree','log in','sign in',
            'weiter','verbinden','akzeptieren','zustimmen','anmelden','gratis',
            'kostenlos','einloggen','bestätigen',
            'accepter','continuer','connecter','gratuit',
            'accetta','connetti','continua','gratuito',
            'aceptar','conectar','continuar'
        ];
        var clickables = document.querySelectorAll(
            'button, input[type="submit"], input[type="button"], a.btn, a.button, ' +
            '[role="button"], .btn, .button, [class*="accept"], [class*="agree"], ' +
            '[class*="submit"], [class*="connect"], [id*="accept"], [id*="agree"]'
        );
        var clicked = false;
        clickables.forEach(function(el) {
            if (clicked) return;
            var text = (el.textContent || el.value || el.getAttribute('aria-label') || '').toLowerCase().trim();
            for (var i = 0; i < keywords.length; i++) {
                if (text.indexOf(keywords[i]) !== -1) {
                    el.click();
                    clicked = true;
                    return;
                }
            }
        });
        if (!clicked) {
            var fallbacks = [
                'button[type="submit"]', 'input[type="submit"]',
                'form button:last-of-type',
                '.accept-btn', '.agree-btn', '.connect-btn',
                '#accept', '#agree', '#connect', '#submit'
            ];
            for (var i = 0; i < fallbacks.length; i++) {
                var el = document.querySelector(fallbacks[i]);
                if (el) { el.click(); clicked = true; break; }
            }
        }
        if (!clicked) {
            var forms = document.querySelectorAll('form');
            if (forms.length > 0) forms[0].submit();
        }
    })();
    """.trimIndent()

    webView.evaluateJavascript(js, null)
}
