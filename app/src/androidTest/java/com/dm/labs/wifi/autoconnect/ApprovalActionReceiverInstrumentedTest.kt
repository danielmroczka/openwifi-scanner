package com.dm.labs.wifi.autoconnect

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dm.labs.wifi.approval.NetworkApprovalManager
import com.dm.labs.wifi.approval.PendingNetworkApproval
import com.dm.labs.wifi.approval.UserNetworkDecision
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalActionReceiverInstrumentedTest {

    private val receiver = ApprovalActionReceiver()

    @Before
    fun setUp() {
        NetworkApprovalManager.clear()
    }

    @Test
    fun notificationActionSubmitsWhitelistDecision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Cafe", "aa:bb:cc:00:00:41", -45)
        )

        val waitingDecision = async {
            NetworkApprovalManager.awaitDecision(timeoutMs = 1_000)
        }

        val intent = Intent(ApprovalActionReceiver.ACTION_APPROVAL_DECISION).apply {
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, UserNetworkDecision.WHITELIST.name)
        }
        receiver.onReceive(context, intent)

        assertEquals(UserNetworkDecision.WHITELIST, waitingDecision.await())
        assertNull(NetworkApprovalManager.pending.value)
    }

    @Test
    fun invalidActionIsIgnored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Cafe", "aa:bb:cc:00:00:42", -50)
        )

        val intent = Intent("com.dm.labs.wifi.IGNORED").apply {
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, UserNetworkDecision.BLACKLIST.name)
        }
        receiver.onReceive(context, intent)

        assertNotNull(NetworkApprovalManager.pending.value)
    }
}

