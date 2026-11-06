@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rentmanager.domain.monthLabel
import com.rentmanager.ui.*
import com.rentmanager.util.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { val vm: AppVM = viewModel(); val y by vm.yellow.collectAsState(); RentTheme(y) { Surface(color = MaterialTheme.colorScheme.background) { RentApp(vm) } } }
    }
}

val MENU = listOf("Bill Preparing", "Bill Addition", "Person Modify", "Rent Update", "Groups", "Bill Management", "Filter By",
    "Reference", "Payment History", "Sending Message", "Tenant File Manager", "Spending & Income", "Electric Bill",
    "EB Bill Generator", "Vacant", "Previous History", "Reset All", "⭐ Favourite", "🎤 Voice Payment",
    "📊 Export Excel/CSV", "📈 Usage Performance", "🛡 Data Safety")

@Composable fun RentApp(vm: AppVM) {
    val drawer = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope()
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val month by vm.month.collectAsState(); val host = remember { SnackbarHostState() }
    var tool by remember { mutableStateOf("") }; var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.toast.collect { host.showSnackbar(it) } }
    LaunchedEffect(Unit) { while (true) { clock = LocalTime.now().toString().take(8); delay(1000) } }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("RENT MANAGER V8", Modifier.padding(16.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            MENU.forEachIndexed { i, n -> NavigationDrawerItem(label = { Text("${i + 1}. $n") }, selected = sel == i,
                onClick = { sel = i; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 8.dp)) }
        } }
    }) {
        Scaffold(snackbarHost = { SnackbarHost(host) }, topBar = {
            TopAppBar(title = { Column { Text("RENT MANAGER", fontSize = 13.sp); Text(MENU[sel].uppercase(), fontSize = 9.sp) } },
                navigationIcon = { TextButton({ scope.launch { drawer.open() } }) { Text("☰", fontSize = 22.sp) } },
                actions = {
                    Text(clock, fontSize = 10.sp)
                    TextButton({ vm.toggleTheme() }) { Text("Y") }; TextButton({ tool = "note" }) { Text("N") }; TextButton({ tool = "calc" }) { Text("C") }
                })
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    TextButton({ vm.shift(-1) }) { Text("‹") }; Text(monthLabel(month), fontSize = 16.sp); TextButton({ vm.shift(1) }) { Text("›") }
                }
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { Screen(sel, vm) }
            }
        }
    }
    if (tool == "note") NotepadDialog(vm) { tool = "" }
    if (tool == "calc") CalcDialog { tool = "" }
}

@Composable fun NotepadDialog(vm: AppVM, close: () -> Unit) {
    var t by remember { mutableStateOf(vm.notes) }; val ctx = LocalContext.current
    AlertDialog(onDismissRequest = { vm.notes = t; close() }, title = { Text("📝 Notepad") },
        text = { OutlinedTextField(t, { t = it; vm.notes = it }, Modifier.fillMaxWidth().height(260.dp)) },
        confirmButton = { TextButton({ vm.notes = t; close() }) { Text("Close") } },
        dismissButton = { TextButton({ Files.share(ctx, "notes.txt", "text/plain", t) }) { Text("⬇ TXT") } })
}

@Composable fun CalcDialog(close: () -> Unit) {
    var e by remember { mutableStateOf("") }
    val keys = listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", "0", ".", "=", "+")
    AlertDialog(onDismissRequest = close, title = { Text("🧮 Calculator") }, text = { Column {
        Text(e.ifEmpty { "0" }, fontSize = 26.sp, modifier = Modifier.padding(8.dp))
        keys.chunked(4).forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { r.forEach { k ->
            OutlinedButton({ e = if (k == "=") calc(e) else e + k }, Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(k) } } } }
        TextButton({ e = "" }) { Text("Clear") }
    } }, confirmButton = { TextButton(close) { Text("Close") } })
}

private fun calc(s: String): String = try {
    val x = s.replace('×', '*').replace('÷', '/').replace('−', '-'); var i = 0
    fun peek() = if (i < x.length) x[i] else '\u0000'
    lateinit var expr: () -> Double
    fun atom(): Double { val st = i; while (peek().isDigit() || peek() == '.') i++; return x.substring(st, i).toDouble() }
    fun term(): Double { var v = atom(); while (peek() == '*' || peek() == '/') { val o = x[i++]; val r = atom(); v = if (o == '*') v * r else v / r }; return v }
    expr = { var v = term(); while (peek() == '+' || peek() == '-') { val o = x[i++]; val r = term(); v = if (o == '+') v + r else v - r }; v }
    val r = expr(); if (r % 1.0 == 0.0) r.toLong().toString() else "%.2f".format(r)
} catch (ex: Exception) { "Error" }
