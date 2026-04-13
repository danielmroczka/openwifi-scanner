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
    private val interactiveLauncher: ((String) -> Unit),
    private val httpSolver: (suspend () -> Boolean)? = null,
    private val resolutionWaiter: (suspend (Long) -> Boolean)? = null
) {
    suspend fun trySolve(ssid: String?): Boolean {
        val knownSsid = ssid?.takeIf { it.isNotBlank() }
        if (knownSsid != null && solutionRepository != null) {
            val solution = solutionRepository.getLatestSolutionForSsid(knownSsid)
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
        interactiveLauncher(knownSsid ?: "Unknown")

        return waitForResolutionOrInjected(120_000L)
    }

    private suspend fun waitForResolutionOrInjected(timeoutMs: Long): Boolean {
        return resolutionWaiter?.invoke(timeoutMs) ?: waitForResolution(timeoutMs)
    }

    /* ---- HTTP-based auto-solve ---------------------------------------------------- */

    @Suppress("TooGenericExceptionCaught")
    private suspend fun trySolveViaHttp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val checkConn = (URL(CONNECTIVITY_CHECK).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", UA)
            }
            val code = checkConn.responseCode
            if (code == 204) {
                checkConn.disconnect(); return@withContext true
            }

            val portalUrl = if (code in 300..399) checkConn.getHeaderField("Location") else null
            checkConn.disconnect()
            if (portalUrl.isNullOrBlank()) return@withContext false

            val portalConn = (URL(portalUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("User-Agent", UA)
            }
            val html = portalConn.inputStream.bufferedReader().readText()
            val cookies = portalConn.headerFields["Set-Cookie"]
                ?.joinToString("; ") { it.substringBefore(";") }
            portalConn.disconnect()

            val formAction = FORM_ACTION_RE.find(html)?.groupValues?.get(1) ?: ""
            val params = INPUT_RE.findAll(html).mapNotNull { parseInput(it.value) }
                .joinToString("&")

            val submitUrl = resolveUrl(portalUrl, formAction)

            val submitConn = (URL(submitUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", UA)
                cookies?.let { setRequestProperty("Cookie", it) }
            }
            submitConn.outputStream.write(params.toByteArray())
            submitConn.responseCode
            submitConn.disconnect()

            delay(3_000)
            val verify = (URL(CONNECTIVITY_CHECK).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 5_000; readTimeout = 5_000
            }
            val ok = verify.responseCode == 204
            verify.disconnect()
            return@withContext ok
        } catch (e: Exception) {
            ScanLogManager.log("HTTP portal solve error: ${e.message}")
            return@withContext false
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
                interactiveLauncher = { ssid ->
                    CaptivePortalSolverActivity.launch(context, ssid)
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

