package com.dm.labs.wifi.autoconnect

import android.content.Intent
import android.content.ContextWrapper
import com.dm.labs.wifi.approval.NetworkApprovalManager
import com.dm.labs.wifi.approval.PendingNetworkApproval
import com.dm.labs.wifi.approval.UserNetworkDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalActionReceiverTest {

    private val receiver = ApprovalActionReceiver()

    @Before
    fun setUp() {
        NetworkApprovalManager.clear()
    }

    @Test
    fun `notification action submits whitelist decision`() = runTest {
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Cafe", "aa:bb:cc:00:00:31", -45)
        )

        val intent = Intent(ApprovalActionReceiver.ACTION_APPROVAL_DECISION).apply {
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, UserNetworkDecision.WHITELIST.name)
        }
        receiver.onReceive(ContextWrapper(null), intent)

        val decision = NetworkApprovalManager.awaitDecision(timeoutMs = 200)
        assertEquals(UserNetworkDecision.WHITELIST, decision)
        assertNull(NetworkApprovalManager.pending.value)
    }

    @Test
    fun `invalid action is ignored`() {
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Cafe", "aa:bb:cc:00:00:32", -50)
        )

        val intent = Intent("com.dm.labs.wifi.IGNORED").apply {
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, UserNetworkDecision.BLACKLIST.name)
        }
        receiver.onReceive(ContextWrapper(null), intent)

        assertNotNull(NetworkApprovalManager.pending.value)
    }
}


