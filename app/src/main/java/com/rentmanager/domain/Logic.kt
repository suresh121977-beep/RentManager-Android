package com.rentmanager.domain

import com.rentmanager.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.max

fun norm(s: String?) = (s ?: "").trim().uppercase().replace(Regex("\\s+"), "")
private val BILL_RX = Regex("^([A-Z]+)(\\d+)$")

/** NS1, NS2, NS10 ... natural order (same as billNaturalCompare in the web app) */
val billCompare = Comparator<String> { a, b ->
    val x = norm(a); val y = norm(b)
    val am = BILL_RX.find(x); val bm = BILL_RX.find(y)
    when {
        am != null && bm != null -> {
            val c = am.groupValues[1].compareTo(bm.groupValues[1])
            if (c != 0) c else (am.groupValues[2].toLongOrNull() ?: 0L).compareTo(bm.groupValues[2].toLongOrNull() ?: 0L)
        }
        am != null -> -1
        bm != null -> 1
        else -> x.compareTo(y)
    }
}

fun billMatch(billNo: String, query: String): Boolean {
    val b = norm(billNo); val q = norm(query)
    if (q.isEmpty()) return true
    if (Regex("^[A-Z]+\\d+$").matches(q)) return b == q
    if (Regex("^[A-Z]+$").matches(q)) return BILL_RX.find(b)?.groupValues?.get(1) == q || b == q
    return b.startsWith(q)
}

fun groupLetters(billNo: String): String =
    BILL_RX.find(norm(billNo))?.groupValues?.get(1) ?: Regex("^[A-Z]+").find(norm(billNo))?.value ?: ""

fun groupFor(billNo: String, groups: List<GroupE>): GroupE? {
    val nb = norm(billNo); var best: GroupE? = null; var bl = -1
    for (g in groups) {
        val ng = norm(g.name)
        if (ng.isNotEmpty() && nb.startsWith(ng) && ng.length > bl) {
            val rest = nb.drop(ng.length)
            if (rest.isEmpty() || rest.all { it.isDigit() }) { best = g; bl = ng.length }
        }
    }
    return best
}

fun csvSplit(line: String): List<String> {
    val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
    for (ch in line) {
        when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out.add(sb.toString().trim()); sb.clear() }
            else -> sb.append(ch)
        }
    }
    out.add(sb.toString().trim()); return out
}
fun csvEsc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
fun csvLines(text: String) = text.removePrefix("\uFEFF").trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
fun num(s: String?) = s?.replace(",", "")?.trim()?.toDoubleOrNull()

data class RawReading(val billNo: String, val prev: Double?, val curr: Double)

/** "NS5,1200,1350" (Prev,Curr)  or  "NS5,1350" (end reading only) */
fun parseReadingCsv(text: String): List<RawReading> {
    val lines = csvLines(text); if (lines.isEmpty()) return emptyList()
    val first = csvSplit(lines[0])
    val start = if (first.size > 1 && num(first[1]) == null) 1 else 0
    val out = ArrayList<RawReading>()
    for (i in start until lines.size) {
        val c = csvSplit(lines[i]); if (c.size < 2 || c[0].isBlank()) continue
        if (c.size < 3) { val e = num(c[1]) ?: continue; out.add(RawReading(norm(c[0]), null, e)) }
        else { val p = num(c[1]) ?: continue; val e = num(c[2]) ?: continue; out.add(RawReading(norm(c[0]), p, e)) }
    }
    return out
}

/** BillNo,PreviousBalance */
fun parseBalanceCsv(text: String): List<Pair<String, Double>> {
    val lines = csvLines(text); if (lines.isEmpty()) return emptyList()
    val start = if (Regex("bill\\s*(no|number)|prev", RegexOption.IGNORE_CASE).containsMatchIn(lines[0])) 1 else 0
    return (start until lines.size).mapNotNull { i ->
        val c = csvSplit(lines[i]); if (c.size < 2) return@mapNotNull null
        val v = num(c[1]) ?: return@mapNotNull null; if (c[0].isBlank()) null else norm(c[0]) to v
    }
}

/** BillNo,Mobile */
fun parseMsgCsv(text: String): List<MsgMap> {
    val lines = csvLines(text); if (lines.isEmpty()) return emptyList()
    val start = if (lines[0].lowercase().contains("bill")) 1 else 0
    return (start until lines.size).mapNotNull { i ->
        val c = csvSplit(lines[i]); if (c.size < 2 || c[0].isBlank()) null else MsgMap(norm(c[0]), c[1])
    }
}

/** CardNo,Group,PrevReading,CurrReading,BillAmount[,LastAmount] */
fun parseEbCsv(text: String, month: String, startSerial: Int): List<EbBill> {
    val lines = csvLines(text); if (lines.isEmpty()) return emptyList()
    val start = if (Regex("card|group|start|end|amount", RegexOption.IGNORE_CASE).containsMatchIn(lines[0])) 1 else 0
    var serial = startSerial
    return (start until lines.size).mapNotNull { i ->
        val c = csvSplit(lines[i]); if (c.size < 5) return@mapNotNull null
        val p = num(c[2]) ?: return@mapNotNull null; val e = num(c[3]) ?: return@mapNotNull null
        val a = num(c[4]) ?: return@mapNotNull null
        val u = max(0.0, e - p); serial++
        EbBill(serialNo = serial, cardNumber = c[0], groupName = c[1].uppercase(), month = month,
            prevReading = p, currReading = e, units = u, billAmount = a, paidAmount = a,
            pricePerUnit = if (u > 0) a / u else 0.0)
    }
}

/** A bill whose current reading has not been entered yet (waiting for voice / manual entry) */
fun Reading.pending() = currReading <= 0.0 && prevReading != null

/** Bill cycle is 10th to 10th: month M covers (M-1)-10 .. M-10 */
data class Cycle(val start: LocalDate, val end: LocalDate)
fun cycleFor(month: String): Cycle { val ym = YearMonth.parse(month); return Cycle(ym.minusMonths(1).atDay(10), ym.atDay(10)) }
fun parseDate(s: String?): LocalDate? = try { LocalDate.parse((s ?: "").take(10)) } catch (e: Exception) { null }
fun entryDateOf(tf: TenantFile): LocalDate? {
    val ps = tf.persons(); val use = ps.filter { it.exitDate.isBlank() }.ifEmpty { ps }
    return use.mapNotNull { parseDate(it.entryDate) }.minOrNull() ?: parseDate(tf.createdAt)
}
/** Entry after the 10th (start of this bill cycle) = new tenant: no rent, only advance due */
fun isFresher(tf: TenantFile, month: String): Boolean {
    val e = entryDateOf(tf) ?: return false
    return e.isAfter(cycleFor(month).start)
}
fun expectedAdvance(g: GroupE?, activePersons: Int): Double =
    if (g == null) 0.0 else g.limitAdvance + max(0, activePersons - g.limitedPerson) * g.extraPersonAdvance

/** Persons that count for rent in this cycle = active persons who entered on/before the cycle start (10th) */
fun ratedPersonCount(tf: TenantFile, month: String): Int {
    val start = cycleFor(month).start
    return tf.active().count { val e = parseDate(it.entryDate); e == null || !e.isAfter(start) }
}

/** Entry date -> next bill date, total days (used to adjust rent by days) */
data class DaysInfo(val entry: LocalDate?, val nextBill: LocalDate, val days: Int)
fun rentDaysInfo(tf: TenantFile?, month: String): DaysInfo {
    val c = cycleFor(month); val e = if (tf == null) null else entryDateOf(tf)
    val days = if (e != null && e.isAfter(c.start)) ChronoUnit.DAYS.between(e, c.end).toInt().coerceIn(0, 31) else 30
    return DaysInfo(e, c.end, days)
}

/** Bill = units*price + rent(days/30) + extra persons*extraRent + extraCharge + advance due + previous balance.
 *  New tenant (fresher, entered after the 10th): rentDays = -1, no rent; amount = units + advance due + previous balance.
 *  ratedPersons = persons that count for extra-person rent (late joiners pay only advance). */
fun buildBill(billNo: String, month: String, r: Reading?, g: GroupE?, old: Bill?, personsOverride: Int?, vacant: Boolean,
              fresher: Boolean = false, advanceDue: Double = 0.0, ratedPersons: Int? = null): Bill {
    val units = r?.units ?: old?.units ?: 0.0
    val price = g?.pricePerUnit ?: r?.price ?: 8.5
    val unitsCharge = units * price
    val days = if (fresher) -1 else (old?.rentDays?.takeIf { it >= 0 } ?: 30)
    val rent = if (fresher) 0.0 else (g?.rent ?: 3000.0) * days / 30.0
    val p = personsOverride ?: old?.persons ?: 0
    val lim = g?.limitedPerson ?: 3
    val extraP = max(0, (ratedPersons ?: p) - lim)
    val extraTotal = if (fresher) 0.0 else extraP * (g?.extraPersonRent ?: 500.0)
    val extraCharge = if (fresher) 0.0 else (g?.extraCharge ?: 0.0)
    val prev = old?.prevBalance ?: 0.0
    val amount = if (vacant) 0.0 else if (fresher) unitsCharge + advanceDue + prev
        else unitsCharge + rent + extraTotal + extraCharge + advanceDue + prev
    return Bill(billNo, month, g?.name ?: groupLetters(billNo), units, unitsCharge, days, rent, p, extraP,
        extraTotal, extraCharge, prev, amount, vacant, advanceDue)
}

/** BillNo,Persons,PreviousBalance  (or BillNo,PreviousBalance) */
data class AddRow(val billNo: String, val persons: Int?, val prev: Double?)
fun parseAdditionCsv(text: String): List<AddRow> {
    val lines = csvLines(text); if (lines.isEmpty()) return emptyList()
    val start = if (Regex("bill|person|prev", RegexOption.IGNORE_CASE).containsMatchIn(lines[0]) && num(csvSplit(lines[0]).getOrNull(1)) == null) 1 else 0
    return (start until lines.size).mapNotNull { i ->
        val c = csvSplit(lines[i]); if (c.size < 2 || c[0].isBlank()) return@mapNotNull null
        if (c.size >= 3) AddRow(norm(c[0]), c[1].trim().toDoubleOrNull()?.toInt(), num(c[2]))
        else AddRow(norm(c[0]), null, num(c[1]))
    }
}

enum class PayStatus { PAID, PARTIAL, UNPAID }
fun balanceOf(b: Bill, p: Payment?) = max(0.0, b.billAmount - (p?.received ?: 0.0))
fun statusOf(b: Bill, p: Payment?): PayStatus = when {
    (p?.received ?: 0.0) <= 0.0 -> PayStatus.UNPAID
    balanceOf(b, p) <= 0.0 -> PayStatus.PAID
    else -> PayStatus.PARTIAL
}

fun shiftMonth(m: String, n: Long): String = YearMonth.parse(m).plusMonths(n).toString()
fun monthLabel(m: String): String = try {
    val ym = YearMonth.parse(m)
    ym.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() } + " " + ym.year
} catch (e: Exception) { m }

/** 7th Oct 26 */
fun fmtDay(d: String): String = try {
    val x = LocalDate.parse(d.take(10)); val n = x.dayOfMonth
    val o = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    "$n$o ${x.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${x.year % 100}"
} catch (e: Exception) { d }

fun money(d: Double) = "₹" + String.format("%,.0f", d)

fun billText(b: Bill, p: Payment?): String =
    "Rent bill ${b.billNo} (${monthLabel(b.month)})\nUnits: ${"%.0f".format(b.units)} = ${money(b.unitsCharge)}\n" +
        "Rent: ${money(b.rent + b.extraTotal + b.extraCharge)}\nPrevious balance: ${money(b.prevBalance)}\n" +
        "Total: ${money(b.billAmount)}\nReceived: ${money(p?.received ?: 0.0)}\nBalance: ${money(balanceOf(b, p))}"

// ---------- voice parsing ----------
data class VoiceCmd(val billNo: String?, val amount: Double?, val mode: String, val full: Boolean)

private val NUMW = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
    "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
    "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

private fun wordsToDigits(t: String): String {
    val toks = t.lowercase().replace("-", " ").split(Regex("\\s+")); val out = ArrayList<String>()
    var total = 0; var cur = 0; var inRun = false
    fun flush() { if (inRun) { out.add((total + cur).toString()); total = 0; cur = 0; inRun = false } }
    for (w in toks) {
        val v = NUMW[w]
        when {
            v != null -> { cur += v; inRun = true }
            w == "hundred" && inRun -> cur = max(cur, 1) * 100
            w == "thousand" && inRun -> { total += max(cur, 1) * 1000; cur = 0 }
            else -> { flush(); out.add(w) }
        }
    }
    flush(); return out.joinToString(" ")
}

fun parseVoice(heard: String): VoiceCmd {
    val s = wordsToDigits(heard)
    val stop = setOf("CASH", "GPAY", "PAY", "UPI", "RS", "RUPEES", "FULL", "PAID", "BILL")
    val bm = Regex("([a-z]{1,4})\\s*(\\d{1,3})").findAll(s).firstOrNull { it.groupValues[1].uppercase() !in stop }
    val rest = if (bm != null) s.removeRange(bm.range) else s
    val amt = Regex("\\d+(\\.\\d+)?").find(rest)?.value?.toDoubleOrNull()
    val mode = if (Regex("gpay|g pay|google|online|upi|phone ?pe").containsMatchIn(s)) "GPAY" else "CASH"
    return VoiceCmd(bm?.let { norm(it.groupValues[1] + it.groupValues[2]) }, amt, mode, Regex("\\b(full|all)\\b").containsMatchIn(s))
}


// ---------- spoken numbers / person details ----------
private val DIGITW = mapOf("zero" to 0, "oh" to 0, "o" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)

/** "nine eight 7 6" -> "9876" */
fun spokenDigits(t: String): String {
    val sb = StringBuilder()
    t.lowercase().split(Regex("[^a-z0-9]+")).forEach { w ->
        val d = DIGITW[w]
        if (d != null) sb.append(d) else if (w.isNotEmpty() && w.all { it.isDigit() }) sb.append(w)
    }
    return sb.toString()
}

/** "one two five zero" -> 1250 ; "twelve fifty"/"1250"/"one thousand two hundred" -> number */
fun spokenNumber(t: String): Double? {
    val toks = t.lowercase().replace(",", " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    if (toks.size >= 2 && toks.all { DIGITW.containsKey(it) || (it.length == 1 && it[0].isDigit()) }) return spokenDigits(t).toDoubleOrNull()
    val s = wordsToDigits(t)
    return Regex("\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull()
}

data class PersonVoice(val name: String?, val mobile: String?, val aadhaar: String?, val age: String?, val address: String?,
                       val father: String? = null, val company: String? = null)

private fun titleCase(s: String) = s.trim().split(" ").filter { it.isNotBlank() }
    .joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } }

/** "name Ravi age 38 father Ragavan mobile 98765 43210 id number 1234 5678 9012 company TCS address Anna Nagar" */
fun parsePersonVoice(heard: String): PersonVoice {
    val kw = mapOf("name" to "name", "mobile" to "mobile", "phone" to "mobile", "contact" to "mobile",
        "aadhaar" to "aadhaar", "aadhar" to "aadhaar", "adhar" to "aadhaar", "id" to "aadhaar", "age" to "age", "address" to "address",
        "father" to "father", "company" to "company", "working" to "company", "work" to "company", "office" to "company")
    val parts = LinkedHashMap<String, StringBuilder>(); val lead = StringBuilder(); var cur: String? = null
    for (w in heard.trim().split(Regex("\\s+"))) {
        val k = kw[w.lowercase().trim(',', '.')]
        if (k != null) {
            val c0 = cur
            // "father name", "company name", "working company": the second keyword is only a filler word
            if (c0 != null && parts[c0]?.isEmpty() == true && ((k == "name" && (c0 == "father" || c0 == "company")) || (k == "company" && c0 == "company"))) continue
            cur = k; parts.getOrPut(k) { StringBuilder() }; continue
        }
        val c = cur
        if (c == null) lead.append(w).append(' ') else parts[c]?.append(w)?.append(' ')
    }
    val name = parts["name"]?.toString()?.takeIf { it.isNotBlank() } ?: lead.toString().takeIf { it.isNotBlank() }
    val mob = parts["mobile"]?.let { spokenDigits(it.toString()) }?.takeIf { it.length >= 10 }?.takeLast(10)
    val aad = parts["aadhaar"]?.let { spokenDigits(it.toString()) }?.takeIf { it.length >= 6 }
    val age = parts["age"]?.let { spokenNumber(it.toString()) }?.toInt()?.toString()
    val addr = parts["address"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    val father = parts["father"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    val comp = parts["company"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
    return PersonVoice(name?.let { titleCase(it) }, mob, aad, age, addr, father?.let { titleCase(it) }, comp?.let { titleCase(it) })
}
