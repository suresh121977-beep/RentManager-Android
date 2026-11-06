package com.rentmanager.data

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "rent_groups")
data class GroupE(
    @PrimaryKey val name: String = "",
    val rent: Double = 3000.0,
    val limitedPerson: Int = 3,
    val extraPersonRent: Double = 500.0,
    val pricePerUnit: Double = 8.5,
    val extraCharge: Double = 0.0,
    val billCount: Int = 10,
    @ColumnInfo(defaultValue = "0") val limitAdvance: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val extraPersonAdvance: Double = 0.0
)

/** Bill Preparing rows (meter readings) */
@Entity(tableName = "readings", primaryKeys = ["billNo", "month"])
data class Reading(
    val billNo: String = "", val month: String = "",
    val prevReading: Double? = null, val currReading: Double = 0.0,
    val units: Double = 0.0, val price: Double = 8.5, val charge: Double = 0.0,
    val endOnlyUnresolved: Boolean = false
)

/** Bill Addition rows (final bill per flat per month) */
@Entity(tableName = "bills", primaryKeys = ["billNo", "month"])
data class Bill(
    val billNo: String = "", val month: String = "", val groupName: String = "",
    val units: Double = 0.0, val unitsCharge: Double = 0.0,
    val rentDays: Int = 30, val rent: Double = 0.0,
    val persons: Int = 0, val extraPersons: Int = 0, val extraTotal: Double = 0.0,
    val extraCharge: Double = 0.0, val prevBalance: Double = 0.0,
    val billAmount: Double = 0.0, val vacant: Boolean = false
)

/** Bill Management totals per flat per month */
@Entity(tableName = "payments", primaryKeys = ["billNo", "month"])
data class Payment(
    val billNo: String = "", val month: String = "",
    val received: Double = 0.0, val balance: Double = 0.0,
    val date: String = "", val mode: String = "", val lastAmount: Double = 0.0
)

@Entity(tableName = "payment_logs")
data class PaymentLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billNo: String = "", val month: String = "", val groupName: String = "",
    val paid: Double = 0.0, val mode: String = "",
    val oldBalance: Double = 0.0, val newBalance: Double = 0.0,
    val date: String = "", val time: String = ""
)

@Entity(tableName = "finance")
data class FinanceRec(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String = "SPEND", val amount: Double = 0.0,
    val category: String = "", val note: String = "", val date: String = ""
)

@Entity(tableName = "eb_bills")
data class EbBill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serialNo: Int = 0, val cardNumber: String = "", val groupName: String = "",
    val month: String = "", val prevReading: Double = 0.0, val currReading: Double = 0.0,
    val units: Double = 0.0, val billAmount: Double = 0.0, val paidAmount: Double = 0.0,
    val pricePerUnit: Double = 0.0, val paidDate: String = ""
)

data class Person(
    val name: String = "", val mobile: String = "", val aadhaar: String = "",
    val age: String = "", val entryDate: String = "", val exitDate: String = "",
    val address: String = "", val idType: String = ""
)

@Entity(tableName = "tenant_files")
data class TenantFile(
    @PrimaryKey val fileName: String = "",
    val groupName: String = "", val personsJson: String = "[]",
    val advance: Double = 0.0, val createdAt: String = ""
) {
    fun persons(): List<Person> = try {
        Gson().fromJson(personsJson, object : TypeToken<List<Person>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }
    fun active() = persons().filter { it.exitDate.isBlank() }
    fun withPersons(p: List<Person>) = copy(personsJson = Gson().toJson(p))
}

@Entity(tableName = "msg_map")
data class MsgMap(@PrimaryKey val billNo: String = "", val mobile: String = "")

@Entity(tableName = "favourites")
data class Fav(@PrimaryKey val billNo: String = "")

@Entity(tableName = "vacated")
data class Vacated(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String = "", val names: String = "", val vacatedDate: String = "",
    val due: Double = 0.0, val advance: Double = 0.0, val adjusted: Double = 0.0,
    val repay: Double = 0.0, val collect: Double = 0.0
)

/** Tenant advance ledger: CREDIT = advance paid in, DEBIT = withdrawal / vacate settlement */
@Entity(tableName = "adv_txns")
data class AdvTxn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String = "", val kind: String = "CREDIT", val amount: Double = 0.0,
    val date: String = "", val note: String = ""
)

/** Visited folder: a bill parked for the day (opens again next day) */
@Entity(tableName = "visited", primaryKeys = ["billNo", "month"])
data class Visited(
    val billNo: String = "", val month: String = "", val visitedDate: String = "", val reason: String = ""
)

data class Backup(
    val version: Int = 1, val savedAt: String = "",
    val groups: List<GroupE> = emptyList(), val readings: List<Reading> = emptyList(),
    val bills: List<Bill> = emptyList(), val payments: List<Payment> = emptyList(),
    val logs: List<PaymentLog> = emptyList(), val finance: List<FinanceRec> = emptyList(),
    val eb: List<EbBill> = emptyList(), val tenantFiles: List<TenantFile> = emptyList(),
    val msgMap: List<MsgMap> = emptyList(), val favs: List<Fav> = emptyList(),
    val vacated: List<Vacated> = emptyList(),
    val advTxns: List<AdvTxn> = emptyList(), val visited: List<Visited> = emptyList()
)
