package com.dm.labs.wifi.captive

import android.content.Context
import android.net.wifi.WifiManager
import android.webkit.JavascriptInterface
import com.dm.labs.wifi.data.CaptivePortalSolutionRepository
import com.dm.labs.wifi.log.ScanLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URL

/**
 * Records user interactions in the captive portal WebView via a JavaScript bridge.
 * The injected JS captures clicks, form submissions, inputs, and navigations,
 * then calls back to this object through @JavascriptInterface methods.
 */
class CaptivePortalRecorder(
    private val repository: CaptivePortalSolutionRepository,
    private val scope: CoroutineScope,
    private val context: Context? = null
) {
    private data class PendingStep(
        val order: Int,
        val type: String,
        val url: String = "",
        val cssSelector: String = "",
        val inputValue: String = "",
        val elementId: String = "",
        val elementName: String = "",
        val elementType: String = "",
        val formData: String = ""
    )

    private var solutionId: Long = -1L
    private var stepCounter = 0
    private val pendingSteps = mutableListOf<PendingStep>()
    var isRecording = false
        private set

    fun startRecording(ssid: String, portalUrl: String) {
        if (isRecording) return
        isRecording = true
        stepCounter = 0
        solutionId = -1L
        synchronized(pendingSteps) { pendingSteps.clear() }

        scope.launch {
            try {
                // Extract BSSID from currently connected WiFi network
                val bssid = getCurrentBssid()
                // Extract portal host from URL
                val portalHost = normalizeHost(portalUrl)

                solutionId = repository.createSolution(
                    ssid = ssid,
                    portalUrl = portalUrl,
                    description = "Recorded on ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                )

                // Update solution with BSSID and portalHost if we were able to extract them
                if (bssid != null || portalHost != null) {
                    repository.updateSolutionBssidAndHost(solutionId, bssid, portalHost)
                    ScanLogManager.log("Recording for SSID=$ssid, BSSID=$bssid, portalHost=$portalHost")
                }

                flushPendingSteps()
                ScanLogManager.log("Started recording captive portal solution for '$ssid'")
            } catch (e: Exception) {
                isRecording = false
                synchronized(pendingSteps) { pendingSteps.clear() }
                ScanLogManager.log("Failed to start recording for '$ssid': ${e.message}")
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        scope.launch {
            if (solutionId > 0) {
                flushPendingSteps()
                repository.finishRecording(solutionId)
                ScanLogManager.log("Finished recording solution #$solutionId with $stepCounter steps")
            }
            synchronized(pendingSteps) { pendingSteps.clear() }
        }
    }

    @JavascriptInterface
    fun onNavigation(url: String) {
        if (!isRecording) return
        enqueueStep(PendingStep(order = nextOrder(), type = "NAVIGATION", url = url))
        ScanLogManager.log("Recorded NAVIGATION: $url")
    }

    @JavascriptInterface
    fun onClick(cssSelector: String, url: String, elementId: String, elementName: String, elementType: String) {
        if (!isRecording) return
        enqueueStep(
            PendingStep(
                order = nextOrder(),
                type = "CLICK",
                url = url,
                cssSelector = cssSelector,
                elementId = elementId,
                elementName = elementName,
                elementType = elementType
            )
        )
        ScanLogManager.log("Recorded CLICK: $cssSelector")
    }

    @JavascriptInterface
    fun onInput(cssSelector: String, value: String, elementId: String, elementName: String, elementType: String) {
        if (!isRecording) return
        enqueueStep(
            PendingStep(
                order = nextOrder(),
                type = "INPUT",
                cssSelector = cssSelector,
                inputValue = value,
                elementId = elementId,
                elementName = elementName,
                elementType = elementType
            )
        )
        ScanLogManager.log("Recorded INPUT: $cssSelector = $value")
    }

    @JavascriptInterface
    fun onFormSubmit(url: String, formData: String, cssSelector: String) {
        if (!isRecording) return
        enqueueStep(
            PendingStep(
                order = nextOrder(),
                type = "FORM_SUBMIT",
                url = url,
                cssSelector = cssSelector,
                formData = formData
            )
        )
        ScanLogManager.log("Recorded FORM_SUBMIT: $url")
    }

    private fun nextOrder(): Int = stepCounter++

    private fun enqueueStep(step: PendingStep) {
        val currentSolutionId = solutionId
        if (currentSolutionId > 0) {
            scope.launch { persistStep(currentSolutionId, step) }
        } else {
            synchronized(pendingSteps) { pendingSteps.add(step) }
        }
    }

    private suspend fun flushPendingSteps() {
        val currentSolutionId = solutionId
        if (currentSolutionId <= 0) return
        val toPersist = synchronized(pendingSteps) {
            if (pendingSteps.isEmpty()) return
            val copy = pendingSteps.toList()
            pendingSteps.clear()
            copy
        }
        for (step in toPersist) {
            persistStep(currentSolutionId, step)
        }
    }

    private suspend fun persistStep(solutionId: Long, step: PendingStep) {
        repository.addStep(
            solutionId = solutionId,
            stepOrder = step.order,
            type = step.type,
            url = step.url,
            cssSelector = step.cssSelector,
            inputValue = step.inputValue,
            elementId = step.elementId,
            elementName = step.elementName,
            elementType = step.elementType,
            formData = step.formData
        )
    }

    /**
     * Extracts the currently connected WiFi network's BSSID.
     * Returns null if unable to determine or permissions missing.
     */
    private fun getCurrentBssid(): String? {
        return try {
            if (context == null) return null
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.bssid
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalizes a URL/host to extract the hostname (e.g., "example.com" from "https://example.com/path").
     */
    private fun normalizeHost(value: String): String? {
        return try {
            val text = value.trim()
            if (text.isEmpty()) return null
            val candidate = if (text.contains("://")) text else "https://$text"
            URL(candidate).host?.lowercase()?.removePrefix("www.")?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Returns JavaScript to inject into the WebView that captures user interactions
         * and communicates them back via the AndroidRecorder bridge.
         */
        fun getRecordingJs(): String = """
        (function() {
            if (window.__recorderInjected) return;
            window.__recorderInjected = true;

            function getCssSelector(el) {
                if (!el || el === document) return '';
                if (el.id) return '#' + el.id;
                if (el.name) return el.tagName.toLowerCase() + '[name="' + el.name + '"]';
                var path = [];
                while (el && el !== document) {
                    var tag = el.tagName.toLowerCase();
                    if (el.id) { path.unshift('#' + el.id); break; }
                    var sibling = el;
                    var nth = 1;
                    while (sibling = sibling.previousElementSibling) {
                        if (sibling.tagName === el.tagName) nth++;
                    }
                    var selector = tag;
                    if (el.className && typeof el.className === 'string') {
                        selector += '.' + el.className.trim().split(/\s+/).join('.');
                    }
                    selector += ':nth-of-type(' + nth + ')';
                    path.unshift(selector);
                    el = el.parentElement;
                }
                return path.join(' > ');
            }

            // Capture clicks
            document.addEventListener('click', function(e) {
                try {
                    var el = e.target;
                    var selector = getCssSelector(el);
                    var url = window.location.href;
                    var elId = el.id || '';
                    var elName = el.name || '';
                    var elType = el.type || el.tagName.toLowerCase();
                    window.AndroidRecorder.onClick(selector, url, elId, elName, elType);
                } catch(ex) {}
            }, true);

            // Capture input changes (debounced)
            var inputTimers = {};
            document.addEventListener('change', function(e) {
                try {
                    var el = e.target;
                    if (el.tagName === 'INPUT' || el.tagName === 'SELECT' || el.tagName === 'TEXTAREA') {
                        var selector = getCssSelector(el);
                        var value = el.type === 'password' ? '***' : (el.value || '');
                        if (el.type === 'checkbox' || el.type === 'radio') {
                            value = el.checked ? 'checked' : 'unchecked';
                        }
                        var elId = el.id || '';
                        var elName = el.name || '';
                        var elType = el.type || el.tagName.toLowerCase();
                        window.AndroidRecorder.onInput(selector, value, elId, elName, elType);
                    }
                } catch(ex) {}
            }, true);

            // Capture form submissions
            document.addEventListener('submit', function(e) {
                try {
                    var form = e.target;
                    var url = form.action || window.location.href;
                    var selector = getCssSelector(form);
                    var data = {};
                    var inputs = form.querySelectorAll('input, select, textarea');
                    inputs.forEach(function(inp) {
                        var name = inp.name || inp.id || '';
                        if (name && inp.type !== 'password') {
                            if (inp.type === 'checkbox' || inp.type === 'radio') {
                                data[name] = inp.checked ? 'checked' : 'unchecked';
                            } else {
                                data[name] = inp.value || '';
                            }
                        }
                    });
                    window.AndroidRecorder.onFormSubmit(url, JSON.stringify(data), selector);
                } catch(ex) {}
            }, true);
        })();
        """.trimIndent()

        /**
         * Returns JavaScript to replay recorded steps in the WebView.
         */
        fun getReplayJs(steps: List<com.dm.labs.wifi.data.CaptivePortalStepEntity>): String {
            val sb = StringBuilder()
            sb.append("(function() {\n")
            sb.append("  var steps = [];\n")
            for (step in steps) {
                when (step.type) {
                    "CLICK" -> {
                        val selector = step.cssSelector.replace("'", "\\'")
                        val elId = step.elementId.replace("'", "\\'")
                        val elName = step.elementName.replace("'", "\\'")
                        sb.append("  steps.push(function() {\n")
                        sb.append("    var el = null;\n")
                        if (elId.isNotEmpty()) {
                            sb.append("    el = document.getElementById('$elId');\n")
                        }
                        if (elName.isNotEmpty()) {
                            sb.append("    if (!el) el = document.querySelector('[name=\"$elName\"]');\n")
                        }
                        if (selector.isNotEmpty()) {
                            sb.append("    if (!el) try { el = document.querySelector('$selector'); } catch(e) {}\n")
                        }
                        sb.append("    if (el) { el.click(); return true; }\n")
                        sb.append("    return false;\n")
                        sb.append("  });\n")
                    }
                    "INPUT" -> {
                        val selector = step.cssSelector.replace("'", "\\'")
                        val value = step.inputValue.replace("'", "\\'")
                        val elId = step.elementId.replace("'", "\\'")
                        val elName = step.elementName.replace("'", "\\'")
                        sb.append("  steps.push(function() {\n")
                        sb.append("    var el = null;\n")
                        if (elId.isNotEmpty()) {
                            sb.append("    el = document.getElementById('$elId');\n")
                        }
                        if (elName.isNotEmpty()) {
                            sb.append("    if (!el) el = document.querySelector('[name=\"$elName\"]');\n")
                        }
                        if (selector.isNotEmpty()) {
                            sb.append("    if (!el) try { el = document.querySelector('$selector'); } catch(e) {}\n")
                        }
                        sb.append("    if (el) {\n")
                        if (step.elementType == "checkbox" || step.elementType == "radio") {
                            sb.append("      el.checked = ${value == "checked"};\n")
                            sb.append("      el.dispatchEvent(new Event('change', {bubbles:true}));\n")
                        } else {
                            sb.append("      el.value = '$value';\n")
                            sb.append("      el.dispatchEvent(new Event('input', {bubbles:true}));\n")
                            sb.append("      el.dispatchEvent(new Event('change', {bubbles:true}));\n")
                        }
                        sb.append("      return true;\n")
                        sb.append("    }\n")
                        sb.append("    return false;\n")
                        sb.append("  });\n")
                    }
                    "FORM_SUBMIT" -> {
                        val selector = step.cssSelector.replace("'", "\\'")
                        sb.append("  steps.push(function() {\n")
                        if (selector.isNotEmpty()) {
                            sb.append("    var form = null;\n")
                            sb.append("    try { form = document.querySelector('$selector'); } catch(e) {}\n")
                            sb.append("    if (!form) form = document.querySelector('form');\n")
                        } else {
                            sb.append("    var form = document.querySelector('form');\n")
                        }
                        sb.append("    if (form) { form.submit(); return true; }\n")
                        sb.append("    return false;\n")
                        sb.append("  });\n")
                    }
                }
            }
            sb.append("  var idx = 0;\n")
            sb.append("  function runNext() {\n")
            sb.append("    if (idx >= steps.length) return;\n")
            sb.append("    steps[idx]();\n")
            sb.append("    idx++;\n")
            sb.append("    if (idx < steps.length) setTimeout(runNext, 500);\n")
            sb.append("  }\n")
            sb.append("  runNext();\n")
            sb.append("})();\n")
            return sb.toString()
        }
    }
}

