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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Files
import java.time.LocalDate

private data class FRow(val date: String, val kind: String, val amount: Double, val cat: String, val note: String, val id: Long)

/** Spending & Income: type filter (All / Spend / Income) and List / Date-wise / Month-wise views */
@Composable fun FinanceScreen(vm: AppVM) {
    val fin by vm.finance.collectAsState(); val eb by vm.eb.collectAsState(); val m by vm.month.collectAsState(); val logs by vm.logs.collectAsState()
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf("SPEND") }; var amt by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    var typeF by remember { mutableStateOf("All") }; var view by remember { mutableStateOf("List") }; var scope by remember { mutableStateOf("This month") }

    val all = remember(fin, eb, logs) {
        buildList {
            fin.forEach { add(FRow(it.date, it.kind, it.amount, it.category, it.note, it.id)) }
            logs.forEach { add(FRow(it.date, "INCOME", it.paid, "Rent", "Rent ${it.billNo} (${it.mode})", -1L)) }
            eb.forEach { if (it.paidAmount > 0) add(FRow(it.paidDate.ifBlank { it.month + "-01" }, "SPEND", it.paidAmount, "EB bill", "EB ${it.groupName} card ${it.cardNumber}", -1L)) }
        }
    }
    val rows = all.filter { r ->
        (scope == "All time" || r.date.startsWith(m)) &&
            (typeF == "All" || (typeF == "Income" && r.kind == "INCOME") || (typeF == "Spend" && r.kind == "SPEND"))
    }.sortedByDescending { it.date }
    val inc = rows.filter { it.kind == "INCOME" }.sumOf { it.amount }; val sp = rows.filter { it.kind == "SPEND" }.sumOf { it.amount }

    LazyColumn {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("INCOME", money(inc), Green, Modifier.weight(1f)); Stat("SPENDING", money(sp), Red, Modifier.weight(1f)); Stat("NET", money(inc - sp), modifier = Modifier.weight(1f))
            }
            Lbl("Income includes rent collected; spending includes EB bills paid")
            Chips(listOf("All", "Spend", "Income"), typeF) { typeF = it }
            Chips(listOf("List", "Date-wise", "Month-wise"), view) { view = it }
            Chips(listOf("This month", "All time"), scope) { scope = it }
        }
        item {
            Panel {
                H("Add entry"); Chips(listOf("SPEND", "INCOME"), kind) { kind = it }
                NumField("Amount ₹", amt, { amt = it }, Modifier.fillMaxWidth()); TxtField("Category", cat, { cat = it }, Modifier.fillMaxWidth()); TxtField("Note", note, { note = it }, Modifier.fillMaxWidth())
                Btn(if (kind == "SPEND") "Add Expense" else "Add Income") { num(amt)?.let { vm.addFinance(kind, it, cat, note, LocalDate.now().toString()); amt = ""; cat = ""; note = "" } }
                BtnO("⬇ Save CSV") { Files.share(ctx, "finance_${if (scope == "All time") "all" else m}.csv", "text/csv", "Date,Type,Amount,Category,Note\n" + rows.joinToString("\n") { "${it.date},${it.kind},${it.amount},${csvEsc(it.cat)},${csvEsc(it.note)}" }) }
            }
        }
        when (view) {
            "Date-wise" -> items(rows.groupBy { it.date }.entries.sortedByDescending { it.key }) { (d, l) -> SummaryRow(fmtDay(d), l) }
            "Month-wise" -> items(rows.groupBy { it.date.take(7) }.entries.sortedByDescending { it.key }) { (d, l) -> SummaryRow(monthLabel(d), l) }
            else -> items(rows) { f ->
                Panel { Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${if (f.kind == "INCOME") "💰" else "💸"} ${money(f.amount)} ${f.cat}"); Lbl("${fmtDay(f.date)} ${f.note}") }
                    if (f.id >= 0) TextButton({ vm.deleteFinance(f.id) }) { Text("🗑", color = Red) } } }
            }
        }
    }
}

@Composable private fun SummaryRow(title: String, l: List<FRow>) {
    val i = l.filter { it.kind == "INCOME" }.sumOf { it.amount }; val s = l.filter { it.kind == "SPEND" }.sumOf { it.amount }
    Panel {
        Row { Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text("Net ${money(i - s)}", fontWeight = FontWeight.Bold) }
        Row { Text("Income ${money(i)}", Modifier.weight(1f), color = Green); Text("Spend ${money(s)}", color = Red) }
        Lbl("${l.size} entries")
    }
}
