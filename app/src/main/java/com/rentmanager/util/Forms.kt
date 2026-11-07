package com.rentmanager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.rentmanager.data.*
import com.rentmanager.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

object Img {
    val EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    fun isImage(f: File) = f.extension.lowercase() in EXT
    fun decodeSampled(f: File, max: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Exception) { null }
    fun dataUri(f: File, max: Int): String? {
        val bmp = decodeSampled(f, max) ?: return null
        val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 72, bo)
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }
}

/** Values shown on the agreement form (same for the in-app preview and the downloaded HTML) */
data class AgrData(
    val fileNo: String, val group: String, val inCharge: Person, val mates: List<Person>,
    val initialAmount: Double, val initialDate: String, val totalAdvance: Double, val place: String
)

fun agrDataFor(tf: TenantFile, txns: List<AdvTxn>, inCharge: Person, place: String): AgrData {
    val credits = txns.filter { it.kind == "CREDIT" }.sortedWith(compareBy({ it.date }, { it.id }))
    val ini = credits.firstOrNull()
    val mates = tf.persons().filter { it != inCharge }
    return AgrData(tf.fileName, tf.groupName, inCharge, mates, ini?.amount ?: tf.advance, ini?.date ?: tf.createdAt, tf.advance, place)
}

fun agrDeclaration(lang: String, d: AgrData): String {
    val p = d.inCharge
    return AgrText.declaration(lang, p.name.ifBlank { "[Name]" }, p.fatherName.ifBlank { "[Father Name]" }, p.age.ifBlank { "[Age]" },
        p.address.ifBlank { "[Address]" }, "%.0f".format(d.initialAmount), fmtDay(p.entryDate.ifBlank { d.initialDate }), d.fileNo)
}

object Forms {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun v(s: String) = esc(s.ifBlank { "-" })

    private fun firstImage(ctx: Context, fileName: String, type: String): File? =
        Attachments.dir(ctx, fileName, type).listFiles()?.filter { it.isFile && Img.isImage(it) }?.maxByOrNull { it.lastModified() }

    // ------------------------------------------------------------------ Agreement form (EN / HI / TA)
    suspend fun agreementHtml(ctx: Context, d: AgrData, startLang: String): String = withContext(Dispatchers.IO) {
        val photo = firstImage(ctx, d.fileNo, "Photo")?.let { Img.dataUri(it, 400) }
        val today = fmtDay(LocalDate.now().toString())
        val p = d.inCharge
        fun row(k: String, value: String, extra: String = "") = "<div class=\"row\"><span class=\"k\">${esc(k)}:</span><span class=\"v\" style=\"$extra\">$value</span></div>"
        fun page(l: String): String {
            val t = { key: String -> AgrText.t(l, key) }
            val sb = StringBuilder()
            sb.append("<div class=\"agr lang-$l\" id=\"l_$l\"")
            if (l != startLang) sb.append(" style=\"display:none\"")
            sb.append(">")
            sb.append("<div class=\"hd\"><div class=\"hdt\"><div class=\"ttl\">${esc(t("title"))}</div>")
            sb.append("<div class=\"sub\">${esc(t("fileNo"))}: ${esc(d.fileNo)} | ${esc(t("group"))}: ${esc(d.group)} | ${esc(t("date"))}: $today</div></div>")
            sb.append("<div class=\"ph\">")
            if (photo != null) sb.append("<img src=\"$photo\" alt=\"photo\">") else sb.append("<div class=\"phn\">👤</div>")
            sb.append("<div class=\"phl\">${esc(t("photoLabel"))}</div></div></div>")

            sb.append("<div class=\"sec\"><div class=\"h\">${esc(t("responsible"))}</div><div class=\"rows\">")
            sb.append(row(t("fileNo"), esc(d.fileNo)))
            sb.append(row(t("inCharge"), v(p.name), "font-weight:700"))
            sb.append(row(t("age"), v(p.age)))
            sb.append(row(t("father"), v(p.fatherName)))
            sb.append(row(t("address"), v(p.address)))
            sb.append(row(t("mobile"), v(p.mobile)))
            sb.append(row(t("idType"), v(p.idType.ifBlank { "Aadhar Card" })))
            sb.append(row(t("idNumber"), v(p.aadhaar), "font-family:monospace"))
            sb.append(row(t("company"), v(p.company)))
            sb.append(row(t("initialAdvance"), "Rs. ${"%.0f".format(d.initialAmount)} on ${esc(fmtDay(d.initialDate))}"))
            sb.append(row(t("entryDate"), esc(fmtDay(p.entryDate.ifBlank { d.initialDate }))))
            sb.append(row(t("totalAdvance"), "Rs. ${"%.0f".format(d.totalAdvance)}", "font-weight:700"))
            sb.append("</div></div>")

            sb.append("<div class=\"sec\"><div class=\"h\">${esc(t("roomMates"))} / Partners in File ${esc(d.fileNo)} (${d.mates.size})</div>")
            if (d.mates.isEmpty()) sb.append("<div class=\"none\">${esc(t("noRoomMates"))}</div>")
            else {
                sb.append("<div class=\"mates\">")
                d.mates.forEachIndexed { i, m ->
                    sb.append("<div class=\"m\"><b>${i + 1}.</b><span>${esc(m.name)} - ${esc(t("age"))}: ${v(m.age)} - ${esc(t("mobile"))}: ${v(m.mobile)} - ${esc(m.idType.ifBlank { "Aadhar Card" })}: ${v(m.aadhaar)}</span></div>")
                }
                sb.append("</div>")
            }
            sb.append("</div>")

            sb.append("<div class=\"sec\"><div class=\"h\">${esc(t("conditions"))}</div><div class=\"conds\">")
            (AgrText.CONDS[l] ?: emptyList()).forEachIndexed { i, c -> sb.append("<div class=\"c\"><b>${i + 1}.</b><span>${esc(c)}</span></div>") }
            sb.append("</div></div>")

            sb.append("<div class=\"sec\"><div class=\"h\">${esc(t("declaration"))}</div><div class=\"decl\">${esc(agrDeclaration(l, d))}</div></div>")
            sb.append("<div class=\"dp\"><div><b>${esc(t("date"))}:</b> $today</div><div><b>${esc(t("place"))}:</b> ${if (d.place.isBlank()) "___________" else esc(d.place)}</div></div>")
            sb.append("<div class=\"sg\"><div class=\"sgc\"><div class=\"box\">${esc(t("ownerAuth"))}</div><b>${esc(t("sigOwner"))}</b></div>")
            sb.append("<div class=\"sgc\"><div class=\"box\">Signature</div><b>${esc(t("sigInCharge"))}</b><div class=\"nm\">${esc(p.name)}</div></div></div>")
            sb.append("<div class=\"ft\">${esc(t("generated"))} - File ${esc(d.fileNo)} - $today</div></div>")
            return sb.toString()
        }
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Agreement ${esc(d.fileNo)} ${esc(p.name)}</title><style>")
        sb.append("*{box-sizing:border-box}body{margin:0;background:#f1f5f9;font-family:Roboto,Arial,Helvetica,sans-serif;color:#000}")
        sb.append(".bar{max-width:820px;margin:0 auto;padding:10px;display:flex;gap:8px;flex-wrap:wrap}.bar button{flex:1;padding:11px 8px;border:0;border-radius:10px;background:#e2e8f0;font-weight:700;font-size:14px}.bar button.on{background:#14b8a6;color:#fff}")
        sb.append(".agr{max-width:820px;margin:0 auto 20px;background:#fff;border:1px solid #e5e7eb;border-radius:14px;padding:20px}")
        sb.append(".lang-hi{font-family:'Noto Sans Devanagari','Mangal','Nirmala UI',sans-serif}.lang-ta{font-family:'Noto Sans Tamil','Latha','Nirmala UI',sans-serif}")
        sb.append(".hd{position:relative;border-bottom:3px solid #000;padding-bottom:12px;margin-bottom:14px;min-height:130px}.hdt{text-align:center;padding-right:150px}")
        sb.append(".ttl{font-size:26px;font-weight:700;line-height:1.15}.sub{font-size:13px;margin-top:8px;color:#333}")
        sb.append(".ph{position:absolute;top:0;right:0;width:130px;height:140px;border:2px solid #000;border-radius:10px;overflow:hidden;background:#fff}.ph img{width:100%;height:116px;object-fit:cover;display:block}")
        sb.append(".phn{height:116px;background:#f3f4f6;display:flex;align-items:center;justify-content:center;font-size:44px}.phl{font-size:11px;text-align:center;padding:3px 0;border-top:1px solid #000}")
        sb.append(".sec{margin-bottom:18px}.h{font-size:16px;font-weight:700;margin-bottom:9px;text-decoration:underline}.rows{display:grid;gap:7px;font-size:15px;line-height:1.5}")
        sb.append(".row{display:flex}.k{font-weight:700;width:46%;flex-shrink:0}.v{flex:1}.none{font-size:14px;font-style:italic;color:#555}")
        sb.append(".mates,.conds{display:grid;gap:8px}.m,.c{font-size:15px;line-height:1.5;display:flex;gap:10px}.m b,.c b{flex-shrink:0}")
        sb.append(".decl{font-size:15px;line-height:1.7;text-align:justify;border:1px solid #e5e7eb;border-radius:8px;padding:12px;background:#fafafa}")
        sb.append(".dp{display:flex;justify-content:space-between;font-size:15px;margin:12px 0 30px}.sg{display:flex;justify-content:space-between;align-items:flex-end;margin-top:18px}")
        sb.append(".sgc{text-align:center;font-size:15px}.box{width:190px;height:70px;border:2px solid #000;border-radius:10px;display:flex;align-items:center;justify-content:center;color:#888;margin:0 auto 6px}.nm{font-size:14px;margin-top:2px}")
        sb.append(".ft{border-top:2px solid #e5e7eb;margin-top:26px;padding-top:12px;text-align:center;font-size:12px;color:#666}")
        sb.append("@media(max-width:560px){.ttl{font-size:20px}.row{flex-direction:column}.k{width:auto}.box{width:140px}.ph{width:100px;height:112px}.ph img,.phn{height:90px}.hdt{padding-right:110px}}")
        sb.append("@media print{.bar{display:none}body{background:#fff}.agr{border:0;margin:0;max-width:none}}")
        sb.append("</style></head><body><div class=\"bar\">")
        AgrText.LANGS.forEach { (code, label) -> sb.append("<button class=\"${if (code == startLang) "on" else ""}\" id=\"b_$code\" onclick=\"setLang('$code')\">$label</button>") }
        sb.append("<button onclick=\"window.print()\">🖨 Print / PDF</button><button onclick=\"saveSelf()\">⬇ Save HTML</button></div>")
        AgrText.LANGS.forEach { (code, _) -> sb.append(page(code)) }
        sb.append("<script>function setLang(c){['en','hi','ta'].forEach(function(x){var e=document.getElementById('l_'+x);if(e)e.style.display=x===c?'block':'none';var b=document.getElementById('b_'+x);if(b)b.classList.toggle('on',x===c);});}")
        sb.append("function saveSelf(){var h='<!doctype html>'+document.documentElement.outerHTML;var b=new Blob([h],{type:'text/html'});var a=document.createElement('a');a.href=URL.createObjectURL(b);a.download=(document.title||'agreement').replace(/[^A-Za-z0-9_-]+/g,'_')+'.html';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},1500);}</script></body></html>")
        sb.toString()
    }

    // ------------------------------------------------------------------ Tenant file (History / Advance / Persons / Vacate) – same page as your embedded N1 file
    suspend fun tenantFileHtml(ctx: Context, tf: TenantFile, txns: List<AdvTxn>, bills: List<Bill>, readings: List<Reading>, vac: List<Vacated>): String = withContext(Dispatchers.IO) {
        fun mon(d: Double) = money(d)
        val persons = tf.persons()
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Tenant File ${esc(tf.fileName)}</title>\n<style>\n")
        sb.append("*{box-sizing:border-box}body{margin:0;font-family:Arial,Helvetica,sans-serif;background:#f1f5f9;color:#0f172a;line-height:1.45}\n.wrap{max-width:820px;margin:0 auto;padding:14px}\n.head{background:#0f172a;color:#fff;border-radius:14px;padding:14px;margin-bottom:12px}\n.head h1{margin:0 0 6px;font-size:20px}.head div{font-size:12px;color:#cbd5e1}\n")
        sb.append(".tabs{display:flex;gap:8px;margin-bottom:12px}.tab{flex:1;padding:12px 6px;border:0;border-radius:12px;background:#e2e8f0;font-size:14px;font-weight:700;color:#0f172a;cursor:pointer}\n.tab.on{background:#14b8a6;color:#fff}\n")
        sb.append(".sec{display:none;background:#fff;border-radius:14px;padding:12px;box-shadow:0 1px 3px rgba(0,0,0,.08)}.sec.on{display:block}\n.ev{display:flex;gap:10px;padding:9px 0;border-bottom:1px solid #e2e8f0;font-size:13px}.ev .dt{min-width:78px;font-weight:700;color:#475569}\n")
        sb.append(".ev.exit b{color:#dc2626}.ev.add b{color:#0d9488}.ev.bill b{color:#7c3aed}.ev.file b{color:#2563eb}\ntable{width:100%;border-collapse:collapse;font-size:13px}th,td{border-bottom:1px solid #e2e8f0;padding:8px 5px;text-align:left}.r{text-align:right}\n")
        sb.append("tr.cre td:nth-child(3){color:#059669;font-weight:700}tr.deb td:nth-child(3){color:#dc2626;font-weight:700}\n.sum{background:#ecfeff;border:1px solid #99f6e4;border-radius:10px;padding:10px;margin-bottom:10px;font-size:15px}\n")
        sb.append(".plist{display:grid;gap:7px}.pbtn{text-align:left;padding:11px;border:1px solid #cbd5e1;border-radius:12px;background:#f8fafc;cursor:pointer;font-size:14px;display:flex;flex-direction:column;gap:2px}.pbtn small{color:#64748b}.pbtn.on{border-color:#14b8a6;background:#f0fdfa}\n")
        sb.append(".pd{margin-top:12px;border:1px solid #99f6e4;border-radius:12px;padding:12px;background:#f0fdfa}.pd h3{margin:0 0 8px}.pd h4{margin:12px 0 6px}\n.grid{display:grid;grid-template-columns:1fr 1fr;gap:6px}@media(max-width:520px){.grid{grid-template-columns:1fr}}\n")
        sb.append(".kv{background:#fff;border-radius:8px;padding:7px 9px;font-size:13px;display:flex;flex-direction:column}.kv span{font-size:10px;color:#64748b;text-transform:uppercase}\n")
        sb.append(".media{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px;margin-top:10px}.m{background:#fff;border-radius:10px;padding:8px}.ml{font-size:11px;font-weight:700;margin-bottom:5px;color:#475569}.m img{width:100%;max-height:340px;object-fit:contain;border-radius:8px;background:#f1f5f9}\n")
        sb.append(".file{background:#fff;border-radius:8px;padding:8px;margin-bottom:6px;font-size:13px}.empty{color:#64748b;font-style:italic;padding:8px}\n")
        sb.append(".vbtn{display:block;width:100%;text-align:left;padding:12px;margin-bottom:8px;border:0;border-radius:10px;background:#fee2e2;color:#7f1d1d;font-size:14px;font-weight:700;cursor:pointer}.vbtn small{display:block;font-weight:400;color:#991b1b;font-size:11px}.vd{background:#fff7ed;border:1px solid #fed7aa;border-radius:10px;padding:8px;margin:-2px 0 10px}\n")
        sb.append("@media print{.tabs{display:none}.sec{display:block!important;box-shadow:none;page-break-inside:avoid}.pd{display:block!important}}\n</style></head><body><div class=\"wrap\">\n")
        sb.append("<div class=\"head\"><h1>Tenant File ${esc(tf.fileName)}</h1><div>Group: ${esc(tf.groupName)} • File created: ${esc(fmtDay(tf.createdAt))} • Persons: ${tf.active().size} • Final Advance: ${mon(tf.advance)}</div></div>\n")
        sb.append("<div class=\"tabs\"><button class=\"tab on\" id=\"t_h\" onclick=\"show('h')\">📜 History</button><button class=\"tab\" id=\"t_a\" onclick=\"show('a')\">💰 Advance</button><button class=\"tab\" id=\"t_p\" onclick=\"show('p')\">👥 Persons</button><button class=\"tab\" id=\"t_v\" onclick=\"show('v')\">🚪 Vacate Details</button><button class=\"tab\" id=\"t_x\" onclick=\"saveSelf()\">⬇ Export HTML</button></div>\n")

        // History
        data class Ev(val key: String, val cls: String, val dt: String, val title: String, val text: String)
        val ev = ArrayList<Ev>()
        if (tf.createdAt.isNotBlank()) ev.add(Ev(tf.createdAt.take(10), "file", fmtDay(tf.createdAt), "File created", "File ${tf.fileName} created in group ${tf.groupName}"))
        persons.forEachIndexed { i, p -> if (i > 0 && p.entryDate.isNotBlank()) ev.add(Ev(p.entryDate.take(10), "add", fmtDay(p.entryDate), "Person added", "${p.name} added to the file")) }
        bills.filter { it.billNo == tf.fileName }.forEach { b -> ev.add(Ev(b.month + "-10", "bill", monthLabel(b.month), "Bill created", "Bill for ${monthLabel(b.month)} • ${mon(b.billAmount)} • ${b.persons} person(s)")) }
        vac.filter { it.fileName == tf.fileName }.forEach { x -> ev.add(Ev(x.vacatedDate.take(10), "exit", fmtDay(x.vacatedDate), "Vacated", "File vacated • advance ${mon(x.advance)} • bill ${mon(x.due)} • " + (if (x.repay > 0) "repaid ${mon(x.repay)}" else "collected ${mon(x.collect)}"))) }
        sb.append("<div class=\"sec on\" id=\"s_h\">")
        if (ev.isEmpty()) sb.append("<div class=\"empty\">No history yet.</div>")
        ev.sortedBy { it.key }.forEach { e -> sb.append("<div class=\"ev ${e.cls}\"><div class=\"dt\">${esc(e.dt)}</div><div><b>${esc(e.title)}</b><div>${esc(e.text)}</div></div></div>") }
        sb.append("</div>\n")

        // Advance
        sb.append("<div class=\"sec\" id=\"s_a\"><div class=\"sum\">Final Advance: <b>${mon(tf.advance)}</b></div><table><tr><th>Date</th><th>Details</th><th class=\"r\">Amount</th><th class=\"r\">Balance</th></tr>")
        var run = 0.0; var firstCredit = true
        txns.filter { it.fileName == tf.fileName }.sortedWith(compareBy({ it.date }, { it.id })).forEach { x ->
            val cr = x.kind == "CREDIT"; run += if (cr) x.amount else -x.amount
            val label = if (cr) (if (firstCredit) "Initial advance paid" else "Advance paid (added)") else "Advance withdrawn / debited"
            if (cr) firstCredit = false
            sb.append("<tr class=\"${if (cr) "cre" else "deb"}\"><td>${esc(fmtDay(x.date))}</td><td>${esc(label)}${if (x.note.isNotBlank()) " • " + esc(x.note) else ""}</td><td class=\"r\">${if (cr) "+" else "−"} ${mon(x.amount)}</td><td class=\"r\">${mon(run)}</td></tr>")
        }
        sb.append("</table></div>\n")

        // Persons
        sb.append("<div class=\"sec\" id=\"s_p\"><div class=\"plist\">")
        persons.forEachIndexed { i, p -> sb.append("<button class=\"pbtn\" onclick=\"showP($i,this)\"><b>${i + 1}. ${esc(p.name)}</b><small>${esc(p.mobile)}</small></button>") }
        sb.append("</div>")
        val photos = ArrayList<Pair<String, String>>()
        listOf("Photo" to "Photo", "ID_Proof" to "ID Proof").forEach { (type, label) ->
            (Attachments.dir(ctx, tf.fileName, type).listFiles()?.filter { it.isFile && Img.isImage(it) } ?: emptyList()).sortedBy { it.name }.take(4).forEach { f ->
                Img.dataUri(f, 600)?.let { photos.add(label to it) }
            }
        }
        val others = ArrayList<String>()
        listOf("Audio", "Other").forEach { type -> (Attachments.dir(ctx, tf.fileName, type).listFiles()?.filter { it.isFile } ?: emptyList()).forEach { others.add("$type • ${it.name}") } }
        persons.forEachIndexed { i, p ->
            sb.append("<div class=\"pd\" id=\"pd$i\" style=\"display:none\">\n        <h3>${esc(p.name)}</h3>\n        <div class=\"grid\">")
            listOf("Name" to p.name, "Age" to p.age, "Father Name" to p.fatherName, "Mobile" to p.mobile, "Company" to p.company,
                "ID Type" to p.idType.ifBlank { "Aadhar Card" }, "ID Number" to p.aadhaar, "Address" to p.address,
                "Entry Date" to (if (p.entryDate.isBlank()) "" else fmtDay(p.entryDate)), "Exit Date" to (if (p.exitDate.isBlank()) "" else fmtDay(p.exitDate))).forEach { (k, value) ->
                sb.append("<div class=\"kv\"><span>${esc(k)}</span><b>${v(value)}</b></div>")
            }
            sb.append("</div>\n        <div class=\"media\">")
            photos.forEach { (l, uri) -> sb.append("<div class=\"m\"><div class=\"ml\">${esc(l)}</div><img src=\"$uri\"></div>") }
            sb.append("</div>\n        ")
            if (photos.isEmpty() && others.isEmpty()) sb.append("<div class=\"empty\">No photo / proof / attachments / audio uploaded.</div>")
            others.forEach { sb.append("<div class=\"file\">${esc(it)}</div>") }
            sb.append("\n      </div>")
        }
        sb.append("</div>\n")

        // Vacate
        sb.append("<div class=\"sec\" id=\"s_v\">")
        val myVac = vac.filter { it.fileName == tf.fileName }
        if (myVac.isEmpty()) sb.append("<div class=\"empty\">Not vacated yet.</div>")
        val lastReading = readings.filter { it.billNo == tf.fileName && !it.pending() }.maxByOrNull { it.month }
        myVac.forEachIndexed { i, x ->
            sb.append("<button class=\"vbtn\" onclick=\"showV($i)\">🚪 Vacate Details • ${esc(fmtDay(x.vacatedDate))}<small>${esc(x.names)}</small></button><div class=\"vd\" id=\"vd$i\" style=\"display:none\"><table>")
            fun tr(k: String, value: String, style: String = "") = sb.append("<tr><td>${esc(k)}</td><td class=\"r\" style=\"$style\">$value</td></tr>")
            tr("Date of vacation", esc(fmtDay(x.vacatedDate))); tr("Tenant(s)", esc(x.names)); tr("Total advance", mon(x.advance)); tr("Bill amount (on vacate date)", mon(x.due))
            if (lastReading != null) tr("Units used", "${"%.0f".format(lastReading.prevReading ?: 0.0)} → ${"%.0f".format(lastReading.currReading)} = ${"%.0f".format(lastReading.units)} units")
            tr("Total amount less advance", if (x.repay > 0) "${mon(x.repay)} advance left (advance ${mon(x.advance)} − bill ${mon(x.due)})" else "${mon(x.collect)} to collect (bill ${mon(x.due)} − advance ${mon(x.advance)})")
            if (x.repay > 0) tr("Amount repaid to tenant", mon(x.repay), "color:#dc2626;font-weight:700")
            if (x.collect > 0) tr("Amount to collect from tenant", mon(x.collect), "color:#dc2626;font-weight:700")
            sb.append("</table></div>")
        }
        sb.append("</div>\n</div><script>\n")
        sb.append("function showV(i){var e=document.getElementById('vd'+i);if(e)e.style.display=e.style.display==='block'?'none':'block';}\n")
        sb.append("function saveSelf(){var h='<!doctype html>'+document.documentElement.outerHTML;var b=new Blob([h],{type:'text/html'});var a=document.createElement('a');a.href=URL.createObjectURL(b);a.download=(document.title||'file').replace(/[^A-Za-z0-9_-]+/g,'_')+'.html';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},1500);}\n")
        sb.append("function show(k){['h','a','p','v'].forEach(function(x){document.getElementById('s_'+x).classList.toggle('on',x===k);document.getElementById('t_'+x).classList.toggle('on',x===k);});}\n")
        sb.append("function showP(i,b){document.querySelectorAll('.pd').forEach(function(e){e.style.display='none'});document.querySelectorAll('.pbtn').forEach(function(e){e.classList.remove('on')});var d=document.getElementById('pd'+i);if(d){d.style.display='block';d.scrollIntoView({behavior:'smooth',block:'start'});}if(b)b.classList.add('on');}\n")
        sb.append("</script></body></html>")
        sb.toString()
    }
}
