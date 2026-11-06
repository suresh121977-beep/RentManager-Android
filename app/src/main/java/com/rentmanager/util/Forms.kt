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
}

/** Tenant registration form as ONE html file – photos / ID proofs are embedded (base64), so it opens anywhere */
object Forms {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    suspend fun tenantHtml(ctx: Context, tf: TenantFile, g: GroupE?, txns: List<AdvTxn>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Tenant Form ${esc(tf.fileName)}</title><style>body{font-family:Arial,sans-serif;margin:20px;color:#111}")
        sb.append("h1{font-size:20px;text-align:center;margin:0 0 4px}h3{margin:18px 0 6px;font-size:15px;border-bottom:2px solid #333}")
        sb.append("table{width:100%;border-collapse:collapse}td,th{border:1px solid #555;padding:6px;font-size:13px;text-align:left;vertical-align:top}")
        sb.append("th{background:#eee}.ph{display:inline-block;margin:6px;text-align:center;font-size:11px}.ph img{height:150px;border:1px solid #444}")
        sb.append(".sig{display:flex;justify-content:space-between;margin-top:60px;font-size:13px}.sig div{border-top:1px solid #000;padding-top:4px;width:40%;text-align:center}")
        sb.append("@media print{body{margin:8mm}}</style></head><body>")
        sb.append("<h1>TENANT REGISTRATION FORM</h1><p style=\"text-align:center;margin:0 0 10px\">File No: <b>${esc(tf.fileName)}</b> &nbsp;•&nbsp; Date: ${LocalDate.now()}</p>")

        sb.append("<h3>Rent details</h3><table><tr><th>Group</th><th>Rent</th><th>Limit persons</th><th>Extra person rent</th><th>Limit advance</th><th>Extra person advance</th></tr><tr>")
        sb.append("<td>${esc(g?.name ?: tf.groupName)}</td><td>${money(g?.rent ?: 0.0)}</td><td>${g?.limitedPerson ?: 0}</td><td>${money(g?.extraPersonRent ?: 0.0)}</td>")
        sb.append("<td>${money(g?.limitAdvance ?: 0.0)}</td><td>${money(g?.extraPersonAdvance ?: 0.0)}</td></tr></table>")

        sb.append("<h3>Persons</h3><table><tr><th>#</th><th>Name</th><th>Mobile</th><th>Aadhaar / ID</th><th>Age</th><th>Address</th><th>Entry</th><th>Exit</th></tr>")
        tf.persons().forEachIndexed { i, p ->
            sb.append("<tr><td>${i + 1}</td><td>${esc(p.name)}</td><td>${esc(p.mobile)}</td><td>${esc(p.aadhaar)}</td><td>${esc(p.age)}</td><td>${esc(p.address)}</td><td>${esc(p.entryDate)}</td><td>${esc(p.exitDate.ifBlank { "Staying" })}</td></tr>")
        }
        sb.append("</table>")

        sb.append("<h3>Photos &amp; ID proofs</h3><div>")
        var any = false
        listOf("Photo", "ID_Proof").forEach { type ->
            (Attachments.dir(ctx, tf.fileName, type).listFiles()?.filter { it.isFile && Img.isImage(it) } ?: emptyList()).sortedBy { it.name }.forEach { f ->
                val bmp = Img.decodeSampled(f, 500)
                if (bmp != null) {
                    val bo = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 70, bo)
                    sb.append("<div class=\"ph\"><img src=\"data:image/jpeg;base64,${Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)}\"><br>${esc(type)} • ${esc(f.name)}</div>")
                    any = true
                }
            }
        }
        if (!any) sb.append("<p>No photo / ID images attached.</p>")
        sb.append("</div>")

        sb.append("<h3>Advance details</h3><table><tr><th>Date</th><th>Type</th><th>Amount</th><th>Note</th></tr>")
        txns.sortedBy { it.date }.forEach { x ->
            sb.append("<tr><td>${esc(fmtDay(x.date))}</td><td>${if (x.kind == "CREDIT") "Paid" else "Withdrawn"}</td><td>${money(x.amount)}</td><td>${esc(x.note)}</td></tr>")
        }
        sb.append("<tr><th colspan=\"2\">Advance balance</th><th colspan=\"2\">${money(tf.advance)}</th></tr></table>")

        sb.append("<h3>Declaration</h3><p style=\"font-size:13px\">I confirm that the details given above are true. I will pay the rent and electricity charges on time and keep the house in good condition. The advance will be returned after adjusting dues when I vacate.</p>")
        sb.append("<div class=\"sig\"><div>Tenant signature</div><div>Owner signature</div></div></body></html>")
        sb.toString()
    }
}
