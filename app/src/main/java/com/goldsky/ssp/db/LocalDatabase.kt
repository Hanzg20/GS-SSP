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
    val isActive: Boolean = true
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
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM local_products WHERE isActive = 1")
    fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM local_products WHERE barcode = :barcode LIMIT 1")
    fun getByBarcode(barcode: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(products: List<ProductEntity>)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(expense: ExpenseEntity)

    @Delete
    fun delete(expense: ExpenseEntity)
}

@Database(entities = [ProductEntity::class, OrderEntity::class, ParkedOrderEntity::class, ExpenseEntity::class], version = 5, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun orderDao(): OrderDao
    abstract fun parkedOrderDao(): ParkedOrderDao
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDatabase? = null

        fun getInstance(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    "ssp_local_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
