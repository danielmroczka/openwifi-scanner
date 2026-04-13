package com.dm.labs.wifi.autoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dm.labs.wifi.approval.NetworkApprovalManager
import com.dm.labs.wifi.approval.UserNetworkDecision
import com.dm.labs.wifi.log.DevLog

/** Handles action buttons from the unknown-network approval notification. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_APPROVAL_DECISION) return

        val decisionRaw = intent.getStringExtra(EXTRA_DECISION) ?: return
        val decision = runCatching { UserNetworkDecision.valueOf(decisionRaw) }
            .getOrElse {
                DevLog.w("Unknown approval decision from notification: $decisionRaw")
                return
            }

        DevLog.i("Approval notification action received: $decision")
        NetworkApprovalManager.submitDecision(decision)
    }

    companion object {
        const val ACTION_APPROVAL_DECISION = "com.dm.labs.wifi.ACTION_APPROVAL_DECISION"
        const val EXTRA_DECISION = "extra_decision"
    }
}

