@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import androidx.compose.foundation.clickable
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
import java.time.LocalDate

@Composable fun Screen(i: Int, vm: AppVM) = when (i) {
    0 -> PreparingScreen(vm); 1 -> AdditionScreen(vm); 2 -> PersonScreen(vm); 3 -> RentUpdateScreen(vm)
    4 -> GroupsScreen(vm); 5 -> ManageScreen(vm); 6 -> FilterScreen(vm); 7 -> ReferenceScreen(vm)
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

// 2 ---------------------------------------------------------------- Bill Addition
@Composable fun AdditionScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); val pick = rememberTextPicker { vm.importBalances(it) }
    Column(Modifier.fillMaxSize()) {
        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Btn("🔄 Sync from Preparing") { vm.syncBills() }; BtnO("Carry balances") { vm.carryBalances() } }
            BtnO("📄 Upload previous balances CSV") { pick() }
            Text("Total: ${money(bills.sumOf { it.billAmount })} • ${bills.size} bills", Modifier.padding(top = 4.dp))
        }
        QuickHost(vm, bills, pays, Modifier.weight(1f)) { open ->
            LazyColumn {
                if (bills.isEmpty()) item { Empty("Nothing yet. Add readings, then Sync.") }
                items(bills) { b ->
                    Panel(Modifier.clickable { open(b) }) {
                        Row { Text(b.billNo, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                            Text(if (b.rentDays < 0 && b.billAmount <= 0) "–" else money(b.billAmount), fontSize = 16.sp, color = Teal) }
                        Lbl(if (b.rentDays < 0) "NEW tenant • no rent • advance due ${money(b.extraCharge)} • ${"%.0f".format(b.units)}u • Prev ${money(b.prevBalance)}"
                            else "${"%.0f".format(b.units)}u = ${money(b.unitsCharge)} • Rent ${money(b.rent)} • Extra ${money(b.extraTotal + b.extraCharge)} • Prev ${money(b.prevBalance)} • ${b.persons} persons")
                    }
                }
            }
        }
    }
}

// 3 ---------------------------------------------------------------- Person Modify
@Composable fun PersonScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); var edit by remember { mutableStateOf<Bill?>(null) }
    QuickHost(vm, bills, pays) { open ->
        LazyColumn {
            if (bills.isEmpty()) item { Empty("No bills this month.") }
            items(bills) { b ->
                Panel(Modifier.clickable { open(b) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.billNo, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                            Lbl("${b.persons} persons (${b.extraPersons} extra) • " + (if (b.rentDays < 0) "New tenant" else "Rent days ${b.rentDays}") + " • Prev ${money(b.prevBalance)}")
                        }
                        OutlinedButton({ edit = b }) { Text("✏ Edit") }
                    }
                }
            }
        }
    }
    edit?.let { b ->
        var p by remember { mutableStateOf(b.persons.toString()) }; var pb by remember { mutableStateOf("%.0f".format(b.prevBalance)) }
        var d by remember { mutableStateOf((b.rentDays.takeIf { it >= 0 } ?: 30).toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Modify ${b.billNo}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Persons staying", p, { p = it }); NumField("Previous balance ₹", pb, { pb = it }); NumField("Rent days (30 = full)", d, { d = it })
            Lbl("If a tenant file exists, persons come from the tenant file.") } },
            confirmButton = { TextButton({ vm.editBill(b, p.toIntOrNull() ?: b.persons, num(pb) ?: 0.0, (d.toIntOrNull() ?: 30).coerceIn(0, 31)); edit = null }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } })
    }
}

// 4 ---------------------------------------------------------------- Rent Update (groups)
@Composable fun RentUpdateScreen(vm: AppVM) {
    val gs by vm.groups.collectAsState(); var edit by remember { mutableStateOf<GroupE?>(null) }; var add by remember { mutableStateOf("") }
    LazyColumn {
        item {
            Panel {
                Lbl("Add groups (NS, N, AB)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TxtField("NS, N, AB", add, { add = it }, Modifier.weight(1f)); Spacer(Modifier.width(6.dp))
                    Btn("Add") {
                        add.split(",").map { norm(it) }.filter { n -> n.isNotEmpty() && gs.none { it.name == n } }.forEach { vm.saveGroup(GroupE(it, pricePerUnit = vm.price)) }
                        add = ""
                    }
                }
            }
        }
        items(gs) { g ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${g.name} • ${g.billCount} bills", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Lbl("Rent ${money(g.rent)} • Limit ${g.limitedPerson} persons • Extra person ${money(g.extraPersonRent)}")
                        Lbl("Limit advance ${money(g.limitAdvance)} • Extra person advance ${money(g.extraPersonAdvance)}")
                        Lbl("Extra charge ${money(g.extraCharge)} • Price/unit ${g.pricePerUnit}")
                    }
                    TextButton({ edit = g }) { Text("✏") }; TextButton({ vm.deleteGroup(g.name) }) { Text("🗑", color = Red) }
                }
            }
        }
    }
    edit?.let { g ->
        var n by remember { mutableStateOf(g.name) }; var r by remember { mutableStateOf(g.rent.toString()) }; var l by remember { mutableStateOf(g.limitedPerson.toString()) }
        var e by remember { mutableStateOf(g.extraPersonRent.toString()) }; var la by remember { mutableStateOf(g.limitAdvance.toString()) }
        var ea by remember { mutableStateOf(g.extraPersonAdvance.toString()) }; var p by remember { mutableStateOf(g.pricePerUnit.toString()) }
        var x by remember { mutableStateOf(g.extraCharge.toString()) }; var c by remember { mutableStateOf(g.billCount.toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (g.name.isEmpty()) "New group" else "Edit ${g.name}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TxtField("Group letters (e.g. NS)", n, { n = it }); NumField("Rent ₹", r, { r = it })
                NumField("Limit (persons)", l, { l = it }); NumField("Extra person rent ₹", e, { e = it })
                H("Advance"); NumField("Limit advance ₹ (for limited persons)", la, { la = it }); NumField("Extra person advance ₹ (each)", ea, { ea = it })
                H("Other"); NumField("Extra charge ₹", x, { x = it }); NumField("Price per unit ₹", p, { p = it }); NumField("Number of files", c, { c = it })
            } },
            confirmButton = { TextButton({
                vm.saveGroup(GroupE(n, num(r) ?: 3000.0, l.toIntOrNull() ?: 3, num(e) ?: 500.0, num(p) ?: 8.5, num(x) ?: 0.0,
                    (c.toIntOrNull() ?: 1).coerceAtLeast(1), num(la) ?: 0.0, num(ea) ?: 0.0)); edit = null }) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } })
    }
}

// 5 ---------------------------------------------------------------- Groups summary
@Composable fun GroupsScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); var f by remember { mutableStateOf("All") }
    val by = bills.groupBy { it.groupName }.toSortedMap()
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("All", "Paid", "Partial", "Unpaid"), f) { f = it }
        QuickHost(vm, bills.filter { f == "All" || statusOf(it, pays[it.billNo]).name.equals(f, true) }, pays, Modifier.weight(1f)) { _ ->
            LazyColumn {
                items(by.entries.toList()) { (g, l) ->
                    val list = l.filter { f == "All" || statusOf(it, pays[it.billNo]).name.equals(f, true) }
                    Panel {
                        Text("$g  (${list.size}/${l.size})", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Lbl("Total ${money(l.sumOf { it.billAmount })} • Received ${money(l.sumOf { pays[it.billNo]?.received ?: 0.0 })} • Balance ${money(l.sumOf { balanceOf(it, pays[it.billNo]) })}")
                        Text(list.joinToString(" ") { it.billNo }, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// 6 ---------------------------------------------------------------- Bill Management
@Composable fun ManageScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); val favs by vm.favs.collectAsState()
    var f by remember { mutableStateOf("All") }; var q by remember { mutableStateOf("") }; var pay by remember { mutableStateOf<Bill?>(null) }
    val list = bills.filter { billMatch(it.billNo, q) && (f == "All" || statusOf(it, pays[it.billNo]).name.equals(f, true)) }
    Column(Modifier.fillMaxSize()) {
        TxtField("🔍 Search bill / group (NS5, NS)", q, { q = it }, Modifier.fillMaxWidth()); Chips(listOf("All", "Paid", "Partial", "Unpaid"), f) { f = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("TOTAL", money(list.sumOf { it.billAmount }), modifier = Modifier.weight(1f))
            Stat("RECEIVED", money(list.sumOf { pays[it.billNo]?.received ?: 0.0 }), Green, Modifier.weight(1f))
            Stat("BALANCE", money(list.sumOf { balanceOf(it, pays[it.billNo]) }), Red, Modifier.weight(1f))
        }
        QuickHost(vm, list, pays, Modifier.weight(1f)) { open ->
            LazyColumn {
                items(list) { b ->
                    BillRow(b, pays[b.billNo], favs.any { it.billNo == b.billNo }, { pay = b }, { vm.toggleFav(b.billNo) }, { vm.undoLastPayment(b.billNo) }, { open(b) })
                }
            }
        }
    }
    pay?.let { b -> PayDialog(b, pays[b.billNo], { pay = null }) { a, m -> vm.pay(b.billNo, a, m) } }
}

@Composable fun VacateDialog(vm: AppVM, b: Bill, p: Payment?, close: () -> Unit) {
    val tfs by vm.tenantFiles.collectAsState(); val tf = tfs.firstOrNull { it.fileName == b.billNo }
    val due = balanceOf(b, p); val adv = tf?.advance ?: 0.0; val adj = minOf(adv, due)
    var d by remember { mutableStateOf(LocalDate.now().toString()) }
    AlertDialog(onDismissRequest = close, title = { Text("Vacate ${b.billNo}") }, text = { Column {
        if (tf == null) Text("No tenant file for this bill — create it in Tenant File Manager first.", color = Red)
        Text("Due ${money(due)}\nAdvance ${money(adv)}\nRent / dues adjusted ${money(adj)}\nRepay tenant ${money(adv - adj)}\nCollect from tenant ${money(due - adj)}")
        TxtField("Vacate date (yyyy-mm-dd)", d, { d = it }, Modifier.padding(top = 6.dp)) } },
        confirmButton = { TextButton({ if (tf != null) vm.vacate(b.billNo, d, due, adv); close() }, enabled = tf != null) { Text("Vacate") } },
        dismissButton = { TextButton(close) { Text("Cancel") } })
}

// 8 ---------------------------------------------------------------- Reference
@Composable fun ReferenceScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); val ctx = LocalContext.current; val m by vm.month.collectAsState()
    val total = bills.sumOf { it.billAmount }; val rec = bills.sumOf { pays[it.billNo]?.received ?: 0.0 }
    val cash = pays.values.filter { it.mode == "CASH" }.sumOf { it.received }; val g = pays.values.filter { it.mode == "GPAY" }.sumOf { it.received }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("BILLS", "${bills.size}", modifier = Modifier.weight(1f)); Stat("TOTAL", money(total), modifier = Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("RECEIVED", money(rec), Green, Modifier.weight(1f)); Stat("BALANCE", money(total - rec), Red, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("💵 CASH (last mode)", money(cash), modifier = Modifier.weight(1f)); Stat("📱 GPAY (last mode)", money(g), modifier = Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("PAID", "${bills.count { statusOf(it, pays[it.billNo]) == PayStatus.PAID }}", Green, Modifier.weight(1f))
            Stat("PARTIAL", "${bills.count { statusOf(it, pays[it.billNo]) == PayStatus.PARTIAL }}", Amber, Modifier.weight(1f))
            Stat("UNPAID", "${bills.count { statusOf(it, pays[it.billNo]) == PayStatus.UNPAID }}", Red, Modifier.weight(1f))
        }
        Btn("Export reference CSV") { Files.share(ctx, "reference_$m.csv", "text/csv", "Bill,Group,Units,Bill Amount,Received,Balance,Status\n" +
            bills.joinToString("\n") { "${it.billNo},${it.groupName},${it.units},${it.billAmount},${pays[it.billNo]?.received ?: 0},${balanceOf(it, pays[it.billNo])},${statusOf(it, pays[it.billNo])}" }) }
    }
}

// 9 ---------------------------------------------------------------- Payment History
@Composable fun HistoryScreen(vm: AppVM) {
    val logs by vm.logs.collectAsState(); val m by vm.month.collectAsState(); var q by remember { mutableStateOf("") }; var all by remember { mutableStateOf(false) }
    val l = logs.filter { (all || it.month == m) && billMatch(it.billNo, q) }
    Column {
        TxtField("🔍 Search bill", q, { q = it }, Modifier.fillMaxWidth()); Row(verticalAlignment = Alignment.CenterVertically) { Switch(all, { all = it }); Text(" All months") }
        Text("${l.size} payments • ${money(l.sumOf { it.paid })}")
        LazyColumn {
            items(l) { x ->
                Panel { Row { Text(x.billNo, Modifier.weight(1f), fontSize = 16.sp); Text(money(x.paid), color = Green) }
                    Lbl("${fmtDay(x.date)} ${x.time} • ${x.mode} • ${money(x.oldBalance)} → ${money(x.newBalance)}") }
            }
        }
    }
}

// 10 --------------------------------------------------------------- Sending Message (direct bulk SMS)
@Composable fun MessageScreen(vm: AppVM) {
    val bills = monthBills(vm); val pays = monthPays(vm); val mm by vm.msgMap.collectAsState(); val tfs by vm.tenantFiles.collectAsState(); val ctx = LocalContext.current
    val map = mm.associate { it.billNo to it.mobile }; var q by remember { mutableStateOf("") }; var edit by remember { mutableStateOf<String?>(null) }
    val pick = rememberTextPicker { vm.saveMsg(parseMsgCsv(it)) }; val sender = rememberSmsSender(); val caller = rememberCaller()
    fun mobile(b: String) = map[b] ?: tfs.firstOrNull { it.fileName == b }?.active()?.firstOrNull { it.mobile.isNotBlank() }?.mobile ?: ""
    val shown = bills.filter { billMatch(it.billNo, q) }
    val unpaid = shown.filter { mobile(it.billNo).isNotBlank() && balanceOf(it, pays[it.billNo]) > 0 }
    val withMobile = shown.filter { mobile(it.billNo).isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TxtField("🔍 Bill", q, { q = it }, Modifier.weight(1f)); BtnO("CSV") { pick() } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("📤 Send to ${unpaid.size} unpaid") {
                sender(unpaid.map { mobile(it.billNo) to billText(it, pays[it.billNo]) }) { ok, fail -> vm.toastMsg("SMS sent: $ok • failed: $fail") }
            }
            BtnO("All ${withMobile.size}") {
                sender(withMobile.map { mobile(it.billNo) to billText(it, pays[it.billNo]) }) { ok, fail -> vm.toastMsg("SMS sent: $ok • failed: $fail") }
            }
        }
        Lbl("Messages are sent straight from the app – no message box opens. Allow the SMS permission once.")
        LazyColumn {
            items(shown) { b ->
                val mob = mobile(b.billNo); val txt = billText(b, pays[b.billNo])
                Panel {
                    Text("${b.billNo} • ${mob.ifBlank { "no mobile" }}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton({ edit = b.billNo }) { Text("📱") }
                        OutlinedButton({ sender(listOf(mob to txt)) { ok, _ -> vm.toastMsg(if (ok > 0) "SMS sent to ${b.billNo} ✓" else "SMS not sent") } }, enabled = mob.isNotBlank()) { Text("SMS") }
                        OutlinedButton({ Files.whatsapp(ctx, mob, txt) }, enabled = mob.isNotBlank()) { Text("WhatsApp") }
                        OutlinedButton({ caller(mob) }, enabled = mob.isNotBlank()) { Text("📞") }
                    }
                }
            }
        }
    }
    edit?.let { b ->
        var v by remember { mutableStateOf(mobile(b)) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Mobile for $b") }, text = { TxtField("Mobile", v, { v = it }) },
            confirmButton = { TextButton({ vm.saveMsg(listOf(MsgMap(b, v))); edit = null }) { Text("Save") } }, dismissButton = { TextButton({ edit = null }) { Text("Cancel") } })
    }
}
