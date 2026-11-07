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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Voice
import java.time.LocalDate

/**
 * Quick payment flow. With a Voice it speaks the bill number, amount, received and balance, waits for the received
 * amount (Pause / Resume any time), asks to confirm, says "payment done" + balance. Continue / stop is optional.
 */
class PayFlow(private val vm: AppVM, val voice: Voice?, private val billOf: (String) -> Bill?, private val payOf: (String) -> Payment?) {
    var selected by mutableStateOf<String?>(null)
    var status by mutableStateOf("Pick a group, then a bill number")
    var stage by mutableStateOf("idle")   // idle | amount | confirm | paused | next
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
            val recv = payOf(n)?.received ?: 0.0
            val msg = "$n. Bill amount ${rs(b.billAmount)}." + (if (recv > 0) " Received ${rs(recv)}." else "") +
                " Balance ${rs(bal(n))}. Say the received amount."
            v.say(msg) { listenAmount() }
        }
    }

    private fun retry(err: String, again: () -> Unit) {
        tries++
        if (tries <= 2) { status = err; voice?.say("I did not catch that. Please say again.") { again() } }
        else { status = "Paused – tap ▶ Resume"; stage = "paused"; tries = 0 }
    }

    fun pause() { voice?.cancel(); if (selected != null) { stage = "paused"; status = "⏸ Paused – tap ▶ Resume, then say the amount" } }
    fun resume() {
        if (selected == null) { status = "Pick a bill number first"; return }
        if (stage == "confirm") listenConfirm() else listenAmount()
    }

    fun listenAmount() {
        val n = selected ?: return; val v = voice ?: return
        stage = "amount"; status = "🎙 $n – say received amount…"
        v.listen(onText = { h -> onAmount(n, h) }, onError = { e -> retry(e) { listenAmount() } })
    }

    private fun onAmount(n: String, heard: String) {
        val v = voice ?: return; val h = heard.lowercase(); tries = 0
        when {
            Regex("\\b(stop|close|cancel)\\b").containsMatchIn(h) -> stop()
            Regex("\\b(pause|wait)\\b").containsMatchIn(h) -> pause()
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
        status = "✓ Payment done ${money(a)} • $n balance ${money(nb)}"
        stage = "idle"
        val v = voice
        if (v != null) {
            v.say("Payment done." + (if (nb > 0) " Balance ${rs(nb)}." else " Fully paid.")) { askNext() }
        } else { selected = null }
    }

    /** Optional: the user may say continue / stop, or simply tap another number. Nothing blocks if there is no answer. */
    fun askNext() {
        val v = voice
        if (v == null) { selected = null; return }
        stage = "next"; status = status + " • Continue or Stop (optional)"
        v.say("Say continue or stop.") {
            v.listen(onText = { h0 ->
                val h = h0.lowercase()
                val bn = parseVoice(h0).billNo
                when {
                    Regex("\\b(stop|close|finish|no)\\b").containsMatchIn(h) -> stop()
                    bn != null && billOf(bn) != null -> pick(bn)
                    else -> { selected = null; stage = "idle"; status = "Pick the next bill number"; v.say("Pick the next bill number.") }
                }
            }, onError = { _ -> selected = null; stage = "idle"; status = "Pick the next bill number" })
        }
    }

    fun continueNext() { voice?.cancel(); selected = null; stage = "idle"; status = "Pick the next bill number" }
    fun close() { voice?.cancel(); selected = null; stage = "idle"; status = "Pick a bill number" }

    fun stop() {
        voice?.cancel(); stage = "idle"; selected = null
        status = "Stopped. Pick a bill number to start again"; voice?.say("Stopped.")
    }

    fun visit(reason: String) {
        val n = selected ?: return
        voice?.cancel(); vm.moveVisited(n, reason); selected = null; stage = "idle"; status = "$n moved to Visited folder"
    }
}

@Composable fun BillHistoryDialog(vm: AppVM, billNo: String, onDismiss: () -> Unit) {
    val logs by vm.logs.collectAsState()
    val l = logs.filter { it.billNo == billNo }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("📜 $billNo history") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (l.isEmpty()) item { Empty("No payments yet for $billNo.") }
            items(l) { x ->
                Panel { Row { Text("${fmtDay(x.date)} ${x.time}", Modifier.weight(1f), fontSize = 13.sp); Text(money(x.paid), color = Green, fontWeight = FontWeight.Bold) }
                    Lbl("${monthLabel(x.month)} • ${x.mode} • ${money(x.oldBalance)} → ${money(x.newBalance)}") }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Bill card (like the Filter By screen): amounts, History, Rent Update, Next Payment, Move to Visited, Close */
@Composable fun SelectedPayCard(vm: AppVM, flow: PayFlow, speak: Boolean) {
    val bills = monthBills(vm); val pays = monthPays(vm); val gs by vm.groups.collectAsState()
    val n = flow.selected
    if (n == null) { Lbl(flow.status); return }
    val b = bills.firstOrNull { it.billNo == n }
    if (b == null) { Lbl("Bill $n not found in Bill Addition"); return }
    val bal = balanceOf(b, pays[n])
    var amt by remember(n) { mutableStateOf("%.0f".format(bal)) }
    var reason by remember(n) { mutableStateOf("") }
    var showPay by remember(n) { mutableStateOf(false) }
    var hist by remember { mutableStateOf(false) }; var rent by remember { mutableStateOf(false) }
    val rows = listOf("Total Bill Amount" to money(b.billAmount), "Units" to "%.0f".format(b.units), "Unit Charge" to money(b.unitsCharge),
        "Old Balance" to money(b.prevBalance)) + (if (b.advanceDue > 0) listOf("Advance Due" to money(b.advanceDue)) else emptyList()) +
        listOf("Received" to money(pays[n]?.received ?: 0.0))
    Panel {
        Text("Bill $n", Modifier.fillMaxWidth(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(k, Modifier.weight(1f), fontSize = 14.sp); Text(v, fontWeight = FontWeight.Bold) } }
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("Latest Balance", Modifier.weight(1f), fontSize = 14.sp)
                    Text(money(bal), fontWeight = FontWeight.ExtraBold, color = if (bal > 0) Green else Teal)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BtnO("📜 History", Modifier.weight(1f)) { hist = true }
            BtnO("🏠 Rent Update", Modifier.weight(1f)) { rent = true }
        }
        Spacer(Modifier.height(8.dp))
        Btn("💳 Next Payment", Modifier.fillMaxWidth()) { showPay = !showPay }
        if (showPay || flow.stage == "confirm") {
            NumField("Enter payment amount ₹", amt, { amt = it }, Modifier.fillMaxWidth().padding(top = 6.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("💳 GPay", Modifier.weight(1f)) { num(amt)?.takeIf { it > 0 }?.let { flow.amount = it; flow.mode = "GPAY"; flow.confirmPay() } }
                BtnO("💵 Cash", Modifier.weight(1f)) { num(amt)?.takeIf { it > 0 }?.let { flow.amount = it; flow.mode = "CASH"; flow.confirmPay() } }
            }
        }
        if (speak) {
            Text(flow.status, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BtnO("⏸ Pause", Modifier.weight(1f)) { flow.pause() }
                Btn("▶ Resume", Modifier.weight(1f)) { flow.resume() }
                if (flow.stage == "confirm") Btn("✓ Confirm", Modifier.weight(1f)) { flow.confirmPay() }
            }
            if (flow.stage == "next") Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("➜ Continue", Modifier.weight(1f)) { flow.continueNext() }; BtnO("■ Stop", Modifier.weight(1f)) { flow.stop() }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TxtField("Reason (optional)", reason, { reason = it }, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); BtnO("➜ Move to Visited") { flow.visit(reason) }
        }
        Spacer(Modifier.height(6.dp))
        BtnO("✕ Close / Back to bills", Modifier.fillMaxWidth()) { flow.close() }
    }
    if (hist) BillHistoryDialog(vm, n) { hist = false }
    if (rent) {
        val g = groupFor(n, gs)
        if (g != null) GroupEditDialog(vm, g) { rent = false } else { rent = false; vm.toastMsg("No group found for $n – add it in Rent Update") }
    }
}

@Composable fun QuickPayPanel(vm: AppVM, speak: Boolean, search: String = "") {
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
    val groupsList = pending.map { groupLetters(it.billNo) }.filter { it.isNotBlank() }.distinct().sorted()
    // Voice Payment: groups & numbers come straight from Bill Addition – first group is selected automatically
    val effGroup = if (speak && group.isEmpty()) (groupsList.firstOrNull() ?: "") else group

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && pendingPick.isNotEmpty()) flow.pick(pendingPick)
        pendingPick = ""
    }
    fun choose(n: String) {
        if (voice != null && !voice.hasMic()) { pendingPick = n; micPerm.launch(Manifest.permission.RECORD_AUDIO) } else flow.pick(n)
    }
    // Filter By: typing a full bill number opens its card
    LaunchedEffect(search) {
        val k = norm(search)
        if (!speak && k.isNotEmpty() && bills.any { it.billNo == k }) flow.pick(k)
    }

    Column(Modifier.fillMaxSize()) {
        val folderLabel = "Visited (${inFolder.size})"
        Chips(listOf("Quick", "Table", "Manual", folderLabel), if (tab == "Visited") folderLabel else tab) { tab = if (it.startsWith("Visited")) "Visited" else it }
        SelectedPayCard(vm, flow, speak)
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Quick" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (speak) Chips(groupsList, effGroup) { group = it } else {
                        TxtField("Enter group id (N, NS …)", group, { group = it.uppercase() }, Modifier.fillMaxWidth())
                        GroupChips(groupsList, group) { group = it }
                    }
                    val q = if (speak) effGroup else group
                    val shown = pending.filter { (q.isEmpty() || billMatch(it.billNo, q)) && (search.isBlank() || billMatch(it.billNo, search)) }
                    Lbl("${shown.size} bills with balance • tap a number" + if (speak) " (it will speak the amount)" else "")
                    NumberGrid(shown.map { it.billNo to statusColor(statusOf(it, pays[it.billNo])) }) { choose(it) }
                }
                "Table" -> LazyColumn {
                    item { Row(Modifier.fillMaxWidth().padding(4.dp)) { Lbl("NO"); Spacer(Modifier.weight(1f)); Lbl("BILL   RECEIVED   BALANCE") } }
                    items(pending.filter { search.isBlank() || billMatch(it.billNo, search) }) { b ->
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
