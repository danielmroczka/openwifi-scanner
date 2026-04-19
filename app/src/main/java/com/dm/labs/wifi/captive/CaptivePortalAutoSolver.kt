package com.dm.labs.wifi.captive

import android.content.Context
import com.dm.labs.wifi.data.CaptivePortalSolutionRepository
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Two-phase captive portal solver:
 * 1. Try to solve the portal automatically via plain HTTP (parse form, check boxes, submit).
 * 2. If that fails, launch the [CaptivePortalSolverActivity] WebView and wait for the user /
 *    injected JS to solve it.
 */
class CaptivePortalAutoSolver(
    private val checker: CaptivePortalChecker,
    private val solutionRepository: CaptivePortalSolutionRepository? = null,
    private val replayLauncher: ((String, Long) -> Unit),
    private val interactiveLauncher: ((String, Boolean) -> Unit),
    private val portalHostDetector: (suspend () -> String?)? = null,
    private val httpSolver: (suspend () -> Boolean)? = null,
    private val resolutionWaiter: (suspend (Long) -> Boolean)? = null
) {
    suspend fun trySolve(ssid: String?, bssid: String? = null): Boolean {
        val knownSsid = ssid?.takeIf { it.isNotBlank() }
        val repository = solutionRepository
        if (knownSsid != null && repository != null) {
            val portalHost = portalHostDetector?.invoke() ?: detectPortalHostFromRedirect()
            val solution = if (portalHost != null && bssid != null) {
                // Priority 1: Try SSID + BSSID + portalHost (most specific)
                repository.getLatestSolutionForSsidBssidHost(knownSsid, bssid, portalHost)
                    ?: run {
                        // Priority 2: Try SSID + portalHost (without BSSID)
                        repository.getLatestSolutionForSsidHost(knownSsid, portalHost)
                    }
            } else if (portalHost != null) {
                // Priority 2: Try SSID + portalHost
                repository.getLatestSolutionForSsidHost(knownSsid, portalHost)
            } else {
                // Priority 3: Fallback to SSID only
                repository.getLatestSolutionForSsid(knownSsid)
            }

            if (solution != null) {
                ScanLogManager.log("Found saved portal steps for $knownSsid. Trying replay…")
                replayLauncher(knownSsid, solution.id)
                if (waitForResolutionOrInjected(90_000L)) {
                    ScanLogManager.log("Portal replay succeeded on $knownSsid.")
                    return true
                }
                ScanLogManager.log("Portal replay failed on $knownSsid. Falling back to HTTP solve…")
            }
        }

        ScanLogManager.log("Attempting HTTP-based portal solve…")
        val solvedByHttp = httpSolver?.invoke() ?: trySolveViaHttp()
        if (solvedByHttp) {
            ScanLogManager.log("HTTP portal solve succeeded!")
            return true
        }

        ScanLogManager.log("HTTP solve failed. Launching interactive solver…")
        // If HTTP failed we need user interaction; for unknown/new portals we auto-record steps.
        interactiveLauncher(knownSsid ?: "Unknown", true)

        return waitForResolutionOrInjected(120_000L)
    }

    private suspend fun waitForResolutionOrInjected(timeoutMs: Long): Boolean {
        return resolutionWaiter?.invoke(timeoutMs) ?: waitForResolution(timeoutMs)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun detectPortalHostFromRedirect(): String? = withContext(Dispatchers.IO) {
        try {
            var lastException: Exception? = null

            // Retry up to 2 times for robustness
            for (attempt in 1..2) {
                try {
                    val conn = URL(CONNECTIVITY_CHECK).openConnection() as HttpURLConnection
                    try {
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 3_000
                        conn.readTimeout = 3_000
                        conn.setRequestProperty("User-Agent", UA)
                        val code = conn.responseCode
                        val location = if (code in 300..399) conn.getHeaderField("Location") else null
                        return@withContext location?.let { URL(it).host?.lowercase()?.ifBlank { null } }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < 2) {
                        delay(500)
                    }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /* ---- HTTP-based auto-solve ---------------------------------------------------- */

    @Suppress("TooGenericExceptionCaught")
    private suspend fun trySolveViaHttp(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Step 1: check connectivity, follow redirect to find portal URL
            val portalUrl: String?
            var lastException: Exception? = null

            // Retry connectivity check up to 3 times (network might be temporarily unavailable)
            var checkConn: HttpURLConnection? = null
            for (attempt in 1..3) {
                try {
                    checkConn = URL(CONNECTIVITY_CHECK).openConnection() as HttpURLConnection
                    checkConn.instanceFollowRedirects = false
                    checkConn.connectTimeout = 10_000
                    checkConn.readTimeout = 10_000
                    checkConn.setRequestProperty("User-Agent", UA)
                    val code = checkConn.responseCode
                    if (code == 204) return@withContext true
                    portalUrl = if (code in 300..399) checkConn.getHeaderField("Location") else null
                    checkConn.disconnect()
                    break  // Success, exit retry loop
                } catch (e: Exception) {
                    lastException = e
                    checkConn?.disconnect()
                    if (attempt < 3) {
                        delay(1_000)  // Wait before retry
                    }
                }
            }

            if (lastException != null && portalUrl == null) {
                // All retries failed
                ScanLogManager.log("HTTP portal solve error: connectivity check failed after 3 attempts: ${lastException.message}")
                return@withContext false
            }

            if (portalUrl.isNullOrBlank()) return@withContext false

            // Step 2: fetch portal page and extract form
            val html: String
            val cookies: String?
            val portalConn = URL(portalUrl).openConnection() as HttpURLConnection
            try {
                portalConn.connectTimeout = 10_000
                portalConn.readTimeout = 10_000
                portalConn.setRequestProperty("User-Agent", UA)
                html = portalConn.inputStream.bufferedReader().readText()
                cookies = portalConn.headerFields["Set-Cookie"]
                    ?.joinToString("; ") { it.substringBefore(";") }
            } finally {
                portalConn.disconnect()
            }

            val formAction = FORM_ACTION_RE.find(html)?.groupValues?.get(1) ?: ""
            val params = INPUT_RE.findAll(html).mapNotNull { parseInput(it.value) }
                .joinToString("&")
            val submitUrl = resolveUrl(portalUrl, formAction)

            // Step 3: submit form
            val submitConn = URL(submitUrl).openConnection() as HttpURLConnection
            try {
                submitConn.requestMethod = "POST"
                submitConn.doOutput = true
                submitConn.connectTimeout = 10_000
                submitConn.readTimeout = 10_000
                submitConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                submitConn.setRequestProperty("User-Agent", UA)
                cookies?.let { submitConn.setRequestProperty("Cookie", it) }
                submitConn.outputStream.write(params.toByteArray())
                submitConn.responseCode // consume response
            } finally {
                submitConn.disconnect()
            }

            // Step 4: verify internet is now open
            delay(3_000)
            val verify = URL(CONNECTIVITY_CHECK).openConnection() as HttpURLConnection
            try {
                verify.instanceFollowRedirects = false
                verify.connectTimeout = 5_000
                verify.readTimeout = 5_000
                verify.responseCode == 204
            } finally {
                verify.disconnect()
            }
        } catch (e: Exception) {
            ScanLogManager.log("HTTP portal solve error: ${e.message}")
            false
        }
    }

    /* ---- helpers ------------------------------------------------------------------ */

    private fun parseInput(tag: String): String? {
        val name = NAME_RE.find(tag)?.groupValues?.get(1) ?: return null
        val type = TYPE_RE.find(tag)?.groupValues?.get(1)?.lowercase() ?: "text"
        val value = VALUE_RE.find(tag)?.groupValues?.get(1) ?: ""
        if (type == "password") return null
        val v = if (type == "checkbox") "on" else value
        return "${enc(name)}=${enc(v)}"
    }

    private fun resolveUrl(base: String, action: String): String = when {
        action.startsWith("http") -> action
        action.startsWith("/") -> {
            val u = URL(base); "${u.protocol}://${u.host}$action"
        }

        action.isEmpty() -> base
        else -> "${base.substringBeforeLast("/")}/$action"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun waitForResolution(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            delay(2_000)
            if (checker.getStatus() == CaptivePortalStatus.OPEN_INTERNET) return true
        }
        return false
    }

    companion object {
        fun create(
            context: Context,
            checker: CaptivePortalChecker,
            solutionRepository: CaptivePortalSolutionRepository? = null
        ): CaptivePortalAutoSolver {
            return CaptivePortalAutoSolver(
                checker = checker,
                solutionRepository = solutionRepository,
                replayLauncher = { ssid, solutionId ->
                    CaptivePortalSolverActivity.launchReplay(context, ssid, solutionId)
                },
                interactiveLauncher = { ssid, autoRecord ->
                    CaptivePortalSolverActivity.launch(context, ssid, autoRecord)
                }
            )
        }

        private const val CONNECTIVITY_CHECK =
            "http://connectivitycheck.gstatic.com/generate_204"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val FORM_ACTION_RE =
            Regex("""<form[^>]*action=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        private val INPUT_RE =
            Regex("""<input[^>]*>""", RegexOption.IGNORE_CASE)
        private val NAME_RE = Regex("""name=["']([^"']*)["']""")
        private val TYPE_RE = Regex("""type=["']([^"']*)["']""")
        private val VALUE_RE = Regex("""value=["']([^"']*)["']""")
    }
}
