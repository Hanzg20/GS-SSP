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
    val amountCents: Int,
    val status: String, // PENDING, PAID, FAILED, UPLOADED
    val createdAt: Long = System.currentTimeMillis(),
    val paymentMethod: String
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM local_products WHERE isActive = 1")
    suspend fun getAll(): List<ProductEntity>

    @Query("SELECT * FROM local_products WHERE barcode = :barcode LIMIT 1")
    suspend fun getByBarcode(barcode: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>): List<Long>
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM local_orders ORDER BY createdAt DESC")
    suspend fun getAll(): List<OrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: OrderEntity): Long

    @Update
    suspend fun update(order: OrderEntity): Int
}

@Database(entities = [ProductEntity::class, OrderEntity::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun orderDao(): OrderDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDatabase? = null

        fun getInstance(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    "ssp_local_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
