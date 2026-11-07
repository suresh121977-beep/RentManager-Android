@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.rentmanager.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Files
import com.rentmanager.util.Img
import com.rentmanager.util.Sms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun statusColor(s: PayStatus) = when (s) { PayStatus.PAID -> Green; PayStatus.PARTIAL -> Amber; PayStatus.UNPAID -> Red }

fun <T> LazyListScope.itemsIdx(l: List<T>, c: @Composable (Int, T) -> Unit) { items(l.size) { c(it, l[it]) } }

@Composable fun GroupChips(groups: List<String>, sel: String, onSel: (String) -> Unit) {
    Chips(listOf("All") + groups, if (sel.isEmpty()) "All" else sel) { onSel(if (it == "All") "" else it) }
}

@Composable fun NumberGrid(items: List<Pair<String, Color>>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (n, c) ->
            OutlinedButton({ onPick(n) }, border = BorderStroke(1.5.dp, c), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(n, color = c, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Group chips + number buttons; tap a number to open its table */
@Composable fun QuickView(bills: List<Bill>, pays: Map<String, Payment>, onPick: (Bill) -> Unit) {
    var g by remember { mutableStateOf("") }
    val groups = bills.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = bills.filter { g.isEmpty() || it.groupName == g }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        GroupChips(groups, g) { g = it }
        Spacer(Modifier.height(8.dp))
        Lbl("${shown.size} bills • tap a number to see its table")
        NumberGrid(shown.map { it.billNo to statusColor(statusOf(it, pays[it.billNo])) }) { n -> shown.firstOrNull { it.billNo == n }?.let(onPick) }
    }
}

/** Table | Quick toggle. In Quick mode a number opens the bill table dialog. */
@Composable fun QuickHost(vm: AppVM, bills: List<Bill>, pays: Map<String, Payment>, modifier: Modifier = Modifier.fillMaxSize(),
                          table: @Composable ((Bill) -> Unit) -> Unit) {
    var mode by remember { mutableStateOf("Table") }; var sel by remember { mutableStateOf<Bill?>(null) }
    Column(modifier) {
        Chips(listOf("Table", "Quick"), mode) { mode = it }
        Box(Modifier.weight(1f)) { if (mode == "Quick") QuickView(bills, pays) { sel = it } else table { sel = it } }
    }
    sel?.let { BillDetailDialog(vm, it) { sel = null } }
}

private fun fmt0(d: Double?) = if (d == null) "-" else "%.0f".format(d)

/** Selected bill result as a table (units, persons, previous balance, rent, extra person, final amount ...) */
@Composable fun BillDetailDialog(vm: AppVM, b: Bill, onDismiss: () -> Unit) {
    val rs by vm.readings.collectAsState(); val pl by vm.payments.collectAsState()
    val r = rs.firstOrNull { it.billNo == b.billNo && it.month == b.month }
    val p = pl.firstOrNull { it.billNo == b.billNo && it.month == b.month }
    val fresher = b.rentDays < 0
    val rows = listOf(
        "Bill number" to b.billNo, "Group" to b.groupName,
        "Previous reading" to fmt0(r?.prevReading),
        "Current reading" to (if (r != null && r.currReading > 0) fmt0(r.currReading) else "-"),
        "Total units" to fmt0(b.units), "Units charge" to money(b.unitsCharge),
        "Persons" to "${b.persons}",
        (if (fresher) "Rent" else "Rent (${b.rentDays} days)") to (if (fresher) "New tenant – no rent" else money(b.rent)),
        "Extra persons" to "${b.extraPersons} = ${money(b.extraTotal)}",
        "Other / extra charge" to money(b.extraCharge),
        "Advance due" to money(b.advanceDue),
        "Previous balance" to money(b.prevBalance),
        "FINAL AMOUNT" to money(b.billAmount),
        "Received" to money(p?.received ?: 0.0), "Balance" to money(balanceOf(b, p)))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${b.billNo} • ${monthLabel(b.month)}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            rows.forEach { (k, v) ->
                val big = k == "FINAL AMOUNT"
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(k, Modifier.weight(1f), fontSize = if (big) 15.sp else 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontWeight = if (big) FontWeight.ExtraBold else FontWeight.Medium, fontSize = if (big) 17.sp else 13.sp,
                        color = if (big) Teal else Color.Unspecified)
                }
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Returns a function that calls a number directly (asks CALL_PHONE permission once) */
@Composable fun rememberCaller(): (String) -> Unit {
    val ctx = LocalContext.current; var pending by remember { mutableStateOf("") }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (pending.isNotBlank()) { Files.call(ctx, pending, ok); pending = "" }
    }
    return { m -> if (Files.canCall(ctx)) { Files.call(ctx, m, true) } else { pending = m; l.launch(Manifest.permission.CALL_PHONE) } }
}

/** Returns a function that sends SMS directly from the app (no message box). done(sent, failed) */
@Composable fun rememberSmsSender(): (List<Pair<String, String>>, (Int, Int) -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Pair<List<Pair<String, String>>, (Int, Int) -> Unit>?>(null) }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (p != null) { if (ok) { runSms(ctx, p.first, p.second) } else { p.second(0, p.first.size) } }
    }
    return { list, done -> if (Sms.has(ctx)) { runSms(ctx, list, done) } else { pending = list to done; l.launch(Manifest.permission.SEND_SMS) } }
}
private fun runSms(ctx: android.content.Context, list: List<Pair<String, String>>, done: (Int, Int) -> Unit) {
    var ok = 0
    list.forEach { if (Sms.send(ctx, it.first, it.second)) ok++ }
    done(ok, list.size - ok)
}

/** Small picture preview decoded in the background (never blocks the screen) */
@Composable fun Thumb(f: File, size: Dp = 96.dp, onClick: () -> Unit = {}) {
    val isImg = Img.isImage(f)
    val bmp by produceState<ImageBitmap?>(null, f.path) {
        value = if (isImg) withContext(Dispatchers.IO) { Img.decodeSampled(f, 300)?.asImageBitmap() } else null
    }
    val b = bmp
    OutlinedCard(onClick = onClick, modifier = Modifier.size(size)) {
        if (b != null) Image(b, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize()) { Text(if (isImg) "…" else "📄 ${f.extension}", Modifier.padding(6.dp), fontSize = 12.sp) }
    }
}
