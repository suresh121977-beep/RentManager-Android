@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Voice
import java.time.LocalDate

/**
 * Quick payment flow. With a Voice it speaks the bill amount, waits for the received amount,
 * asks to confirm, speaks "received done" + balance, then asks: continue with another bill or stop.
 */
class PayFlow(private val vm: AppVM, val voice: Voice?, private val billOf: (String) -> Bill?, private val payOf: (String) -> Payment?) {
    var selected by mutableStateOf<String?>(null)
    var status by mutableStateOf("Pick a group, then a bill number")
    var stage by mutableStateOf("idle")   // idle | amount | confirm | next
    var amount by mutableStateOf(0.0)
    var mode by mutableStateOf("CASH")
    private var tries = 0

    fun bal(n: String): Double { val b = billOf(n) ?: return 0.0; return balanceOf(b, payOf(n)) }
    private fun rs(d: Double) = "${d.toInt()} rupees"

    fun pick(n: String) {
        val b = billOf(n) ?: return
        selected = n; stage = "idle"; tries = 0
        status = "$n • Bill ${money(b.billAmount)} • Balance ${money(bal(n))}"
        val v = voice
        if (v != null) {
            stage = "amount"
            v.say("$n. Bill amount ${rs(b.billAmount)}. Balance ${rs(bal(n))}. Say the received amount.") { listenAmount() }
        }
    }

    private fun retry(err: String, again: () -> Unit) {
        tries++
        if (tries <= 2) { status = err; voice?.say("I did not catch that. Please say again.") { again() } }
        else { status = "Paused – tap 🎤 Listen"; stage = "idle"; tries = 0 }
    }

    fun resume() { if (selected != null) listenAmount() else status = "Pick a bill number first" }

    fun listenAmount() {
        val n = selected ?: return; val v = voice ?: return
        stage = "amount"; status = "🎙 $n – say received amount…"
        v.listen(onText = { h -> onAmount(n, h) }, onError = { e -> retry(e) { listenAmount() } })
    }

    private fun onAmount(n: String, heard: String) {
        val v = voice ?: return; val h = heard.lowercase(); tries = 0
        when {
            Regex("\\b(stop|close|cancel)\\b").containsMatchIn(h) -> stop()
            Regex("\\b(later|visit|visited|tomorrow)\\b").containsMatchIn(h) -> {
                vm.moveVisited(n, "Asked to pay later"); status = "$n moved to Visited"
                v.say("$n moved to visited folder.") { askNext() }
            }
            else -> {
                val full = Regex("\\b(full|all)\\b").containsMatchIn(h)
                val a = if (full) bal(n) else spokenNumber(heard)
                if (a == null || a <= 0.0) { retry("Amount not understood") { listenAmount() } }
                else {
                    amount = a
                    mode = if (Regex("gpay|g pay|google|online|upi|phone ?pe").containsMatchIn(h)) "GPAY" else "CASH"
                    stage = "confirm"; status = "Confirm ${money(a)} via $mode for $n?"
                    v.say("Received ${rs(a)} from $n by $mode. Say confirm or cancel.") { listenConfirm() }
                }
            }
        }
    }

    fun listenConfirm() {
        val n = selected ?: return; val v = voice ?: return
        stage = "confirm"
        v.listen(onText = { h0 ->
            val h = h0.lowercase(); tries = 0
            when {
                Regex("\\b(confirm|yes|ok|okay|correct|sure|done|haan)\\b").containsMatchIn(h) -> confirmPay()
                Regex("\\b(cancel|no|wrong|again)\\b").containsMatchIn(h) -> { v.say("Cancelled. Say the received amount again.") { listenAmount() } }
                Regex("\\b(stop|close)\\b").containsMatchIn(h) -> stop()
                else -> retry("Say confirm or cancel") { listenConfirm() }
            }
        }, onError = { e -> retry(e) { listenConfirm() } })
    }

    fun confirmPay() {
        val n = selected ?: return
        voice?.cancel()
        val before = bal(n); val a = amount
        vm.pay(n, a, mode)
        val nb = maxOf(0.0, before - a)
        status = "✓ ${money(a)} received for $n • Balance ${money(nb)}"
        stage = "idle"
        val v = voice
        if (v != null) {
            v.say("Received ${rs(a)} done." + (if (nb > 0) " Balance ${rs(nb)}." else " Fully paid.")) { askNext() }
        } else { selected = null }
    }

    fun askNext() {
        val v = voice
        if (v == null) { selected = null; return }
        v.say("Say continue for another bill, or stop.") {
            stage = "next"; status = "Say continue, a bill number, or stop"
            v.listen(onText = { h0 ->
                val h = h0.lowercase(); tries = 0
                val bn = parseVoice(h0).billNo
                when {
                    Regex("\\b(stop|close|finish|no)\\b").containsMatchIn(h) -> stop()
                    bn != null && billOf(bn) != null -> pick(bn)
                    Regex("\\b(continue|next|other|another|yes|ok)\\b").containsMatchIn(h) -> {
                        selected = null; stage = "idle"; status = "Pick the next bill number"; v.say("Pick the next bill number.")
                    }
                    else -> retry("Say continue or stop") { askNext() }
                }
            }, onError = { e -> retry(e) { askNext() } })
        }
    }

    fun stop() {
        voice?.cancel(); stage = "idle"; selected = null
        status = "Stopped. Pick a bill number to start again"; voice?.say("Stopped.")
    }

    fun visit(reason: String) {
        val n = selected ?: return
        voice?.cancel(); vm.moveVisited(n, reason); selected = null; stage = "idle"; status = "$n moved to Visited folder"
    }
}

@Composable fun SelectedPayCard(vm: AppVM, flow: PayFlow, speak: Boolean) {
    val n = flow.selected
    if (n == null) { Lbl(flow.status); return }
    var amt by remember(n) { mutableStateOf("%.0f".format(flow.bal(n))) }
    var reason by remember(n) { mutableStateOf("") }
    Panel {
        Text(n, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(flow.status, fontSize = 13.sp)
        NumField("Enter payment amount ₹", amt, { amt = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("💳 GPay", Modifier.weight(1f)) { num(amt)?.takeIf { it > 0 }?.let { flow.amount = it; flow.mode = "GPAY"; flow.confirmPay() } }
            BtnO("💵 Cash", Modifier.weight(1f)) { num(amt)?.takeIf { it > 0 }?.let { flow.amount = it; flow.mode = "CASH"; flow.confirmPay() } }
        }
        if (speak) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("🎤 Listen", Modifier.weight(1f)) { flow.resume() }
            if (flow.stage == "confirm") Btn("✓ Confirm", Modifier.weight(1f)) { flow.confirmPay() }
            BtnO("■ Stop", Modifier.weight(1f)) { flow.stop() }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TxtField("Reason (optional)", reason, { reason = it }, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); BtnO("➜ Visited") { flow.visit(reason) }
        }
    }
}

@Composable fun QuickPayPanel(vm: AppVM, speak: Boolean) {
    val ctx = LocalContext.current
    val bills = monthBills(vm); val pays = monthPays(vm)
    val visited by vm.visited.collectAsState(); val m by vm.month.collectAsState()
    val today = LocalDate.now().toString()
    val billsS by rememberUpdatedState(bills); val paysS by rememberUpdatedState(pays)
    val voice = remember(speak) { if (speak) Voice(ctx) else null }
    DisposableEffect(voice) { onDispose { voice?.shutdown() } }
    val flow = remember(voice) { PayFlow(vm, voice, { n -> billsS.firstOrNull { it.billNo == n } }, { n -> paysS[n] }) }

    var tab by remember { mutableStateOf("Quick") }
    var group by remember { mutableStateOf("") }
    var manualNo by remember { mutableStateOf("") }; var manualAmt by remember { mutableStateOf("") }
    var pendingPick by remember { mutableStateOf("") }

    val visitedNow = visited.filter { it.month == m && it.visitedDate == today }.associateBy { it.billNo }
    val due = bills.filter { balanceOf(it, pays[it.billNo]) > 0 }
    val pending = due.filter { it.billNo !in visitedNow }
    val inFolder = due.filter { it.billNo in visitedNow }

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && pendingPick.isNotEmpty()) flow.pick(pendingPick)
        pendingPick = ""
    }
    fun choose(n: String) {
        if (voice != null && !voice.hasMic()) { pendingPick = n; micPerm.launch(Manifest.permission.RECORD_AUDIO) } else flow.pick(n)
    }

    Column(Modifier.fillMaxSize()) {
        val folderLabel = "Visited (${inFolder.size})"
        Chips(listOf("Quick", "Table", "Manual", folderLabel), if (tab == "Visited") folderLabel else tab) { tab = if (it.startsWith("Visited")) "Visited" else it }
        SelectedPayCard(vm, flow, speak)
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Quick" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    TxtField("Enter group id (N, NS …)", group, { group = it.uppercase() }, Modifier.fillMaxWidth())
                    GroupChips(pending.map { groupLetters(it.billNo) }.distinct().sorted(), group) { group = it }
                    val shown = pending.filter { group.isEmpty() || billMatch(it.billNo, group) }
                    Lbl("${shown.size} bills with balance • tap a number" + if (speak) " (it will speak the amount)" else "")
                    NumberGrid(shown.map { it.billNo to statusColor(statusOf(it, pays[it.billNo])) }) { choose(it) }
                }
                "Table" -> LazyColumn {
                    item { Row(Modifier.fillMaxWidth().padding(4.dp)) { Lbl("NO"); Spacer(Modifier.weight(1f)); Lbl("BILL   RECEIVED   BALANCE") } }
                    items(pending) { b ->
                        val p = pays[b.billNo]
                        Panel { Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.billNo, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold)
                            Text("${money(b.billAmount)}  ${money(p?.received ?: 0.0)}  ", fontSize = 12.sp)
                            Text(money(balanceOf(b, p)), color = Red, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp)); Button({ choose(b.billNo) }) { Text("Pay") } } }
                    }
                }
                "Manual" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Panel {
                        TxtField("Bill number (N1)", manualNo, { manualNo = it.uppercase() }, Modifier.fillMaxWidth())
                        NumField("Amount ₹", manualAmt, { manualAmt = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
                        val b = bills.firstOrNull { it.billNo == norm(manualNo) }
                        if (b != null) Lbl("Bill ${money(b.billAmount)} • Balance ${money(balanceOf(b, pays[b.billNo]))}")
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Btn("💳 GPay", Modifier.weight(1f)) { val a = num(manualAmt); if (b != null && a != null && a > 0) { vm.pay(b.billNo, a, "GPAY"); manualAmt = "" } }
                            BtnO("💵 Cash", Modifier.weight(1f)) { val a = num(manualAmt); if (b != null && a != null && a > 0) { vm.pay(b.billNo, a, "CASH"); manualAmt = "" } }
                        }
                    }
                }
                else -> LazyColumn {
                    item { Lbl("Visited bills come back to Pending tomorrow. You can still pay them from here.") }
                    if (inFolder.isEmpty()) item { Empty("Visited folder is empty.") }
                    items(inFolder) { b ->
                        Panel { Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.billNo, fontWeight = FontWeight.ExtraBold)
                                Lbl("Balance ${money(balanceOf(b, pays[b.billNo]))} • ${visitedNow[b.billNo]?.reason?.ifBlank { "-" } ?: "-"}")
                            }
                            TextButton({ vm.unvisit(b.billNo) }) { Text("↩") }
                            Button({ tab = "Quick"; choose(b.billNo) }) { Text("Pay") } } }
                    }
                }
            }
        }
    }
}

@Composable fun VoiceScreen(vm: AppVM) {
    var tab by remember { mutableStateOf("Quick Entry") }
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("Quick Entry", "Say Command"), tab) { tab = it }
        Box(Modifier.weight(1f)) { if (tab == "Quick Entry") QuickPayPanel(vm, true) else VoiceCommandPanel(vm) }
    }
}
