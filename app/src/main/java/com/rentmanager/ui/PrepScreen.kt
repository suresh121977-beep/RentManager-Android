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
import com.rentmanager.util.Files
import com.rentmanager.util.Voice

const val HIGH_UNITS = 300.0

/** Speaks each bill number + previous reading, listens for the current reading, shows units.
 *  If units are more than 300 it asks to CHECK (say again) or ENTER (accept just that bill). */
class ReadFlow(private val vm: AppVM, val voice: Voice?, private val readingOf: (String) -> Reading?) {
    var queue by mutableStateOf<List<String>>(emptyList())
    var current by mutableStateOf<String?>(null)
    var status by mutableStateOf("Pick a group, then start voice entry – or tap a number and type")
    var stage by mutableStateOf("idle")   // idle | reading | check
    var pendingCurr by mutableStateOf(0.0)
    private var tries = 0

    fun prevOf(n: String) = readingOf(n)?.prevReading ?: 0.0

    fun select(n: String) {
        voice?.cancel(); current = n; stage = "reading"; tries = 0
        status = "$n • previous ${prevOf(n).toInt()} • type current reading"
    }

    fun start(list: List<String>) { queue = list; tries = 0; next() }

    fun next() {
        val n = queue.firstOrNull()
        if (n == null) { current = null; stage = "idle"; status = "✓ All bills in this list are done"; voice?.say("All done."); return }
        current = n; stage = "reading"
        status = "$n • previous ${prevOf(n).toInt()} • say current reading"
        voice?.say("$n. Previous reading ${prevOf(n).toInt()}. Say current reading.") { listenReading() }
    }

    private fun retry(err: String, again: () -> Unit) {
        tries++
        if (tries <= 2) { status = err; voice?.say("I did not catch that. Please say again.") { again() } }
        else { status = "Paused – tap 🎤 to continue"; stage = "idle"; tries = 0 }
    }

    fun listenReading() {
        val n = current ?: return; val v = voice ?: return
        stage = "reading"; status = "🎙 $n – listening for current reading…"
        v.listen(onText = { h -> onHeard(n, h) }, onError = { e -> retry(e) { listenReading() } })
    }

    private fun onHeard(n: String, heard: String) {
        val v = voice ?: return; val h = heard.lowercase(); tries = 0
        when {
            Regex("\\b(stop|close|finish)\\b").containsMatchIn(h) -> stop()
            Regex("\\b(skip|next)\\b").containsMatchIn(h) -> { queue = queue.drop(1); v.say("Skipped.") { next() } }
            else -> {
                val c = spokenNumber(heard)
                if (c == null) { retry("Number not understood") { listenReading() } } else submit(n, c)
            }
        }
    }

    /** Used by voice and typing. force = true accepts a reading even if units are above the limit. */
    fun submit(n: String, c: Double, force: Boolean = false) {
        val u = c - prevOf(n)
        if (u < 0) {
            status = "$n: current ${c.toInt()} is less than previous ${prevOf(n).toInt()}"
            voice?.say("Current is less than previous. Say again.") { listenReading() }
            return
        }
        if (u > HIGH_UNITS && !force) {
            pendingCurr = c; stage = "check"; current = n
            status = "⚠ $n: ${u.toInt()} units is more than ${HIGH_UNITS.toInt()}. Check, or Enter to accept"
            voice?.say("Units ${u.toInt()}. More than 300. Say enter to accept, or check to say again.") { listenCheck() }
            return
        }
        vm.saveCurrent(n, c); queue = queue.filter { it != n }
        status = "✓ $n current ${c.toInt()} → ${u.toInt()} units"
        val v = voice
        if (v != null) { v.say("${u.toInt()} units.") { next() } } else { current = null; stage = "idle" }
    }

    fun listenCheck() {
        val n = current ?: return; val v = voice ?: return
        v.listen(onText = { h0 ->
            val h = h0.lowercase(); tries = 0
            when {
                Regex("\\b(enter|yes|confirm|ok|okay|correct)\\b").containsMatchIn(h) -> submit(n, pendingCurr, true)
                Regex("\\b(stop|close)\\b").containsMatchIn(h) -> stop()
                else -> {
                    val c = spokenNumber(h0)
                    if (c != null && !Regex("\\b(check|no|again)\\b").containsMatchIn(h)) submit(n, c)
                    else v.say("Say the current reading again.") { listenReading() }
                }
            }
        }, onError = { e -> retry(e) { listenCheck() } })
    }

    fun checkAgain() { stage = "reading"; status = "$current – say or type the current reading again"; if (voice != null) listenReading() }
    fun resume() { if (current != null) listenReading() else next() }
    fun stop() { voice?.cancel(); stage = "idle"; status = "Stopped. ${queue.size} bills still waiting" ; voice?.say("Stopped.") }
}

@Composable fun PreparingScreen(vm: AppVM) {
    val m by vm.month.collectAsState(); val rs by vm.readings.collectAsState()
    val rows = remember(m, rs) { rs.filter { it.month == m }.sortedWith { a, b -> billCompare.compare(a.billNo, b.billNo) } }
    var mode by remember { mutableStateOf("Table") }
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("Table", "Quick"), mode) { mode = it }
        Box(Modifier.weight(1f)) { if (mode == "Table") PrepTable(vm, rows) else PrepQuick(vm, rows) }
    }
}

@Composable private fun PrepUploads(vm: AppVM) {
    val ctx = LocalContext.current
    var price by remember { mutableStateOf(vm.price.toString()) }
    val pickFull = rememberTextPicker { vm.importReadings(it) }; val pickPrev = rememberTextPicker { vm.importPrevReadings(it) }
    Panel {
        Lbl("Default price / unit ₹ (a group's own price overrides)")
        NumField("Price/Unit", price, { price = it; num(it)?.let { v -> vm.price = v } }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Lbl("CSV with readings: BillNo,Prev,Curr  or  BillNo,End")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("📄 Readings CSV") { pickFull() }
            BtnO("⬇ Template") { Files.share(ctx, "end_reading_template.csv", "text/csv", "BillNo,End\nNS1,1350\n") }
        }
        Spacer(Modifier.height(6.dp))
        Lbl("CSV with previous readings only: BillNo,Prev  → then say / type the current reading")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("📄 Previous CSV") { pickPrev() }
            BtnO("↩ Last month's current") { vm.startFromLastMonth() }
        }
    }
}

@Composable private fun PrepTable(vm: AppVM, rows: List<Reading>) {
    val ctx = LocalContext.current; val m by vm.month.collectAsState()
    var bn by remember { mutableStateOf("") }; var pv by remember { mutableStateOf("") }; var cv by remember { mutableStateOf("") }
    LazyColumn {
        item { PrepUploads(vm) }
        item {
            Panel { H("Add single reading"); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TxtField("Bill", bn, { bn = it }, Modifier.weight(1f)); NumField("Prev", pv, { pv = it }, Modifier.weight(1f)); NumField("Curr", cv, { cv = it }, Modifier.weight(1f)) }
                Btn("＋ Add Bill") { val p = num(pv); val c = num(cv); if (p != null && c != null) { vm.addReading(bn, p, c); bn = ""; pv = ""; cv = "" } } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${rows.size} rows • ${rows.count { it.pending() }} waiting")
                Row {
                    TextButton({ Files.share(ctx, "preparing_$m.csv", "text/csv", "BillNo,Prev,Curr,Units,Price,Charge\n" +
                        rows.joinToString("\n") { "${it.billNo},${it.prevReading ?: ""},${it.currReading},${it.units},${it.price},${it.charge}" }) }) { Text("⬇ CSV") }
                    TextButton({ vm.clearReadings() }) { Text("🗑 Clear", color = Red) }
                }
            }
        }
        if (rows.isEmpty()) item { Empty("No readings for this month. Upload the CSV.") }
        items(rows) { r ->
            Panel { Row {
                Text(r.billNo, Modifier.weight(1f), fontSize = 16.sp)
                if (r.pending()) Text("${r.prevReading?.toInt()} → waiting", color = Amber)
                else Text("${r.prevReading?.let { "%.0f".format(it) } ?: "?"} → ${"%.0f".format(r.currReading)} = ${"%.0f".format(r.units)}u ${money(r.charge)}",
                    color = if (r.endOnlyUnresolved) Red else MaterialTheme.colorScheme.onSurface)
            } }
        }
    }
}

@Composable private fun PrepQuick(vm: AppVM, rows: List<Reading>) {
    val ctx = LocalContext.current
    val voice = remember { Voice(ctx) }
    DisposableEffect(Unit) { onDispose { voice.shutdown() } }
    val rowsS by rememberUpdatedState(rows)
    val flow = remember { ReadFlow(vm, voice) { n -> rowsS.firstOrNull { it.billNo == n } } }
    var g by remember { mutableStateOf("") }; var typed by remember { mutableStateOf("") }
    var startList by remember { mutableStateOf<List<String>>(emptyList()) }
    val inGroup = rows.filter { g.isEmpty() || groupLetters(it.billNo) == g }
    val pending = inGroup.filter { it.pending() }; val done = inGroup.filter { !it.pending() }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) flow.start(startList) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        PrepUploads(vm)
        GroupChips(rows.map { groupLetters(it.billNo) }.filter { it.isNotBlank() }.distinct().sorted(), g) { g = it }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("🎤 Voice entry (${pending.size})") {
                startList = pending.map { it.billNo }
                if (voice.hasMic()) flow.start(startList) else micPerm.launch(Manifest.permission.RECORD_AUDIO)
            }
            BtnO("🎤 Listen") { flow.resume() }
            BtnO("■ Stop") { flow.stop() }
        }
        Panel {
            Text(flow.status, fontSize = 13.sp)
            val n = flow.current
            if (n != null) {
                Text(n, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumField("Current reading", typed, { typed = it }, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Btn("Enter") { num(typed)?.let { flow.submit(n, it); typed = "" } }
                }
                if (flow.stage == "check") Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("✓ Enter (accept)", Modifier.weight(1f)) { flow.submit(n, flow.pendingCurr, true) }
                    BtnO("↺ Check", Modifier.weight(1f)) { flow.checkAgain() }
                }
            }
        }
        Lbl("Waiting for current reading (tap to type)")
        NumberGrid(pending.map { it.billNo to Amber }) { flow.select(it) }
        Spacer(Modifier.height(8.dp))
        Lbl("Done (tap to correct)")
        NumberGrid(done.map { it.billNo to Green }) { flow.select(it) }
        Spacer(Modifier.height(6.dp))
        done.forEach { Text("${it.billNo}   ${it.prevReading?.toInt() ?: "?"} → ${it.currReading.toInt()}  =  ${it.units.toInt()} units", fontSize = 12.sp) }
    }
}
