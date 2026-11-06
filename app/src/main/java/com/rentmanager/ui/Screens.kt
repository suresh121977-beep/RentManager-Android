@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Files
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable fun Screen(i: Int, vm: AppVM) = when (i) {
    0 -> PreparingScreen(vm); 1 -> AdditionScreen(vm); 2 -> PersonScreen(vm); 3 -> RentUpdateScreen(vm)
    4 -> GroupsScreen(vm); 5 -> ManageScreen(vm, false); 6 -> ManageScreen(vm, true); 7 -> ReferenceScreen(vm)
    8 -> HistoryScreen(vm); 9 -> MessageScreen(vm); 10 -> TenantScreen(vm); 11 -> FinanceScreen(vm)
    12 -> ElectricScreen(vm); 13 -> EbScreen(vm); 14 -> VacantScreen(vm); 15 -> PrevHistScreen(vm)
    16 -> ResetScreen(vm); 17 -> FavScreen(vm); 18 -> VoiceScreen(vm); 19 -> ExportScreen(vm)
    20 -> UsageScreen(vm); else -> DataSafeScreen(vm)
}

// shared month-filtered state
@Composable fun monthBills(vm: AppVM): List<Bill> { val m by vm.month.collectAsState(); val b by vm.bills.collectAsState()
    return remember(m, b) { b.filter { it.month == m && !it.vacant }.sortedWith { x, y -> billCompare.compare(x.billNo, y.billNo) } } }
@Composable fun monthPays(vm: AppVM): Map<String, Payment> { val m by vm.month.collectAsState(); val p by vm.payments.collectAsState()
    return remember(m, p) { p.filter { it.month == m }.associateBy { it.billNo } } }

// 1 ---------------------------------------------------------------- Bill Preparing
@Composable fun PreparingScreen(vm: AppVM) {
    val m by vm.month.collectAsState(); val rs by vm.readings.collectAsState(); val ctx = LocalContext.current
    val rows = remember(m, rs) { rs.filter { it.month == m }.sortedWith { a, b -> billCompare.compare(a.billNo, b.billNo) } }
    var price by remember { mutableStateOf(vm.price.toString()) }; var bn by remember { mutableStateOf("") }
    var pv by remember { mutableStateOf("") }; var cv by remember { mutableStateOf("") }
    val pick = rememberTextPicker { vm.importReadings(it) }
    LazyColumn {
        item { Panel { Lbl("Default price / unit ₹ (a group's own price overrides)")
            NumField("Price/Unit", price, { price = it; num(it)?.let { v -> vm.price = v } }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp)); Lbl("CSV: BillNo,Prev,Curr  (NS5,1200,1350)  or  BillNo,End  (NS5,1350)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("📄 Upload CSV") { pick() }
                BtnO("⬇ Template") { Files.share(ctx, "end_reading_template.csv", "text/csv", "BillNo,End\nNS1,1350\n") } } } }
        item { Panel { H("Add single reading"); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TxtField("Bill", bn, { bn = it }, Modifier.weight(1f)); NumField("Prev", pv, { pv = it }, Modifier.weight(1f)); NumField("Curr", cv, { cv = it }, Modifier.weight(1f)) }
            Btn("＋ Add Bill") { val p = num(pv); val c = num(cv); if (p != null && c != null) { vm.addReading(bn, p, c); bn = ""; pv = ""; cv = "" } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} rows"); Row { TextButton({ Files.share(ctx, "preparing_$m.csv", "text/csv",
                "BillNo,Prev,Curr,Units,Price,Charge\n" + rows.joinToString("\n") { "${it.billNo},${it.prevReading ?: ""},${it.currReading},${it.units},${it.price},${it.charge}" }) }) { Text("⬇ CSV") }
                TextButton({ vm.clearReadings() }) { Text("🗑 Clear", color = Red) } } } }
        if (rows.isEmpty()) item { Empty("No readings for this month. Upload the CSV.") }
        items(rows) { r -> Panel { Row { Text(r.billNo, Modifier.weight(1f), fontSize = 16.sp)
            Text("${r.prevReading?.let { "%.0f".format(it) } ?: "?"} → ${"%.0f".format(r.currReading)}  =  ${"%.0f".format(r.units)}u  ${money(r.charge)}",
                color = if (r.endOnlyUnresolved) Red else MaterialTheme.colorScheme.onSurface) } } }
    }
}

// 2 ---------------------------------------------------------------- Bill Addition
@Composable fun AdditionScreen(vm: AppVM) {
    val bills = monthBills(vm); val pick = rememberTextPicker { vm.importBalances(it) }
    LazyColumn {
        item { Panel { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("🔄 Sync from Preparing") { vm.syncBills() }; BtnO("Carry balances") { vm.carryBalances() } }
            Spacer(Modifier.height(4.dp)); BtnO("📄 Upload previous balances CSV") { pick() }
            Text("Total: ${money(bills.sumOf { it.billAmount })} • ${bills.size} bills", Modifier.padding(top = 6.dp)) } }
        if (bills.isEmpty()) item { Empty("Nothing yet. Add readings, then Sync.") }
        items(bills) { b -> Panel { Row { Text(b.billNo, Modifier.weight(1f), fontSize = 16.sp); Text(money(b.billAmount), fontSize = 16.sp, color = Teal) }
            Lbl("${"%.0f".format(b.units)}u = ${money(b.unitsCharge)} • Rent ${money(b.rent)} • Extra ${money(b.extraTotal + b.extraCharge)} • Prev ${money(b.prevBalance)} • ${b.persons} persons") } }
    }
}

// 3 ---------------------------------------------------------------- Person Modify
@Composable fun PersonScreen(vm: AppVM) {
    val bills = monthBills(vm); var edit by remember { mutableStateOf<Bill?>(null) }
    LazyColumn { if (bills.isEmpty()) item { Empty("No bills this month.") }
        items(bills) { b -> Panel { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) {
            Text(b.billNo, fontSize = 16.sp); Lbl("${b.persons} persons (${b.extraPersons} extra) • Rent days ${b.rentDays} • Prev ${money(b.prevBalance)}") }
            OutlinedButton({ edit = b }) { Text("✏ Edit") } } } } }
    edit?.let { b -> var p by remember { mutableStateOf(b.persons.toString()) }; var pb by remember { mutableStateOf("%.0f".format(b.prevBalance)) }; var d by remember { mutableStateOf(b.rentDays.toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Modify ${b.billNo}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Persons staying", p, { p = it }); NumField("Previous balance ₹", pb, { pb = it }); NumField("Rent days (30 = full)", d, { d = it }) } },
            confirmButton = { TextButton({ vm.editBill(b, p.toIntOrNull() ?: b.persons, num(pb) ?: 0.0, (d.toIntOrNull() ?: 30).coerceIn(0, 31)); edit = null }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }) }
}

// 4 ---------------------------------------------------------------- Rent Update (groups)
@Composable fun RentUpdateScreen(vm: AppVM) {
    val gs by vm.groups.collectAsState(); var edit by remember { mutableStateOf<GroupE?>(null) }
    LazyColumn { item { Btn("＋ New group") { edit = GroupE("", pricePerUnit = vm.price) } }
        items(gs) { g -> Panel { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) {
            Text(g.name, fontSize = 17.sp); Lbl("Rent ${money(g.rent)} • limit ${g.limitedPerson} persons • extra ${money(g.extraPersonRent)}/person • ${g.pricePerUnit}/unit • extra charge ${money(g.extraCharge)} • ${g.billCount} files") }
            TextButton({ edit = g }) { Text("✏") }; TextButton({ vm.deleteGroup(g.name) }) { Text("🗑", color = Red) } } } } }
    edit?.let { g -> var n by remember { mutableStateOf(g.name) }; var r by remember { mutableStateOf(g.rent.toString()) }; var l by remember { mutableStateOf(g.limitedPerson.toString()) }
        var e by remember { mutableStateOf(g.extraPersonRent.toString()) }; var p by remember { mutableStateOf(g.pricePerUnit.toString()) }
        var x by remember { mutableStateOf(g.extraCharge.toString()) }; var c by remember { mutableStateOf(g.billCount.toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (g.name.isEmpty()) "New group" else "Edit ${g.name}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TxtField("Group letters (e.g. NS)", n, { n = it }); NumField("Rent ₹", r, { r = it }); NumField("Limited persons", l, { l = it })
            NumField("Extra person rent ₹", e, { e = it }); NumField("Price per unit ₹", p, { p = it }); NumField("Extra charge ₹", x, { x = it }); NumField("Number of files", c, { c = it }) } },
            confirmButton = { TextButton({ vm.saveGroup(GroupE(n, num(r) ?: 3000.0, l.toIntOrNull() ?: 3, num(e) ?: 500.0, num(p) ?: 8.5, num(x) ?: 0.0, (c.toIntOrNull() ?: 1).coerceAtLeast(1))); edit = null }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }) }
}

// 5 ---------------------------------------------------------------- Groups summary
@Composable fun GroupsScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); var f by remember { mutableStateOf("All") }
    val by = bills.groupBy { it.groupName }.toSortedMap()
    Column { Chips(listOf("All", "Paid", "Partial", "Unpaid"), f) { f = it }
        LazyColumn { items(by.entries.toList()) { (g, l) ->
            val list = l.filter { f == "All" || statusOf(it, pays[it.billNo]).name.equals(f, true) }
            Panel { Text("$g  (${list.size}/${l.size})", fontSize = 17.sp)
                Lbl("Total ${money(l.sumOf { it.billAmount })} • Received ${money(l.sumOf { pays[it.billNo]?.received ?: 0.0 })} • Balance ${money(l.sumOf { balanceOf(it, pays[it.billNo]) })}")
                Text(list.joinToString(" ") { it.billNo }, fontSize = 12.sp) } } } }
}

// 6 + 7 ------------------------------------------------------------ Bill Management / Filter By
@Composable fun ManageScreen(vm: AppVM, filterMode: Boolean) {
    val bills = monthBills(vm); val pays = monthPays(vm); val favs by vm.favs.collectAsState()
    var f by remember { mutableStateOf("All") }; var q by remember { mutableStateOf("") }; var pay by remember { mutableStateOf<Bill?>(null) }
    var vac by remember { mutableStateOf<Bill?>(null) }
    val list = bills.filter { billMatch(it.billNo, q) && (f == "All" || statusOf(it, pays[it.billNo]).name.equals(f, true)) }
    Column { TxtField("🔍 Search bill / group (NS5, NS)", q, { q = it }, Modifier.fillMaxWidth()); Chips(listOf("All", "Paid", "Partial", "Unpaid"), f) { f = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("TOTAL", money(list.sumOf { it.billAmount }), modifier = Modifier.weight(1f))
            Stat("RECEIVED", money(list.sumOf { pays[it.billNo]?.received ?: 0.0 }), Green, Modifier.weight(1f)); Stat("BALANCE", money(list.sumOf { balanceOf(it, pays[it.billNo]) }), Red, Modifier.weight(1f)) }
        LazyColumn { items(list) { b -> BillRow(b, pays[b.billNo], favs.any { it.billNo == b.billNo }, { pay = b }, { vm.toggleFav(b.billNo) }, { vm.undoLastPayment(b.billNo) })
            if (filterMode) TextButton({ vac = b }) { Text("🚪 Vacate ${b.billNo}") } } } }
    pay?.let { b -> PayDialog(b, pays[b.billNo], { pay = null }) { a, m -> vm.pay(b.billNo, a, m) } }
    vac?.let { b -> VacateDialog(vm, b, pays[b.billNo]) { vac = null } }
}

@Composable fun VacateDialog(vm: AppVM, b: Bill, p: Payment?, close: () -> Unit) {
    val tfs by vm.tenantFiles.collectAsState(); val tf = tfs.firstOrNull { it.fileName == b.billNo }
    val due = balanceOf(b, p); val adv = tf?.advance ?: 0.0; val adj = minOf(adv, due)
    var d by remember { mutableStateOf(LocalDate.now().toString()) }
    AlertDialog(onDismissRequest = close, title = { Text("Vacate ${b.billNo}") }, text = { Column {
        if (tf == null) Text("No tenant file for this bill — create it in Tenant File Manager first.", color = Red)
        Text("Due ${money(due)}\nAdvance ${money(adv)}\nAdjusted ${money(adj)}\nRepay tenant ${money(adv - adj)}\nCollect from tenant ${money(due - adj)}")
        TxtField("Vacate date (yyyy-mm-dd)", d, { d = it }, Modifier.padding(top = 6.dp)) } },
        confirmButton = { TextButton({ if (tf != null) vm.vacate(b.billNo, d, due, adv); close() }, enabled = tf != null) { Text("Vacate") } },
        dismissButton = { TextButton(close) { Text("Cancel") } })
}

// 8 ---------------------------------------------------------------- Reference
@Composable fun ReferenceScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); val ctx = LocalContext.current; val m by vm.month.collectAsState()
    val total = bills.sumOf { it.billAmount }; val rec = bills.sumOf { pays[it.billNo]?.received ?: 0.0 }
    val cash = pays.values.filter { it.mode == "CASH" }.sumOf { it.received }; val g = pays.values.filter { it.mode == "GPAY" }.sumOf { it.received }
    Column { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("BILLS", "${bills.size}", modifier = Modifier.weight(1f)); Stat("TOTAL", money(total), modifier = Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("RECEIVED", money(rec), Green, Modifier.weight(1f)); Stat("BALANCE", money(total - rec), Red, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("💵 CASH (last mode)", money(cash), modifier = Modifier.weight(1f)); Stat("📱 GPAY (last mode)", money(g), modifier = Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("PAID", "${bills.count { statusOf(it, pays[it.billNo]) == PayStatus.PAID }}", Green, Modifier.weight(1f))
            Stat("PARTIAL", "${bills.count { statusOf(it, pays[it.billNo]) == PayStatus.PARTIAL }}", Amber, Modifier.weight(1f)); Stat("UNPAID", "${bills.count { statusOf(it, pays[it.billNo]) == PayStatus.UNPAID }}", Red, Modifier.weight(1f)) }
        Btn("Export reference CSV") { Files.share(ctx, "reference_$m.csv", "text/csv", "Bill,Group,Units,Bill Amount,Received,Balance,Status\n" +
            bills.joinToString("\n") { "${it.billNo},${it.groupName},${it.units},${it.billAmount},${pays[it.billNo]?.received ?: 0},${balanceOf(it, pays[it.billNo])},${statusOf(it, pays[it.billNo])}" }) } }
}

// 9 ---------------------------------------------------------------- Payment History
@Composable fun HistoryScreen(vm: AppVM) {
    val logs by vm.logs.collectAsState(); val m by vm.month.collectAsState(); var q by remember { mutableStateOf("") }; var all by remember { mutableStateOf(false) }
    val l = logs.filter { (all || it.month == m) && billMatch(it.billNo, q) }
    Column { TxtField("🔍 Search bill", q, { q = it }, Modifier.fillMaxWidth()); Row(verticalAlignment = Alignment.CenterVertically) { Switch(all, { all = it }); Text(" All months") }
        Text("${l.size} payments • ${money(l.sumOf { it.paid })}")
        LazyColumn { items(l) { x -> Panel { Row { Text(x.billNo, Modifier.weight(1f), fontSize = 16.sp); Text(money(x.paid), color = Green) }
            Lbl("${fmtDay(x.date)} ${x.time} • ${x.mode} • ${money(x.oldBalance)} → ${money(x.newBalance)}") } } } }
}

// 10 --------------------------------------------------------------- Sending Message
@Composable fun MessageScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); val mm by vm.msgMap.collectAsState(); val tfs by vm.tenantFiles.collectAsState(); val ctx = LocalContext.current
    val map = mm.associate { it.billNo to it.mobile }; var q by remember { mutableStateOf("") }; var edit by remember { mutableStateOf<String?>(null) }
    val pick = rememberTextPicker { vm.saveMsg(parseMsgCsv(it)) }
    fun mobile(b: String) = map[b] ?: tfs.firstOrNull { it.fileName == b }?.active()?.firstOrNull { it.mobile.isNotBlank() }?.mobile ?: ""
    Column { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TxtField("🔍 Bill", q, { q = it }, Modifier.weight(1f)); BtnO("CSV") { pick() } }
        LazyColumn { items(bills.filter { billMatch(it.billNo, q) }) { b -> val mob = mobile(b.billNo); val txt = billText(b, pays[b.billNo])
            Panel { Text("${b.billNo} • ${mob.ifBlank { "no mobile" }}", fontSize = 15.sp); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton({ edit = b.billNo }) { Text("📱") }; OutlinedButton({ Files.sms(ctx, mob, txt) }, enabled = mob.isNotBlank()) { Text("SMS") }
                OutlinedButton({ Files.whatsapp(ctx, mob, txt) }, enabled = mob.isNotBlank()) { Text("WhatsApp") }; OutlinedButton({ Files.call(ctx, mob) }, enabled = mob.isNotBlank()) { Text("📞") } } } } }
    edit?.let { b -> var v by remember { mutableStateOf(mobile(b)) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Mobile for $b") }, text = { TxtField("Mobile", v, { v = it }) },
            confirmButton = { TextButton({ vm.saveMsg(listOf(MsgMap(b, v))); edit = null }) { Text("Save") } }, dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }) }
}

// 11 --------------------------------------------------------------- Tenant File Manager
@Composable fun TenantScreen(vm: AppVM) {
    val tfs by vm.tenantFiles.collectAsState(); val gs by vm.groups.collectAsState(); var edit by remember { mutableStateOf<TenantFile?>(null) }; var q by remember { mutableStateOf("") }
    val pick = rememberTextPicker { vm.importPersons(it) }; var bulk by remember { mutableStateOf(false) }; var att by remember { mutableStateOf<String?>(null) }
    Column { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("📁 Bulk") { bulk = true }; BtnO("＋ File") { edit = TenantFile("", "", "[]", 0.0, LocalDate.now().toString()) }; BtnO("⬆ CSV") { pick() } }
        Lbl("CSV: FileName,Name,Mobile,Aadhaar,EntryDate"); TxtField("🔍 File / group", q, { q = it }, Modifier.fillMaxWidth())
        LazyColumn { items(tfs.filter { billMatch(it.fileName, q) || it.groupName.contains(q.uppercase()) }.sortedWith { a, b -> billCompare.compare(a.fileName, b.fileName) }) { t ->
            Panel { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(t.fileName, fontSize = 16.sp)
                Lbl(if (t.active().isEmpty()) "VACANT" else t.active().joinToString { it.name } + " • Advance ${money(t.advance)}") }
                TextButton({ att = t.fileName }) { Text("📎") }; TextButton({ edit = t }) { Text("✏") }; TextButton({ vm.deleteTenantFile(t.fileName) }) { Text("🗑", color = Red) } } } } } }
    att?.let { AttachmentsDialog(it) { att = null } }
    if (bulk) AlertDialog(onDismissRequest = { bulk = false }, title = { Text("Create blank files for group") },
        text = { Column { if (gs.isEmpty()) Text("Create groups in Rent Update first."); gs.forEach { g -> TextButton({ vm.bulkCreateFiles(g.name); bulk = false }) { Text("${g.name} (${g.billCount} files)") } } } },
        confirmButton = { TextButton({ bulk = false }) { Text("Close") } })
    edit?.let { t -> var fn by remember { mutableStateOf(t.fileName) }; var adv by remember { mutableStateOf(t.advance.toString()) }; var ps by remember { mutableStateOf(t.persons()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Tenant file") }, text = { LazyColumn { item { TxtField("File name (NS5)", fn, { fn = it }); NumField("Advance ₹", adv, { adv = it }) }
            itemsIndexed2(ps) { i, p -> Column { Text("Person ${i + 1}"); TxtField("Name", p.name, { v -> ps = ps.toMutableList().also { it[i] = p.copy(name = v) } })
                TxtField("Mobile", p.mobile, { v -> ps = ps.toMutableList().also { it[i] = p.copy(mobile = v) } })
                TxtField("Aadhaar / ID", p.aadhaar, { v -> ps = ps.toMutableList().also { it[i] = p.copy(aadhaar = v) } })
                TxtField("Entry date", p.entryDate, { v -> ps = ps.toMutableList().also { it[i] = p.copy(entryDate = v) } })
                TxtField("Exit date (blank = staying)", p.exitDate, { v -> ps = ps.toMutableList().also { it[i] = p.copy(exitDate = v) } })
                TextButton({ ps = ps.filterIndexed { j, _ -> j != i } }) { Text("Remove", color = Red) } } }
            item { TextButton({ ps = ps + Person(entryDate = LocalDate.now().toString()) }) { Text("＋ Add person") } } } },
            confirmButton = { TextButton({ vm.saveTenantFile(t.copy(fileName = fn, groupName = groupLetters(fn), advance = num(adv) ?: 0.0).withPersons(ps)); edit = null }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }) }
}
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexed2(l: List<T>, c: @Composable (Int, T) -> Unit) { items(l.size) { c(it, l[it]) } }
