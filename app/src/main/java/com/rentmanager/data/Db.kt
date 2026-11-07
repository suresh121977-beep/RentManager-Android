package com.rentmanager.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AppDao {
    @Query("SELECT * FROM rent_groups ORDER BY name") abstract fun groups(): Flow<List<GroupE>>
    @Upsert abstract suspend fun upsertGroup(g: GroupE)
    @Query("DELETE FROM rent_groups WHERE name=:n") abstract suspend fun deleteGroup(n: String)

    @Query("SELECT * FROM readings") abstract fun readings(): Flow<List<Reading>>
    @Upsert abstract suspend fun upsertReadings(r: List<Reading>)
    @Query("DELETE FROM readings WHERE month=:m") abstract suspend fun deleteReadings(m: String)
    @Query("DELETE FROM readings") abstract suspend fun deleteAllReadings()

    @Query("SELECT * FROM bills") abstract fun bills(): Flow<List<Bill>>
    @Upsert abstract suspend fun upsertBills(b: List<Bill>)
    @Query("DELETE FROM bills WHERE month=:m") abstract suspend fun deleteBills(m: String)
    @Query("DELETE FROM bills") abstract suspend fun deleteAllBills()

    @Query("SELECT * FROM payments") abstract fun payments(): Flow<List<Payment>>
    @Upsert abstract suspend fun upsertPayment(p: Payment)
    @Query("DELETE FROM payments WHERE billNo=:b AND month=:m") abstract suspend fun deletePayment(b: String, m: String)
    @Query("DELETE FROM payments WHERE month=:m") abstract suspend fun deletePayments(m: String)
    @Query("DELETE FROM payments") abstract suspend fun deleteAllPayments()

    @Query("SELECT * FROM payment_logs ORDER BY id DESC") abstract fun logs(): Flow<List<PaymentLog>>
    @Insert abstract suspend fun insertLog(l: PaymentLog)
    @Query("DELETE FROM payment_logs WHERE id=:id") abstract suspend fun deleteLog(id: Long)
    @Query("DELETE FROM payment_logs WHERE month=:m") abstract suspend fun deleteLogs(m: String)
    @Query("DELETE FROM payment_logs") abstract suspend fun deleteAllLogs()

    @Query("SELECT * FROM finance ORDER BY date DESC, id DESC") abstract fun finance(): Flow<List<FinanceRec>>
    @Upsert abstract suspend fun upsertFinance(f: FinanceRec)
    @Query("DELETE FROM finance WHERE id=:id") abstract suspend fun deleteFinance(id: Long)
    @Query("DELETE FROM finance") abstract suspend fun deleteAllFinance()

    @Query("SELECT * FROM eb_bills ORDER BY serialNo") abstract fun eb(): Flow<List<EbBill>>
    @Upsert abstract suspend fun upsertEb(e: EbBill)
    @Query("DELETE FROM eb_bills WHERE id=:id") abstract suspend fun deleteEb(id: Long)
    @Query("DELETE FROM eb_bills") abstract suspend fun deleteAllEb()

    @Query("SELECT * FROM tenant_files ORDER BY fileName") abstract fun tenantFiles(): Flow<List<TenantFile>>
    @Upsert abstract suspend fun upsertTenantFiles(t: List<TenantFile>)
    @Query("DELETE FROM tenant_files WHERE fileName=:n") abstract suspend fun deleteTenantFile(n: String)

    @Query("SELECT * FROM msg_map") abstract fun msgMap(): Flow<List<MsgMap>>
    @Upsert abstract suspend fun upsertMsg(m: List<MsgMap>)

    @Query("SELECT * FROM favourites") abstract fun favs(): Flow<List<Fav>>
    @Upsert abstract suspend fun upsertFav(f: Fav)
    @Query("DELETE FROM favourites WHERE billNo=:b") abstract suspend fun deleteFav(b: String)

    @Query("SELECT * FROM vacated ORDER BY id DESC") abstract fun vacated(): Flow<List<Vacated>>
    @Insert abstract suspend fun insertVacated(v: Vacated)

    @Query("SELECT * FROM adv_txns ORDER BY date, id") abstract fun advTxns(): Flow<List<AdvTxn>>
    @Insert abstract suspend fun insertAdv(t: AdvTxn)
    @Query("DELETE FROM adv_txns WHERE id=:id") abstract suspend fun deleteAdv(id: Long)
    @Query("DELETE FROM adv_txns") abstract suspend fun deleteAllAdv()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertAdvs(l: List<AdvTxn>)

    @Query("SELECT * FROM visited") abstract fun visited(): Flow<List<Visited>>
    @Upsert abstract suspend fun upsertVisited(v: Visited)
    @Query("DELETE FROM visited WHERE billNo=:b AND month=:m") abstract suspend fun deleteVisited(b: String, m: String)
    @Query("DELETE FROM visited") abstract suspend fun deleteAllVisited()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertVisiteds(l: List<Visited>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertGroups(l: List<GroupE>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertPayments(l: List<Payment>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertLogs(l: List<PaymentLog>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertFinances(l: List<FinanceRec>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertEbs(l: List<EbBill>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertFavs(l: List<Fav>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertVacs(l: List<Vacated>)

    @Transaction open suspend fun resetMonth(m: String, withFinance: Boolean) {
        deleteReadings(m); deleteBills(m); deletePayments(m); deleteLogs(m)
    }
    @Transaction open suspend fun resetAll(withFinance: Boolean) {
        deleteAllReadings(); deleteAllBills(); deleteAllPayments(); deleteAllLogs()
        if (withFinance) { deleteAllFinance(); deleteAllEb() }
    }
    @Transaction open suspend fun restore(b: Backup) {
        deleteAllReadings(); deleteAllBills(); deleteAllPayments(); deleteAllLogs(); deleteAllFinance(); deleteAllEb()
        deleteAllAdv(); deleteAllVisited()
        insertAdvs(b.advTxns); insertVisiteds(b.visited)
        insertGroups(b.groups); upsertReadings(b.readings); upsertBills(b.bills); insertPayments(b.payments)
        insertLogs(b.logs); insertFinances(b.finance); insertEbs(b.eb); upsertTenantFiles(b.tenantFiles)
        upsertMsg(b.msgMap); insertFavs(b.favs); insertVacs(b.vacated)
    }
}

@Database(
    entities = [GroupE::class, Reading::class, Bill::class, Payment::class, PaymentLog::class, FinanceRec::class,
        EbBill::class, TenantFile::class, MsgMap::class, Fav::class, Vacated::class, AdvTxn::class, Visited::class],
    version = 3, exportSchema = false
)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rent_groups ADD COLUMN limitAdvance REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rent_groups ADD COLUMN extraPersonAdvance REAL NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `adv_txns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fileName` TEXT NOT NULL, `kind` TEXT NOT NULL, `amount` REAL NOT NULL, `date` TEXT NOT NULL, `note` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `visited` (`billNo` TEXT NOT NULL, `month` TEXT NOT NULL, `visitedDate` TEXT NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`billNo`, `month`))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN advanceDue REAL NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "rent_manager.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { inst = it }
        }
    }
}
