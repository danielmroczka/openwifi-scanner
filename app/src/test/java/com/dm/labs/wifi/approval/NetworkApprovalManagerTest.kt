package com.dm.labs.wifi.approval

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkApprovalManagerTest {

    @Before
    fun setUp() {
        NetworkApprovalManager.clear()
    }

    @Test
    fun `submitDecision without pending request is ignored`() = runTest {
        NetworkApprovalManager.submitDecision(UserNetworkDecision.WHITELIST)

        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Cafe", "aa:bb:cc:00:00:41", -47)
        )

        val decision = NetworkApprovalManager.awaitDecision(timeoutMs = 50)
        assertEquals(UserNetworkDecision.SKIP, decision)
    }

    @Test
    fun `clear removes queued decisions from previous approval`() = runTest {
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Cafe", "aa:bb:cc:00:00:42", -50)
        )
        NetworkApprovalManager.submitDecision(UserNetworkDecision.BLACKLIST)

        NetworkApprovalManager.clear()
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Airport", "aa:bb:cc:00:00:43", -56)
        )

        val decision = NetworkApprovalManager.awaitDecision(timeoutMs = 50)
        assertEquals(UserNetworkDecision.SKIP, decision)
    }

    @Test
    fun `awaitDecision returns submitted decision for current request`() = runTest {
        NetworkApprovalManager.requestApproval(
            PendingNetworkApproval("Hotel", "aa:bb:cc:00:00:44", -52)
        )

        val waiting = async { NetworkApprovalManager.awaitDecision(timeoutMs = 1_000) }
        yield()

        NetworkApprovalManager.submitDecision(UserNetworkDecision.WHITELIST)

        assertEquals(UserNetworkDecision.WHITELIST, waiting.await())
    }
}

