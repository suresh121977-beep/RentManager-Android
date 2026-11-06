package com.rentmanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.rentmanager.data.*
import com.rentmanager.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max

class AppVM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs = app.getSharedPreferences("rm", 0)

    private fun <T> Flow<T>.st(init: T) = stateIn(viewModelScope, SharingStarted.Eagerly, init)
    val month = MutableStateFlow(LocalDate.now().toString().take(7))
    val groups = dao.groups().st(emptyList())
    val readings = dao.readings().st(emptyList())
    val bills = dao.bills().st(emptyList())
    val payments = dao.payments().st(emptyList())
    val logs = dao.logs().st(emptyList())
    val finance = dao.finance().st(emptyList())
    val eb = dao.eb().st(emptyList())
    val tenantFiles = dao.tenantFiles().st(emptyList())
    val msgMap = dao.msgMap().st(emptyList())
    val favs = dao.favs().st(emptyList())
    val vacated = dao.vacated().st(emptyList())
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private fun say(s: String) { toast.tryEmit(s) }

    var yellow = MutableStateFlow(prefs.getBoolean("yellow", false))
    fun toggleTheme() { yellow.value = !yellow.value; prefs.edit().putBoolean("yellow", yellow.value).apply() }
    var notes: String
        get() = prefs.getString("notes", "") ?: ""
        set(v) { prefs.edit().putString("notes", v).apply() }
    var price: Double
        get() = prefs.getFloat("price", 8.5f).toDouble()
        set(v) { prefs.edit().putFloat("price", v.toFloat()).apply() }

    init { viewModelScope.launch(Dispatchers.IO) { autoBackup() } }

    fun shift(n: Long) { month.value = shiftMonth(month.value, n) }

    // ---------------- Bill Preparing ----------------
    fun importReadings(text: String) = viewModelScope.launch {
        val raws = parseReadingCsv(text)
        if (raws.isEmpty()) { say("No valid rows. Use BillNo,Prev,Curr or BillNo,End"); return@launch }
        val m = month.value; val gs = groups.value
        val prevMonth = readings.value.filter { it.month == shiftMonth(m, -1) }.associateBy { it.billNo }
        val rows = raws.map { r ->
            val g = groupFor(r.billNo, gs); val p = g?.pricePerUnit?.takeIf { it > 0 } ?: price
            val prev = r.prev ?: prevMonth[r.billNo]?.currReading
            val units = if (prev != null) r.curr - prev else 0.0
            Reading(r.billNo, m, prev, r.curr, units, p, units * p, prev == null)
        }
        dao.upsertReadings(rows)
        val unresolved = rows.count { it.endOnlyUnresolved }
        say("Imported ${rows.size} rows" + if (unresolved > 0) " ($unresolved need a start reading)" else "")
        syncBills()
    }
    fun addReading(billNo: String, prev: Double, curr: Double) = viewModelScope.launch {
        val b = norm(billNo); if (b.isEmpty()) return@launch
        val g = groupFor(b, groups.value); val p = g?.pricePerUnit?.takeIf { it > 0 } ?: price
        dao.upsertReadings(listOf(Reading(b, month.value, prev, curr, curr - prev, p, (curr - prev) * p)))
        syncBills()
    }
    fun clearReadings() = viewModelScope.launch { dao.deleteReadings(month.value); dao.deleteBills(month.value); say("Removed this month's readings & bills") }

    // ---------------- Bill Addition / Person Modify ----------------
    fun syncBills() = viewModelScope.launch { syncInternal(); say("Bills synced ✓") }

    private suspend fun syncInternal() {
        val m = month.value; val gs = dao.groups().first()
        val rs = dao.readings().first().filter { it.month == m }
        val old = dao.bills().first().filter { it.month == m }.associateBy { it.billNo }
        val tfs = dao.tenantFiles().first().associateBy { norm(it.fileName) }
        val out = rs.map { r ->
            val g = groupFor(r.billNo, gs)
            val fixed = if (g != null && g.pricePerUnit > 0 && r.price != g.pricePerUnit)
                r.copy(price = g.pricePerUnit, charge = r.units * g.pricePerUnit) else r
            val tf = tfs[r.billNo]
            val active = tf?.active()?.size
            buildBill(r.billNo, m, fixed, g, old[r.billNo], active, tf != null && active == 0)
        }
        dao.upsertReadings(rs.map { r -> val g = groupFor(r.billNo, gs); if (g != null && g.pricePerUnit > 0) r.copy(price = g.pricePerUnit, charge = r.units * g.pricePerUnit) else r })
        dao.upsertBills(out)
    }

    fun editBill(b: Bill, persons: Int, prevBalance: Double, rentDays: Int) = viewModelScope.launch {
        val g = groupFor(b.billNo, groups.value)
        val r = readings.value.firstOrNull { it.billNo == b.billNo && it.month == b.month }
        dao.upsertBills(listOf(buildBill(b.billNo, b.month, r, g, b.copy(prevBalance = prevBalance, rentDays = rentDays), persons, b.vacant)))
        say("${b.billNo} updated ✓")
    }
    fun importBalances(text: String) = viewModelScope.launch {
        val rows = parseBalanceCsv(text); if (rows.isEmpty()) { say("No valid rows (BillNo,PreviousBalance)"); return@launch }
        val m = month.value; val bs = bills.value.filter { it.month == m }.associateBy { it.billNo }; var n = 0
        val gs = groups.value
        val upd = rows.mapNotNull { (b, v) -> bs[b]?.let { n++; val r = readings.value.firstOrNull { x -> x.billNo == b && x.month == m }
            buildBill(b, m, r, groupFor(b, gs), it.copy(prevBalance = v), null, it.vacant) } }
        dao.upsertBills(upd); say("Previous balance set for $n bills")
    }
    /** Previous month's unpaid balance becomes this month's previous balance */
    fun carryBalances() = viewModelScope.launch {
        val m = month.value; val pm = shiftMonth(m, -1); val gs = groups.value
        val pb = bills.value.filter { it.month == pm }.associateBy { it.billNo }
        val pp = payments.value.filter { it.month == pm }.associateBy { it.billNo }
        val upd = bills.value.filter { it.month == m }.map { b ->
            val prev = pb[b.billNo]?.let { balanceOf(it, pp[b.billNo]) } ?: 0.0
            val r = readings.value.firstOrNull { x -> x.billNo == b.billNo && x.month == m }
            buildBill(b.billNo, m, r, groupFor(b.billNo, gs), b.copy(prevBalance = prev), null, b.vacant)
        }
        dao.upsertBills(upd); say("Carried forward last month's balances")
    }

    // ---------------- Groups ----------------
    fun saveGroup(g: GroupE) = viewModelScope.launch {
        val name = norm(g.name); if (name.isEmpty()) return@launch
        dao.upsertGroup(g.copy(name = name)); syncInternal()
        say("$name saved & bills recalculated ✓")
    }
    fun deleteGroup(name: String) = viewModelScope.launch { dao.deleteGroup(name) }

    // ---------------- Payments ----------------
    fun pay(billNo: String, amount: Double, mode: String) = viewModelScope.launch {
        val m = month.value
        val bill = dao.bills().first().firstOrNull { it.billNo == billNo && it.month == m } ?: return@launch
        val ex = dao.payments().first().firstOrNull { it.billNo == billNo && it.month == m }
        val old = max(0.0, bill.billAmount - (ex?.received ?: 0.0))
        val rec = (ex?.received ?: 0.0) + amount; val bal = max(0.0, bill.billAmount - rec)
        val d = LocalDate.now().toString()
        dao.upsertPayment(Payment(billNo, m, rec, bal, d, mode, amount))
        dao.insertLog(PaymentLog(0, billNo, m, bill.groupName, amount, mode, old, bal, d, LocalTime.now().toString().take(5)))
        say("$billNo ${money(amount)} via $mode ✓ • Balance ${money(bal)}")
    }
    fun undoLastPayment(billNo: String) = viewModelScope.launch {
        val m = month.value
        val last = dao.logs().first().firstOrNull { it.billNo == billNo && it.month == m } ?: run { say("Nothing to undo"); return@launch }
        val bill = dao.bills().first().firstOrNull { it.billNo == billNo && it.month == m } ?: return@launch
        val ex = dao.payments().first().firstOrNull { it.billNo == billNo && it.month == m } ?: return@launch
        val rec = max(0.0, ex.received - last.paid)
        if (rec <= 0.0) dao.deletePayment(billNo, m) else dao.upsertPayment(ex.copy(received = rec, balance = max(0.0, bill.billAmount - rec)))
        dao.deleteLog(last.id); say("Undid ${money(last.paid)} for $billNo")
    }
    fun toggleFav(b: String) = viewModelScope.launch { if (favs.value.any { it.billNo == b }) dao.deleteFav(b) else dao.upsertFav(Fav(b)) }

    // ---------------- Messages ----------------
    fun saveMsg(list: List<MsgMap>) = viewModelScope.launch { dao.upsertMsg(list); say("Saved ${list.size} mobile numbers") }

    // ---------------- Tenant files ----------------
    fun saveTenantFile(t: TenantFile) = viewModelScope.launch {
        dao.upsertTenantFiles(listOf(t.copy(fileName = norm(t.fileName)))); syncInternal(); say("Tenant file saved ✓")
    }
    fun deleteTenantFile(n: String) = viewModelScope.launch { dao.deleteTenantFile(n); syncInternal() }
    fun bulkCreateFiles(group: String) = viewModelScope.launch {
        val g = groups.value.firstOrNull { it.name == group } ?: return@launch
        val ex = tenantFiles.value.map { it.fileName }.toSet()
        val new = (1..g.billCount).map { g.name + it }.filter { it !in ex }
            .map { TenantFile(it, g.name, "[]", 0.0, LocalDate.now().toString()) }
        dao.upsertTenantFiles(new); say("${new.size} blank files created for ${g.name}")
    }
    /** FileName,Name,Mobile,Aadhaar,EntryDate */
    fun importPersons(text: String) = viewModelScope.launch {
        val lines = csvLines(text); if (lines.isEmpty()) return@launch
        val start = if (lines[0].lowercase().contains("file") || lines[0].lowercase().contains("name")) 1 else 0
        val map = tenantFiles.value.associateBy { it.fileName }.toMutableMap()
        for (i in start until lines.size) {
            val c = csvSplit(lines[i]); if (c.size < 2) continue
            val f = norm(c[0]); val tf = map[f] ?: TenantFile(f, groupLetters(f), "[]", 0.0, LocalDate.now().toString())
            val p = Person(c[1], c.getOrElse(2) { "" }, c.getOrElse(3) { "" }, "", c.getOrElse(4) { LocalDate.now().toString() })
            map[f] = tf.withPersons(tf.persons() + p)
        }
        dao.upsertTenantFiles(map.values.toList()); syncInternal(); say("Persons imported ✓")
    }
    fun vacate(fileName: String, date: String, due: Double, advance: Double) = viewModelScope.launch {
        val tf = tenantFiles.value.firstOrNull { it.fileName == fileName } ?: return@launch
        val adj = minOf(advance, due)
        dao.insertVacated(Vacated(0, fileName, tf.active().joinToString(", ") { it.name }, date, due, advance, adj, advance - adj, due - adj))
        dao.upsertTenantFiles(listOf(tf.withPersons(tf.persons().map { if (it.exitDate.isBlank()) it.copy(exitDate = date) else it }).copy(advance = 0.0)))
        syncInternal(); say("$fileName vacated ✓")
    }

    // ---------------- Finance / EB ----------------
    fun addFinance(kind: String, amount: Double, cat: String, note: String, date: String) = viewModelScope.launch {
        dao.upsertFinance(FinanceRec(0, kind, amount, cat, note, date)); say("Saved ✓")
    }
    fun deleteFinance(id: Long) = viewModelScope.launch { dao.deleteFinance(id) }
    fun saveEb(e: EbBill) = viewModelScope.launch {
        val u = max(0.0, e.currReading - e.prevReading)
        dao.upsertEb(e.copy(units = u, pricePerUnit = if (u > 0) e.billAmount / u else 0.0,
            serialNo = if (e.serialNo == 0) (eb.value.maxOfOrNull { it.serialNo } ?: 0) + 1 else e.serialNo,
            month = e.month.ifBlank { month.value }, groupName = e.groupName.uppercase()))
    }
    fun deleteEb(id: Long) = viewModelScope.launch { dao.deleteEb(id) }
    fun clearEb() = viewModelScope.launch { dao.deleteAllEb() }
    fun importEb(text: String) = viewModelScope.launch {
        val rows = parseEbCsv(text, month.value, eb.value.maxOfOrNull { it.serialNo } ?: 0)
        if (rows.isEmpty()) { say("No valid EB rows"); return@launch }
        rows.forEach { dao.upsertEb(it) }; say("Imported ${rows.size} EB bills")
    }

    // ---------------- Reset / Backup ----------------
    fun reset(allMonths: Boolean, withFinance: Boolean, backupFirst: Boolean) = viewModelScope.launch {
        if (backupFirst) withContext(Dispatchers.IO) { writeBackupFile("before_reset") }
        if (allMonths) dao.resetAll(withFinance) else dao.resetMonth(month.value, withFinance)
        say("Reset done ✓")
    }
    suspend fun backupJson(): String = gson.toJson(Backup(1, LocalDate.now().toString(),
        dao.groups().first(), dao.readings().first(), dao.bills().first(), dao.payments().first(), dao.logs().first(),
        dao.finance().first(), dao.eb().first(), dao.tenantFiles().first(), dao.msgMap().first(), dao.favs().first(), dao.vacated().first()))
    fun restoreJson(json: String) = viewModelScope.launch {
        try {
            val b = gson.fromJson(json, Backup::class.java)
            if (b == null || b.version == 0) { say("Not a Rent Manager backup"); return@launch }
            withContext(Dispatchers.IO) { writeBackupFile("before_restore") }
            dao.restore(b); say("Restored ✓ (${b.bills.size} bills, ${b.payments.size} payments)")
        } catch (e: Exception) { say("Restore failed: ${e.message}") }
    }
    private fun backupDir() = File(getApplication<Application>().filesDir, "backups").apply { mkdirs() }
    private suspend fun writeBackupFile(tag: String): File {
        val f = File(backupDir(), "rent_backup_${LocalDate.now()}_$tag.json"); f.writeText(backupJson()); return f
    }
    private suspend fun autoBackup() {
        if (!prefs.getBoolean("autoBackup", true)) return
        val today = LocalDate.now().toString(); if (prefs.getString("lastBackup", "") == today) return
        writeBackupFile("auto"); prefs.edit().putString("lastBackup", today).apply()
        backupDir().listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
    }
    fun backups(): List<File> = backupDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun restoreFile(f: File) = restoreJson(f.readText())
    fun lastBackup(): String = prefs.getString("lastBackup", "never") ?: "never"
    fun backupNow() = viewModelScope.launch(Dispatchers.IO) { writeBackupFile("manual"); say("Backup saved on phone ✓") }
    var autoBackupOn: Boolean
        get() = prefs.getBoolean("autoBackup", true)
        set(v) { prefs.edit().putBoolean("autoBackup", v).apply() }
}
