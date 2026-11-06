@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.rentmanager.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.rentmanager.util.Attachments
import com.rentmanager.util.Files
import com.rentmanager.util.Forms
import com.rentmanager.util.Voice
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@Composable fun TenantScreen(vm: AppVM) {
    val tfs by vm.tenantFiles.collectAsState()
    var open by remember { mutableStateOf("") }
    val t0 = tfs.firstOrNull { it.fileName == open }
    if (t0 != null) TenantDetail(vm, t0) { open = "" } else TenantList(vm, tfs) { open = it }
}

@Composable fun TenantList(vm: AppVM, tfs: List<TenantFile>, onOpen: (String) -> Unit) {
    val gs by vm.groups.collectAsState(); val m by vm.month.collectAsState()
    var edit by remember { mutableStateOf<TenantFile?>(null) }; var q by remember { mutableStateOf("") }
    var bulk by remember { mutableStateOf(false) }; var att by remember { mutableStateOf("") }; var reset by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("Table") }
    val pick = rememberTextPicker { vm.importPersons(it) }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("📁 Bulk") { bulk = true }; BtnO("＋ File") { edit = TenantFile("", "", "[]", 0.0, LocalDate.now().toString()) }
            BtnO("⬆ CSV") { pick() }; BtnO("🕘 Reset dates") { reset = true }
        }
        Chips(listOf("Table", "Quick"), mode) { mode = it }
        Box(Modifier.weight(1f)) {
            if (mode == "Quick") QuickTenantView(vm, tfs, onOpen) else Column {
                TxtField("🔍 File / group", q, { q = it }, Modifier.fillMaxWidth())
                LazyColumn {
                    items(tfs.filter { billMatch(it.fileName, q) || it.groupName.contains(q.uppercase()) }.sortedWith { a, b -> billCompare.compare(a.fileName, b.fileName) }) { t ->
                        Panel(Modifier.clickable { onOpen(t.fileName) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.fileName, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                                    Lbl(if (t.active().isEmpty()) "VACANT" else t.active().joinToString { it.name } +
                                        (if (isFresher(t, m)) " • NEW" else "") + " • Advance ${money(t.advance)}")
                                }
                                TextButton({ att = t.fileName }) { Text("📎") }; TextButton({ edit = t }) { Text("✏") }
                                TextButton({ vm.deleteTenantFile(t.fileName) }) { Text("🗑", color = Red) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (att.isNotEmpty()) AttachmentsDialog(att) { att = "" }
    if (bulk) AlertDialog(onDismissRequest = { bulk = false }, title = { Text("Create blank files for group") },
        text = { Column { if (gs.isEmpty()) Text("Create groups in Rent Update first."); gs.forEach { g -> TextButton({ vm.bulkCreateFiles(g.name); bulk = false }) { Text("${g.name} (${g.billCount} files)") } } } },
        confirmButton = { TextButton({ bulk = false }) { Text("Close") } })
    edit?.let { t -> TenantEditDialog(vm, t) { edit = null } }
    if (reset) {
        var d by remember { mutableStateOf(cycleFor(m).start.toString()) }
        AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset tenant dates") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Every tenant who entered after this date gets this entry date, so ALL tenants count as old tenants (rent is charged normally).", fontSize = 13.sp)
            TxtField("Entry date (yyyy-mm-dd)", d, { d = it }) } },
            confirmButton = { TextButton({ vm.resetEntryDates(d); reset = false }) { Text("Apply to all") } },
            dismissButton = { TextButton({ reset = false }) { Text("Cancel") } })
    }
}

/** Group → numbers → choose Photo / ID / Mobile / Name / Persons */
@Composable fun QuickTenantView(vm: AppVM, tfs: List<TenantFile>, onOpen: (String) -> Unit) {
    var g by remember { mutableStateOf("") }; var sel by remember { mutableStateOf("") }; var what by remember { mutableStateOf("Mobile") }
    val caller = rememberCaller(); val sender = rememberSmsSender()
    val groups = tfs.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = tfs.filter { g.isEmpty() || it.groupName == g }.sortedWith { a, b -> billCompare.compare(a.fileName, b.fileName) }
    val t = tfs.firstOrNull { it.fileName == sel }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        GroupChips(groups, g) { g = it; sel = "" }
        Spacer(Modifier.height(6.dp))
        Lbl("Green = occupied • Red = vacant")
        NumberGrid(shown.map { it.fileName to (if (it.active().isEmpty()) Red else Green) }) { sel = it }
        if (t != null) Panel {
            Text(t.fileName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Chips(listOf("Photo", "ID", "Mobile", "Name", "Persons"), what) { what = it }
            Spacer(Modifier.height(6.dp))
            when (what) {
                "Photo" -> AttachGrid(t.fileName, "Photo")
                "ID" -> AttachGrid(t.fileName, "ID_Proof")
                "Mobile" -> {
                    if (t.active().isEmpty()) Text("Vacant")
                    t.active().forEach { p -> ContactRow(vm, p, caller, sender) }
                }
                "Name" -> t.active().forEach { Text("• ${it.name}", fontSize = 15.sp) }
                else -> { Text("${t.active().size} persons", fontSize = 16.sp); t.active().forEach { Lbl("${it.name} • entry ${fmtDay(it.entryDate)}") } }
            }
            Spacer(Modifier.height(6.dp)); BtnO("Open full file") { onOpen(t.fileName) }
        }
    }
}

@Composable fun AttachGrid(fileName: String, type: String) {
    val ctx = LocalContext.current
    var files by remember(fileName, type) { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(fileName, type) { files = Attachments.list(ctx, fileName, type) }
    if (files.isEmpty()) Lbl("No ${Attachments.LABEL[type] ?: type} files yet")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { f -> Thumb(f, 96.dp) { Attachments.open(ctx, f) } }
    }
}

/** Name + mobile with direct Call, direct SMS (no message box) and WhatsApp */
@Composable fun ContactRow(vm: AppVM, p: Person, caller: (String) -> Unit, sender: (List<Pair<String, String>>, (Int, Int) -> Unit) -> Unit) {
    val ctx = LocalContext.current; var msg by remember { mutableStateOf("") }
    Column(Modifier.padding(vertical = 4.dp)) {
        Text("${p.name} • ${p.mobile.ifBlank { "no mobile" }}", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ caller(p.mobile) }, enabled = p.mobile.isNotBlank()) { Text("📞 Call") }
            OutlinedButton({ Files.whatsapp(ctx, p.mobile, msg) }, enabled = p.mobile.isNotBlank()) { Text("WhatsApp") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TxtField("SMS text", msg, { msg = it }, Modifier.weight(1f)); Spacer(Modifier.width(6.dp))
            Button({ sender(listOf(p.mobile to msg)) { ok, _ -> vm.toastMsg(if (ok > 0) "SMS sent ✓" else "SMS not sent (allow SMS permission)") }; msg = "" },
                enabled = p.mobile.isNotBlank() && msg.isNotBlank()) { Text("Send") }
        }
    }
}

// ------------------------------------------------------------------ detail (Advance / Persons / History / Vacate)
@Composable fun TenantDetail(vm: AppVM, t: TenantFile, onBack: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val txns by vm.advTxns.collectAsState(); val gs by vm.groups.collectAsState(); val vac by vm.vacated.collectAsState()
    val m by vm.month.collectAsState(); val bills by vm.bills.collectAsState(); val pays by vm.payments.collectAsState()
    var tab by remember { mutableStateOf("Advance") }; var edit by remember { mutableStateOf(false) }; var advKind by remember { mutableStateOf("") }
    var person by remember { mutableStateOf<Person?>(null) }; var vacDialog by remember { mutableStateOf(false) }; var att by remember { mutableStateOf(false) }
    val g = groupFor(t.fileName, gs); val active = t.active()
    val my = txns.filter { it.fileName == t.fileName }.sortedBy { it.date }
    val myVac = vac.filter { it.fileName == t.fileName }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ Back") }
            Text(t.fileName, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            TextButton({ att = true }) { Text("📎") }; TextButton({ edit = true }) { Text("✏ Edit") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BtnO("📄 Form (HTML)") { scope.launch { val html = Forms.tenantHtml(ctx, t, g, my); Files.share(ctx, "TenantForm_${t.fileName}.html", "text/html", html) } }
        }
        Chips(listOf("Advance", "Persons", "History", "Vacate"), tab) { tab = it }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Advance" -> LazyColumn {
                    item {
                        val exp = expectedAdvance(g, active.size)
                        Panel {
                            Text("Advance balance ${money(t.advance)}", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Lbl("Required ${money(exp)} = limit advance ${money(g?.limitAdvance ?: 0.0)} + extra persons × ${money(g?.extraPersonAdvance ?: 0.0)}")
                            Text("Due ${money(maxOf(0.0, exp - t.advance))}", color = Red, fontWeight = FontWeight.Bold)
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Btn("＋ Add", Modifier.weight(1f)) { advKind = "CREDIT" }; BtnO("− Debit", Modifier.weight(1f)) { advKind = "DEBIT" }
                            }
                        }
                    }
                    if (my.isEmpty()) item { Empty("No advance entries yet.") }
                    items(my.reversed()) { x ->
                        Panel {
                            Row { Text("${fmtDay(x.date)} • ${if (x.kind == "CREDIT") "Paid" else "Withdrawal"}", Modifier.weight(1f))
                                Text(money(x.amount), color = if (x.kind == "CREDIT") Green else Red, fontWeight = FontWeight.Bold) }
                            if (x.note.isNotBlank()) Lbl(x.note)
                            TextButton({ vm.deleteAdvTxn(x) }) { Text("🗑", color = Red) }
                        }
                    }
                }
                "Persons" -> LazyColumn {
                    if (t.persons().isEmpty()) item { Empty("No persons. Tap ✏ Edit to add.") }
                    items(t.persons()) { p ->
                        Panel(Modifier.clickable { person = p }) {
                            Text(p.name, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Lbl("${p.mobile} • entry ${fmtDay(p.entryDate)}" + if (p.exitDate.isNotBlank()) " • exit ${fmtDay(p.exitDate)}" else " • staying")
                        }
                    }
                }
                "History" -> LazyColumn {
                    item { H("Persons") }
                    items(t.persons()) { p -> Panel { Text(p.name, fontWeight = FontWeight.Bold); Lbl("Entry ${fmtDay(p.entryDate)} • Exit ${if (p.exitDate.isBlank()) "-" else fmtDay(p.exitDate)}") } }
                    item { H("Advance by date") }
                    if (my.isEmpty()) item { Empty("No advance entries.") }
                    items(my) { x -> Panel { Row { Text("${fmtDay(x.date)} • ${if (x.kind == "CREDIT") "Paid advance" else "Withdrawn"}", Modifier.weight(1f)); Text(money(x.amount), color = if (x.kind == "CREDIT") Green else Red) }; if (x.note.isNotBlank()) Lbl(x.note) } }
                }
                else -> LazyColumn {
                    item { Btn("🚪 Vacate now") { vacDialog = true } }
                    item { H("Vacate records") }
                    if (myVac.isEmpty()) item { Empty("Not vacated yet.") }
                    items(myVac) { v ->
                        Panel {
                            Text("Vacated ${fmtDay(v.vacatedDate)}", fontWeight = FontWeight.ExtraBold); Lbl(v.names)
                            Text("Advance paid ${money(v.advance)}"); Text("Rent / dues adjusted ${money(v.adjusted)}")
                            if (v.repay > 0) Text("Final: repay to tenant ${money(v.repay)}", color = Green, fontWeight = FontWeight.Bold)
                            if (v.collect > 0) Text("Final: collect from tenant ${money(v.collect)}", color = Red, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    if (att) AttachmentsDialog(t.fileName) { att = false }
    if (edit) TenantEditDialog(vm, t) { edit = false }
    if (advKind.isNotEmpty()) AdvanceDialog(advKind, { advKind = "" }) { a, d, n -> vm.addAdvance(t.fileName, advKind, a, d, n) }
    person?.let { p -> PersonDialog(t, p) { person = null } }
    if (vacDialog) {
        val b = bills.firstOrNull { it.billNo == t.fileName && it.month == m } ?: Bill(billNo = t.fileName, month = m, groupName = groupLetters(t.fileName))
        VacateDialog(vm, b, pays.firstOrNull { it.billNo == t.fileName && it.month == m }) { vacDialog = false }
    }
}

@Composable fun AdvanceDialog(kind: String, onDismiss: () -> Unit, onSave: (Double, String, String) -> Unit) {
    var a by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }; var d by remember { mutableStateOf(LocalDate.now().toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (kind == "CREDIT") "Add advance" else "Debit advance") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { NumField("Amount ₹", a, { a = it }); TxtField("Date (yyyy-mm-dd)", d, { d = it }); TxtField("Note", note, { note = it }) } },
        confirmButton = { TextButton({ num(a)?.takeIf { it > 0 }?.let { onSave(it, d, note); onDismiss() } }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable fun PersonDialog(t: TenantFile, p: Person, onDismiss: () -> Unit) {
    val caller = rememberCaller()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(p.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf("Name" to p.name, "Mobile" to p.mobile, "Aadhaar / ID" to p.aadhaar, "Age" to p.age, "Address" to p.address,
                "Entry date" to fmtDay(p.entryDate), "Exit date" to (if (p.exitDate.isBlank()) "Staying" else fmtDay(p.exitDate))).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(k, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v.ifBlank { "-" }, fontSize = 13.sp) }
                HorizontalDivider()
            }
            Spacer(Modifier.height(8.dp)); Text("Documents", fontWeight = FontWeight.Bold)
            Lbl("Photo"); AttachGrid(t.fileName, "Photo")
            Lbl("ID proof"); AttachGrid(t.fileName, "ID_Proof")
            if (p.mobile.isNotBlank()) Button({ caller(p.mobile) }, Modifier.padding(top = 8.dp)) { Text("📞 Call ${p.mobile}") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Add / edit persons. "🎤 Add by voice" listens once and auto-fills the form. */
@Composable fun TenantEditDialog(vm: AppVM, t0: TenantFile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var fn by remember { mutableStateOf(t0.fileName) }; var ps by remember { mutableStateOf(t0.persons()) }
    val voice = remember { Voice(ctx) }; DisposableEffect(Unit) { onDispose { voice.shutdown() } }
    var listening by remember { mutableStateOf(false) }; var heard by remember { mutableStateOf("") }; var wantIdx by remember { mutableIntStateOf(-1) }

    fun fill(i: Int, text: String) {
        val r = parsePersonVoice(text)
        ps = ps.toMutableList().also { l ->
            if (i in l.indices) { val p = l[i]
                l[i] = p.copy(name = r.name ?: p.name, mobile = r.mobile ?: p.mobile, aadhaar = r.aadhaar ?: p.aadhaar, age = r.age ?: p.age, address = r.address ?: p.address) }
        }
    }
    fun doListen(i: Int) {
        listening = true
        voice.listen(onText = { h -> listening = false; heard = h; fill(i, h) }, onError = { e -> listening = false; heard = e })
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && wantIdx >= 0) doListen(wantIdx)
        wantIdx = -1
    }
    fun speakFill(i: Int) { if (voice.hasMic()) doListen(i) else { wantIdx = i; micPerm.launch(Manifest.permission.RECORD_AUDIO) } }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (t0.fileName.isEmpty()) "New tenant file" else "Edit ${t0.fileName}") }, text = {
        LazyColumn {
            item {
                TxtField("File name (NS5)", fn, { fn = it }, Modifier.fillMaxWidth())
                Button({ val i = ps.size; ps = ps + Person(entryDate = LocalDate.now().toString()); speakFill(i) }, Modifier.padding(top = 6.dp)) { Text(if (listening) "🎙 Listening…" else "🎤 Add person by voice") }
                Lbl("Say: name Ravi Kumar mobile 98765 43210 aadhaar 1234 5678 9012 age 30 address Anna Nagar")
                if (heard.isNotBlank()) Lbl("Heard: $heard")
            }
            itemsIdx(ps) { i, p ->
                Column(Modifier.padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Person ${i + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton({ speakFill(i) }) { Text("🎤") }
                        TextButton({ ps = ps.filterIndexed { j, _ -> j != i } }) { Text("Remove", color = Red) }
                    }
                    TxtField("Name", p.name, { v -> ps = ps.toMutableList().also { it[i] = p.copy(name = v) } }, Modifier.fillMaxWidth())
                    TxtField("Mobile", p.mobile, { v -> ps = ps.toMutableList().also { it[i] = p.copy(mobile = v) } }, Modifier.fillMaxWidth())
                    TxtField("Aadhaar / ID", p.aadhaar, { v -> ps = ps.toMutableList().also { it[i] = p.copy(aadhaar = v) } }, Modifier.fillMaxWidth())
                    TxtField("Age", p.age, { v -> ps = ps.toMutableList().also { it[i] = p.copy(age = v) } }, Modifier.fillMaxWidth())
                    TxtField("Address", p.address, { v -> ps = ps.toMutableList().also { it[i] = p.copy(address = v) } }, Modifier.fillMaxWidth())
                    TxtField("Entry date (yyyy-mm-dd)", p.entryDate, { v -> ps = ps.toMutableList().also { it[i] = p.copy(entryDate = v) } }, Modifier.fillMaxWidth())
                    TxtField("Exit date (blank = staying)", p.exitDate, { v -> ps = ps.toMutableList().also { it[i] = p.copy(exitDate = v) } }, Modifier.fillMaxWidth())
                }
            }
            item { TextButton({ ps = ps + Person(entryDate = LocalDate.now().toString()) }) { Text("＋ Add person (type)") } }
        }
    },
        confirmButton = { TextButton({ vm.saveTenantFile(t0.copy(fileName = fn, groupName = groupLetters(fn)).withPersons(ps)); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
