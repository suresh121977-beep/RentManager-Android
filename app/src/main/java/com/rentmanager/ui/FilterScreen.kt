@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import java.time.LocalDate

@Composable fun FilterScreen(vm: AppVM) {
    var menu by remember { mutableStateOf("Dual Payment") }; var search by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("Dual Payment", "Collection", "Vacate", "New-comers"), menu) { menu = it }
        if (menu == "Dual Payment") TxtField("SEARCH BILL NUMBER (N, N1 …)", search, { search = it.uppercase() }, Modifier.fillMaxWidth())
        Box(Modifier.weight(1f)) {
            when (menu) {
                "Dual Payment" -> QuickPayPanel(vm, false, search)
                "Collection" -> CollectionPanel(vm)
                "Vacate" -> VacatePanel(vm)
                else -> NewcomersPanel(vm)
            }
        }
    }
}

/** Collection: Today / Week / Month / Date-to-date, group wise, GPay or Cash, totals */
@Composable fun CollectionPanel(vm: AppVM) {
    val logs by vm.logs.collectAsState(); val m by vm.month.collectAsState()
    val today = LocalDate.now()
    var period by remember { mutableStateOf("Today") }
    var from by remember { mutableStateOf(today.minusDays(7).toString()) }
    var to by remember { mutableStateOf(today.toString()) }
    var grp by remember { mutableStateOf("") }; var mode by remember { mutableStateOf("All") }

    val inPeriod = logs.filter { l ->
        when (period) {
            "Today" -> l.date == today.toString()
            "Week" -> l.date >= today.minusDays(6).toString() && l.date <= today.toString()
            "Month" -> l.month == m
            else -> l.date >= from && l.date <= to
        }
    }
    val groupsAll = inPeriod.map { groupLetters(it.billNo) }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = inPeriod.filter { (grp.isEmpty() || groupLetters(it.billNo) == grp) && (mode == "All" || it.mode == mode) }
    val gp = shown.filter { it.mode == "GPAY" }.sumOf { it.paid }; val ca = shown.filter { it.mode == "CASH" }.sumOf { it.paid }
    val byGroup = shown.groupBy { groupLetters(it.billNo).ifBlank { "-" } }.toSortedMap()

    LazyColumn {
        item {
            Chips(listOf("Today", "Week", "Month", "Date to date"), if (period == "Custom") "Date to date" else period) { period = if (it == "Date to date") "Custom" else it }
            if (period == "Custom") Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TxtField("From (yyyy-mm-dd)", from, { from = it }, Modifier.weight(1f)); TxtField("To", to, { to = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp)); GroupChips(groupsAll, grp) { grp = it }
            Chips(listOf("All", "GPAY", "CASH"), mode) { mode = it }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("💳 GPAY", money(gp), modifier = Modifier.weight(1f)); Stat("💵 CASH", money(ca), modifier = Modifier.weight(1f))
                Stat("TOTAL", money(gp + ca), Green, Modifier.weight(1f))
            }
            Lbl("${shown.size} payments")
        }
        item {
            Panel {
                Row { Text("GROUP", Modifier.weight(1f), fontSize = 11.sp); Text("GPAY", Modifier.weight(1f), fontSize = 11.sp); Text("CASH", Modifier.weight(1f), fontSize = 11.sp); Text("TOTAL", Modifier.weight(1f), fontSize = 11.sp) }
                HorizontalDivider()
                byGroup.forEach { (g, l) ->
                    val a = l.filter { it.mode == "GPAY" }.sumOf { it.paid }; val c = l.filter { it.mode == "CASH" }.sumOf { it.paid }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(g, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(money(a), Modifier.weight(1f)); Text(money(c), Modifier.weight(1f))
                        Text(money(a + c), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("ALL", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold); Text(money(gp), Modifier.weight(1f)); Text(money(ca), Modifier.weight(1f))
                    Text(money(gp + ca), Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, color = Green)
                }
            }
        }
        items(shown) { l ->
            Panel { Row { Text(l.billNo, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold); Text(money(l.paid), color = Green) }
                Lbl("${fmtDay(l.date)} ${l.time} • ${l.mode}") }
        }
    }
}

@Composable fun VacatePanel(vm: AppVM) {
    val tfs by vm.tenantFiles.collectAsState(); val vac by vm.vacated.collectAsState(); val m by vm.month.collectAsState()
    val bills by vm.bills.collectAsState(); val pays = monthPays(vm)
    var q by remember { mutableStateOf("") }; var sel by remember { mutableStateOf<Bill?>(null) }
    val files = tfs.filter { it.active().isNotEmpty() && billMatch(it.fileName, q) }.sortedWith { a, b -> billCompare.compare(a.fileName, b.fileName) }
    LazyColumn {
        item { TxtField("🔍 Search file (N, N1)", q, { q = it }, Modifier.fillMaxWidth()) }
        items(files) { t ->
            val b = bills.firstOrNull { it.billNo == t.fileName && it.month == m } ?: Bill(billNo = t.fileName, month = m, groupName = groupLetters(t.fileName))
            Panel { Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.fileName, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Lbl("${t.active().joinToString { it.name }} • Advance ${money(t.advance)} • Due ${money(balanceOf(b, pays[t.fileName]))}")
                }
                OutlinedButton({ sel = b }) { Text("🚪 Vacate") } } }
        }
        item { H("Vacated history") }
        items(vac) { v ->
            Panel { Text("${v.fileName} • ${fmtDay(v.vacatedDate)}", fontWeight = FontWeight.Bold)
                Lbl("${v.names} • due ${money(v.due)} • advance ${money(v.advance)} • rent adjusted ${money(v.adjusted)} • repay ${money(v.repay)} • collect ${money(v.collect)}") }
        }
    }
    sel?.let { b -> VacateDialog(vm, b, pays[b.billNo]) { sel = null } }
}

@Composable fun NewcomersPanel(vm: AppVM) {
    val tfs by vm.tenantFiles.collectAsState(); val gs by vm.groups.collectAsState(); val m by vm.month.collectAsState()
    val c = cycleFor(m)
    val rows = tfs.filter { it.active().isNotEmpty() && isFresher(it, m) }.sortedWith { a, b -> billCompare.compare(a.fileName, b.fileName) }
    LazyColumn {
        item { Lbl("Bill cycle ${fmtDay(c.start.toString())} → ${fmtDay(c.end.toString())}. Tenants who entered after ${fmtDay(c.start.toString())} are new-comers: no rent this cycle, only advance due.") }
        if (rows.isEmpty()) item { Empty("No new-comers in this cycle.") }
        items(rows) { t ->
            val g = groupFor(t.fileName, gs); val exp = expectedAdvance(g, t.active().size)
            Panel {
                Text("${t.fileName} • ${t.active().joinToString { it.name }}", fontWeight = FontWeight.Bold)
                Lbl("Entry ${fmtDay(entryDateOf(t)?.toString() ?: "")} • Advance paid ${money(t.advance)} / required ${money(exp)} • Due ${money(maxOf(0.0, exp - t.advance))}")
            }
        }
    }
}
