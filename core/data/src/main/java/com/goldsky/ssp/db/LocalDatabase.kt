package com.goldsky.ssp.db

import android.content.Context
import androidx.room.*

@Entity(tableName = "local_products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val priceCents: Int,
    val barcode: String?,
    val category: String,
    val isActive: Boolean = true,
    // null = "not tracked" (mirrors Product.stockQty), distinct from a real 0 (out of stock).
    val stockQty: Int? = null,
    // Mirrors Product.taxRateBps; null = fall back to the device tax rate.
    val taxRateBps: Int? = null
)

@Entity(tableName = "local_orders")
data class OrderEntity(
    @PrimaryKey val ecrRefNum: String,
    val subtotalCents: Int,
    val taxCents: Int = 0,
    val tipCents: Int = 0,
    val amountCents: Int,
    val status: String,
    val hardwareStatus: String? = null,
    val staffId: String? = null,
    val tableId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val paymentMethod: String
)

@Entity(tableName = "parked_orders")
data class ParkedOrderEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val tableName: String? = null,
    val cartJson: String, // Serialized Map<ProductId, Qty>
    val subtotalCents: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "local_expenses")
data class ExpenseEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val vendor: String,
    val amountCents: Int,
    val category: String,
    val imageUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // v8: the cloud `expenses` table is the record now; this is its offline
    // copy. synced = uploaded (an unsynced row is retried on the next sync).
    val spentOn: String? = null,      // yyyy-MM-dd, store-local
    val note: String? = null,
    val source: String = "manual",    // manual | receipt | voice
    val staffName: String? = null,
    val synced: Boolean = false
)

@Entity(tableName = "local_notes")
data class NoteEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "",
    val content: String,
    val tagsJson: String = "[]",
    val pinned: Boolean = false,
    val staffName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM local_notes ORDER BY pinned DESC, createdAt DESC")
    fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM local_notes WHERE synced = 0")
    fun unsynced(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(notes: List<NoteEntity>)

    @Query("DELETE FROM local_notes WHERE synced = 1")
    fun deleteSynced()

    @Query("DELETE FROM local_notes WHERE id = :id")
    fun deleteById(id: String)
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM local_products WHERE isActive = 1")
    fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM local_products WHERE barcode = :barcode LIMIT 1")
    fun getByBarcode(barcode: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(products: List<ProductEntity>)

    // Lets a cloud sync fully replace the local catalog (see
    // RetailRepository.syncWithCloud) instead of only ever upserting --
    // insertAll's REPLACE never removes a row whose product was deleted or
    // deactivated in the cloud, so without a purge stale products stuck
    // around in local_products (and the in-memory catalog) forever.
    @Query("DELETE FROM local_products")
    fun deleteAll()
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM local_orders ORDER BY createdAt DESC")
    fun getAll(): List<OrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(order: OrderEntity)

    @Update
    fun update(order: OrderEntity)
}

@Dao
interface ParkedOrderDao {
    @Query("SELECT * FROM parked_orders ORDER BY createdAt DESC")
    fun getAll(): List<ParkedOrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(order: ParkedOrderEntity)

    @Delete
    fun delete(order: ParkedOrderEntity)
    
    @Query("DELETE FROM parked_orders WHERE id = :id")
    fun deleteById(id: String)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM local_expenses ORDER BY createdAt DESC")
    fun getAll(): List<ExpenseEntity>

    @Query("SELECT * FROM local_expenses WHERE synced = 0")
    fun unsynced(): List<ExpenseEntity>

    @Query("UPDATE local_expenses SET synced = 1 WHERE id = :id")
    fun markSynced(id: String)

    @Query("DELETE FROM local_expenses WHERE synced = 1")
    fun deleteSynced()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(expenses: List<ExpenseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(expense: ExpenseEntity)

    @Delete
    fun delete(expense: ExpenseEntity)
}

@Database(entities = [ProductEntity::class, OrderEntity::class, ParkedOrderEntity::class, ExpenseEntity::class, NoteEntity::class], version = 9, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun orderDao(): OrderDao
    abstract fun parkedOrderDao(): ParkedOrderDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDatabase? = null

        // Explicit so this upgrade doesn't fall through to the destructive
        // fallback, which would also wipe local orders and parked carts.
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_notes (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL,
                        tagsJson TEXT NOT NULL DEFAULT '[]',
                        pinned INTEGER NOT NULL DEFAULT 0,
                        staffName TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        synced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_expenses ADD COLUMN spentOn TEXT")
                db.execSQL("ALTER TABLE local_expenses ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE local_expenses ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE local_expenses ADD COLUMN staffName TEXT")
                db.execSQL("ALTER TABLE local_expenses ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_products ADD COLUMN taxRateBps INTEGER")
            }
        }

        fun getInstance(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    "ssp_local_db"
                )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
