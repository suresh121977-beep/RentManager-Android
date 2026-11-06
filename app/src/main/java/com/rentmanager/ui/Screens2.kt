@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Files
import com.rentmanager.util.Voice
import kotlinx.coroutines.launch
import java.time.LocalDate

// 12 --------------------------------------------------------------- Spending & Income
@Composable fun FinanceScreen(vm: AppVM) {
    val fin by vm.finance.collectAsState(); val eb by vm.eb.collectAsState(); val m by vm.month.collectAsState(); val pays by vm.payments.collectAsState()
    val ctx = LocalContext.current; var kind by remember { mutableStateOf("SPEND") }; var amt by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }; var all by remember { mutableStateOf(false) }
    val l = fin.filter { all || it.date.startsWith(m) }
    val rent = pays.filter { all || it.month == m }.sumOf { it.received }; val ebPaid = eb.filter { all || it.month == m }.sumOf { it.paidAmount }
    val inc = l.filter { it.kind == "INCOME" }.sumOf { it.amount }; val sp = l.filter { it.kind == "SPEND" }.sumOf { it.amount }
    LazyColumn {
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("INCOME", money(inc + rent), Green, Modifier.weight(1f)); Stat("SPENDING", money(sp + ebPaid), Red, Modifier.weight(1f)) }
            Stat("NET", money(inc + rent - sp - ebPaid), modifier = Modifier.fillMaxWidth())
            Lbl("Includes rent collected ${money(rent)} and EB bills paid ${money(ebPaid)} automatically"); Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(all, { all = it }); Text(" All months") } }
        item { Panel { Chips(listOf("SPEND", "INCOME"), kind) { kind = it }
            NumField("Amount ₹", amt, { amt = it }, Modifier.fillMaxWidth()); TxtField("Category", cat, { cat = it }, Modifier.fillMaxWidth()); TxtField("Note", note, { note = it }, Modifier.fillMaxWidth())
            Btn(if (kind == "SPEND") "Add Expense" else "Add Income") { num(amt)?.let { vm.addFinance(kind, it, cat, note, LocalDate.now().toString()); amt = ""; cat = ""; note = "" } }
            BtnO("⬇ Save CSV") { Files.share(ctx, "finance_${if (all) "all" else m}.csv", "text/csv", "Date,Type,Amount,Category,Note\n" + l.joinToString("\n") { "${it.date},${it.kind},${it.amount},${csvEsc(it.category)},${csvEsc(it.note)}" }) } } }
        items(l) { f -> Panel { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${if (f.kind == "INCOME") "💰" else "💸"} ${money(f.amount)} ${f.category}"); Lbl("${fmtDay(f.date)} ${f.note}") }
            TextButton({ vm.deleteFinance(f.id) }) { Text("🗑", color = Red) } } } }
    }
}

// 13 --------------------------------------------------------------- Electric Bill (gain/loss)
@Composable fun ElectricScreen(vm: AppVM) {
    val bills = monthBills(vm); val eb by vm.eb.collectAsState(); val m by vm.month.collectAsState()
    val tenant = bills.sumOf { it.unitsCharge }; val provider = eb.filter { it.month == m }.sumOf { it.paidAmount.takeIf { p -> p > 0 } ?: it.billAmount }
    val diff = tenant - provider
    Column { Panel { H("Electricity gain / loss"); Text("Collected from tenants (units charge): ${money(tenant)}"); Text("Paid to EB provider: ${money(provider)}")
        Text(if (diff >= 0) "GAIN ${money(diff)}" else "LOSS ${money(-diff)}", fontSize = 22.sp, color = if (diff >= 0) Green else Red) }
        Lbl("Add the provider bills in 'EB Bill Generator'. This updates automatically.")
        bills.groupBy { it.groupName }.toSortedMap().forEach { (g, l) -> Panel { Text(g); Lbl("${l.sumOf { it.units }.toInt()} units • ${money(l.sumOf { it.unitsCharge })}") } } }
}

// 14 --------------------------------------------------------------- EB Bill Generator
@Composable fun EbScreen(vm: AppVM) {
    val eb by vm.eb.collectAsState(); val m by vm.month.collectAsState(); var edit by remember { mutableStateOf<EbBill?>(null) }
    val pick = rememberTextPicker { vm.importEb(it) }
    LazyColumn { item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("＋ EB bill") { edit = EbBill(month = m, paidDate = LocalDate.now().toString()) }; BtnO("⬆ CSV") { pick() }; BtnO("Remove all") { vm.clearEb() } }
        Lbl("CSV: CardNo,Group,PrevReading,CurrReading,BillAmount") }
        items(eb.filter { it.month == m }) { e -> Panel { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Column(Modifier.weight(1f)) {
            Text("#${e.serialNo} ${e.groupName} • card ${e.cardNumber}"); Lbl("${e.prevReading.toInt()}→${e.currReading.toInt()} = ${e.units.toInt()}u • bill ${money(e.billAmount)} • paid ${money(e.paidAmount)}") }
            TextButton({ edit = e }) { Text("✏") }; TextButton({ vm.deleteEb(e.id) }) { Text("🗑", color = Red) } } } } }
    edit?.let { e -> var g by remember { mutableStateOf(e.groupName) }; var c by remember { mutableStateOf(e.cardNumber) }; var p by remember { mutableStateOf(e.prevReading.toString()) }
        var cu by remember { mutableStateOf(e.currReading.toString()) }; var b by remember { mutableStateOf(e.billAmount.toString()) }; var pd by remember { mutableStateOf(e.paidAmount.toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("EB bill") }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TxtField("Group", g, { g = it }); TxtField("Card number", c, { c = it }); NumField("Previous reading", p, { p = it }); NumField("Current reading", cu, { cu = it }); NumField("Bill amount ₹", b, { b = it }); NumField("Paid amount ₹", pd, { pd = it }) } },
            confirmButton = { TextButton({ vm.saveEb(e.copy(groupName = g, cardNumber = c, prevReading = num(p) ?: 0.0, currReading = num(cu) ?: 0.0, billAmount = num(b) ?: 0.0, paidAmount = num(pd) ?: 0.0)); edit = null }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }) }
}

// 15 --------------------------------------------------------------- Vacant
@Composable fun VacantScreen(vm: AppVM) {
    val tfs by vm.tenantFiles.collectAsState(); val vac by vm.vacated.collectAsState()
    val empty = tfs.filter { it.active().isEmpty() }.sortedWith { a, b -> billCompare.compare(a.fileName, b.fileName) }
    LazyColumn { item { H("Vacant files (${empty.size})"); Text(empty.joinToString("  ") { it.fileName }.ifBlank { "None" }) }
        item { H("Vacated history") }
        items(vac) { v -> Panel { Text("${v.fileName} • ${fmtDay(v.vacatedDate)}"); Lbl("${v.names} • due ${money(v.due)} • advance ${money(v.advance)} • repay ${money(v.repay)} • collect ${money(v.collect)}") } } }
}

// 16 --------------------------------------------------------------- Previous History
@Composable fun PrevHistScreen(vm: AppVM) {
    val bills by vm.bills.collectAsState(); val pays by vm.payments.collectAsState(); var open by remember { mutableStateOf<String?>(null) }
    val months = bills.map { it.month }.distinct().sortedDescending()
    LazyColumn { if (months.isEmpty()) item { Empty("No history yet.") }
        items(months) { mo -> val b = bills.filter { it.month == mo && !it.vacant }; val p = pays.filter { it.month == mo }.associateBy { it.billNo }
            Panel { Text(monthLabel(mo), fontSize = 17.sp); Lbl("${b.size} bills • Total ${money(b.sumOf { it.billAmount })} • Received ${money(p.values.sumOf { it.received })} • Balance ${money(b.sumOf { balanceOf(it, p[it.billNo]) })}")
                TextButton({ open = if (open == mo) null else mo }) { Text(if (open == mo) "Hide" else "Details") }
                if (open == mo) b.sortedWith { x, y -> billCompare.compare(x.billNo, y.billNo) }.forEach { Text("${it.billNo}  ${money(it.billAmount)}  recd ${money(p[it.billNo]?.received ?: 0.0)}  bal ${money(balanceOf(it, p[it.billNo]))}", fontSize = 12.sp) } } } }
}

// 17 --------------------------------------------------------------- Reset
@Composable fun ResetScreen(vm: AppVM) {
    val m by vm.month.collectAsState(); var all by remember { mutableStateOf(false) }; var fin by remember { mutableStateOf(false) }; var bk by remember { mutableStateOf(true) }; var ask by remember { mutableStateOf(false) }
    val r by vm.readings.collectAsState(); val b by vm.bills.collectAsState(); val p by vm.payments.collectAsState(); val l by vm.logs.collectAsState()
    fun c(mo: String) = if (all) "" else mo
    Panel { H("Reset billing data"); Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(all, { all = it }); Text(if (all) " All months" else " Selected month only (${monthLabel(m)})") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(fin, { fin = it }); Text(" Also clear Finance & EB bills") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(bk, { bk = it }); Text(" Save a JSON backup first") }
        Text("Will erase: ${r.count { all || it.month == m }} readings, ${b.count { all || it.month == m }} bills, ${p.count { all || it.month == m }} payments, ${l.count { all || it.month == m }} payment logs", Modifier.padding(vertical = 6.dp))
        Button({ ask = true }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("🧹 Reset") } }
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("Are you sure?") }, text = { Text("This cannot be undone${if (bk) " (a backup is saved first)" else ""}.") },
        confirmButton = { TextButton({ vm.reset(all, fin, bk); ask = false }) { Text("Reset", color = Red) } }, dismissButton = { TextButton({ ask = false }) { Text("Cancel") } })
}

// 18 --------------------------------------------------------------- Favourite
@Composable fun FavScreen(vm: AppVM) {
    val favs by vm.favs.collectAsState(); val bills = monthBills(vm); val pays = monthPays(vm); var pay by remember { mutableStateOf<Bill?>(null) }
    val l = bills.filter { b -> favs.any { it.billNo == b.billNo } }
    LazyColumn { if (l.isEmpty()) item { Empty("Tap ☆ on any bill in Bill Management to add it here.") }
        items(l) { b -> BillRow(b, pays[b.billNo], true, { pay = b }, { vm.toggleFav(b.billNo) }) } }
    pay?.let { b -> PayDialog(b, pays[b.billNo], { pay = null }) { a, md -> vm.pay(b.billNo, a, md) } }
}

// 19 --------------------------------------------------------------- Voice Payment
@Composable fun VoiceScreen(vm: AppVM) {
    val ctx = LocalContext.current; val voice = remember { Voice(ctx) }; DisposableEffect(Unit) { onDispose { voice.shutdown() } }
    val bills = monthBills(vm); val pays = monthPays(vm); val log = remember { mutableStateListOf<String>() }
    var heard by remember { mutableStateOf("") }; var cmd by remember { mutableStateOf<VoiceCmd?>(null) }; var speaker by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(true) }; var listening by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("Say e.g. “NS 5 five hundred cash”") }
    fun doPay(c: VoiceCmd) { val b = bills.firstOrNull { it.billNo == c.billNo }; if (b == null) { status = "Bill ${c.billNo} not found"; voice.say(status); return }
        val a = if (c.full) balanceOf(b, pays[b.billNo]) else c.amount ?: 0.0; if (a <= 0) { status = "No amount heard"; voice.say(status); return }
        vm.pay(b.billNo, a, c.mode); log.add(0, "${b.billNo} ${money(a)} ${c.mode}"); voice.say("${b.billNo} ${a.toInt()} rupees ${c.mode} recorded"); cmd = null }
    fun listen() { voice.speakerOn = speaker; listening = true
        voice.listen(onText = { t -> listening = false; heard = t; val c = parseVoice(t); status = "Heard: $t"
            if (c.billNo == null || (c.amount == null && !c.full)) { status = "Couldn't understand “$t”"; voice.say("Please say bill number and amount") }
            else if (confirm) { cmd = c; voice.say("${c.billNo}, ${if (c.full) "full balance" else "${c.amount?.toInt()} rupees"}, ${c.mode}. Confirm?") } else doPay(c) },
            onError = { listening = false; status = it }) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) { listen() } else { status = "Microphone permission denied" } }
    Column { Panel { Text(status); Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(speaker, { speaker = it; voice.speakerOn = it }); Text(" 🔊 Speaker") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(confirm, { confirm = it }); Text(" Ask to confirm before paying") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn(if (listening) "🎙 Listening…" else "🎤 Start Voice Payment") { if (voice.hasMic()) listen() else perm.launch(Manifest.permission.RECORD_AUDIO) }; BtnO("■ Stop") { voice.stop(); listening = false } } }
        cmd?.let { c -> Panel { Text("Pay ${c.billNo}: ${if (c.full) "full balance" else money(c.amount ?: 0.0)} via ${c.mode}?"); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("✓ Confirm & Pay") { doPay(c) }; BtnO("✕ Cancel") { cmd = null } } } }
        H("This session"); LazyColumn { items(log) { Text(it, Modifier.padding(2.dp)) } } }
}

// 20 --------------------------------------------------------------- Export
@Composable fun ExportScreen(vm: AppVM) {
    val ctx = LocalContext.current; val m by vm.month.collectAsState(); val bills = monthBills(vm); val pays = monthPays(vm); val rs by vm.readings.collectAsState()
    val logs by vm.logs.collectAsState(); val fin by vm.finance.collectAsState(); val tfs by vm.tenantFiles.collectAsState()
    fun bt(b: Bill) = billText(b, pays[b.billNo]).replace("\n", " | ")
    Column { H("Export (opens the share sheet — save to Drive, Files or send on WhatsApp; CSV opens in Excel)")
        Btn("Bills (CSV)", Modifier.fillMaxWidth()) { Files.share(ctx, "bills_$m.csv", "text/csv", "Bill,Group,Units,UnitsCharge,Rent,Persons,ExtraTotal,ExtraCharge,PrevBalance,BillAmount,Received,Balance\n" +
            bills.joinToString("\n") { "${it.billNo},${it.groupName},${it.units},${it.unitsCharge},${it.rent},${it.persons},${it.extraTotal},${it.extraCharge},${it.prevBalance},${it.billAmount},${pays[it.billNo]?.received ?: 0},${balanceOf(it, pays[it.billNo])}" }) }
        BtnO("Bills (TXT messages)", Modifier.fillMaxWidth()) { Files.share(ctx, "bills_$m.txt", "text/plain", bills.joinToString("\n\n") { billText(it, pays[it.billNo]) }) }
        BtnO("Payments (CSV)", Modifier.fillMaxWidth()) { Files.share(ctx, "payments_$m.csv", "text/csv", "Bill,Date,Time,Mode,Paid,OldBalance,NewBalance\n" + logs.filter { it.month == m }.joinToString("\n") { "${it.billNo},${it.date},${it.time},${it.mode},${it.paid},${it.oldBalance},${it.newBalance}" }) }
        BtnO("Readings (CSV)", Modifier.fillMaxWidth()) { Files.share(ctx, "readings_$m.csv", "text/csv", "BillNo,Prev,Curr\n" + rs.filter { it.month == m }.joinToString("\n") { "${it.billNo},${it.prevReading ?: ""},${it.currReading}" }) }
        BtnO("Tenants (CSV)", Modifier.fillMaxWidth()) { Files.share(ctx, "tenants.csv", "text/csv", "File,Name,Mobile,Aadhaar,Entry,Exit\n" + tfs.flatMap { t -> t.persons().map { "${t.fileName},${csvEsc(it.name)},${it.mobile},${it.aadhaar},${it.entryDate},${it.exitDate}" } }.joinToString("\n")) }
        BtnO("Finance (CSV)", Modifier.fillMaxWidth()) { Files.share(ctx, "finance.csv", "text/csv", "Date,Type,Amount,Category,Note\n" + fin.joinToString("\n") { "${it.date},${it.kind},${it.amount},${csvEsc(it.category)},${csvEsc(it.note)}" }) } }
}

// 21 --------------------------------------------------------------- Usage Performance
@Composable fun UsageScreen(vm: AppVM) {
    val m by vm.month.collectAsState(); val bills by vm.bills.collectAsState(); val months = (4 downTo 0).map { shiftMonth(m, -it.toLong()) }
    val by = bills.groupBy { it.billNo }.toSortedMap(billCompare)
    LazyColumn { item { Row { Text("Bill", Modifier.weight(1f)); months.forEach { Text(monthLabel(it).take(3), Modifier.weight(1f), fontSize = 11.sp) } } }
        items(by.entries.toList()) { (b, l) -> val u = months.map { mo -> l.firstOrNull { it.month == mo }?.units }; val last = u.last(); val prev = u.dropLast(1).filterNotNull().lastOrNull()
            Panel { Row { Text(b, Modifier.weight(1f)); u.forEach { Text(it?.toInt()?.toString() ?: "–", Modifier.weight(1f), fontSize = 12.sp) } }
                if (last != null && prev != null && prev > 0) { val d = (last - prev) / prev * 100; Lbl(if (d >= 25) "▲ usage up ${d.toInt()}%" else if (d <= -25) "▼ usage down ${(-d).toInt()}%" else "steady") } } } }
}

// 22 --------------------------------------------------------------- Data Safety
@Composable fun DataSafeScreen(vm: AppVM) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); var auto by remember { mutableStateOf(vm.autoBackupOn) }; var files by remember { mutableStateOf(vm.backups()) }
    val pick = rememberTextPicker { vm.restoreJson(it) }
    LazyColumn { item { Panel { H("🛡 Data Safety"); Text("Last auto-backup: ${vm.lastBackup()}")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(auto, { auto = it; vm.autoBackupOn = it }); Text(" Auto-backup once a day on app open") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("💾 Backup now") { vm.backupNow(); files = vm.backups() }
            BtnO("⬇ Export JSON") { scope.launch { Files.share(ctx, "rent_backup_${LocalDate.now()}.json", "application/json", vm.backupJson()) } } }
        BtnO("♻ Restore from JSON file") { pick() } } }
        item { H("Backups on this phone") }
        items(files) { f -> Panel { Text(f.name, fontSize = 12.sp); TextButton({ vm.restoreFile(f) }) { Text("♻ Restore this") } } } }
}
