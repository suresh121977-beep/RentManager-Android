@file:OptIn(ExperimentalMaterial3Api::class)
package com.rentmanager.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rentmanager.data.*
import com.rentmanager.domain.*
import com.rentmanager.util.Files

val Teal = Color(0xFF14B8A6); val Red = Color(0xFFF87171); val Green = Color(0xFF22C55E); val Amber = Color(0xFFFACC15)

@Composable fun RentTheme(yellow: Boolean, content: @Composable () -> Unit) {
    val p = if (yellow) Amber else Teal
    MaterialTheme(colorScheme = darkColorScheme(primary = p, onPrimary = Color(0xFF0F172A), background = Color(0xFF0B1220),
        surface = Color(0xFF111A2E), onSurface = Color(0xFFE2E8F0), surfaceVariant = Color(0xFF1B2640),
        onSurfaceVariant = Color(0xFF94A3B8), secondaryContainer = Color(0xFF223052)), content = content)
}

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    ElevatedCard(modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp), content = content) }

@Composable fun Lbl(t: String) = Text(t, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable fun H(t: String) = Text(t, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

@Composable fun NumField(label: String, v: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(v, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

@Composable fun TxtField(label: String, v: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(v, onChange, label = { Text(label) }, singleLine = true, modifier = modifier)

@Composable fun Stat(label: String, value: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) =
    ElevatedCard(modifier) { Column(Modifier.padding(10.dp)) { Lbl(label); Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = color) } }

@Composable fun Chips(opts: List<String>, sel: String, onSel: (String) -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { FilterChip(selected = it == sel, onClick = { onSel(it) }, label = { Text(it) }) }
    }

@Composable fun Btn(t: String, modifier: Modifier = Modifier, onClick: () -> Unit) = Button(onClick, modifier) { Text(t) }
@Composable fun BtnO(t: String, modifier: Modifier = Modifier, onClick: () -> Unit) = OutlinedButton(onClick, modifier) { Text(t) }

@Composable fun Empty(t: String) = Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))

/** Opens the system file picker and returns the text of the chosen file */
@Composable fun rememberTextPicker(onText: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) onText(Files.readUri(ctx, u)) }
    return { l.launch(arrayOf("*/*")) }
}

@Composable fun PayDialog(b: Bill, p: Payment?, onDismiss: () -> Unit, onPay: (Double, String) -> Unit) {
    val bal = balanceOf(b, p); var amt by remember { mutableStateOf("%.0f".format(bal)) }; var mode by remember { mutableStateOf("CASH") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pay • ${b.billNo}") },
        text = { Column {
            Text("Bill ${money(b.billAmount)} • Balance ${money(bal)}")
            NumField("Amount ₹", amt, { amt = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("CASH", "GPAY").forEach { FilterChip(selected = mode == it, onClick = { mode = it }, label = { Text(if (it == "CASH") "💵 Cash" else "📱 GPay") }) }
            } } },
        confirmButton = { TextButton(onClick = { num(amt)?.takeIf { it > 0 }?.let { onPay(it, mode); onDismiss() } }) { Text("Confirm & Pay") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable fun BillRow(b: Bill, p: Payment?, fav: Boolean, onPay: () -> Unit, onFav: () -> Unit, onLong: (() -> Unit)? = null, onOpen: (() -> Unit)? = null) {
    val st = statusOf(b, p); val bal = balanceOf(b, p)
    val c = when (st) { PayStatus.PAID -> Green; PayStatus.PARTIAL -> Amber; else -> Red }
    Panel(if (onOpen != null) Modifier.clickable { onOpen() } else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.billNo, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("Bill ${money(b.billAmount)}  •  Recd ${money(p?.received ?: 0.0)}", fontSize = 12.sp)
                Text("Balance ${money(bal)} • ${st.name}" + (p?.date?.takeIf { it.isNotBlank() }?.let { " • ${fmtDay(it)} ${p.mode}" } ?: ""), color = c, fontSize = 12.sp)
            }
            TextButton(onFav) { Text(if (fav) "⭐" else "☆", fontSize = 20.sp) }
            if (onLong != null) TextButton(onLong) { Text("↩") }
            Button(onPay, enabled = !b.vacant) { Text("💳 Pay") }
        }
    }
}
