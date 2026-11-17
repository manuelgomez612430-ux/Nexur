package com.gomezmart.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    suspend fun getAllAccountsList(): List<AccountEntity>

    @Query("SELECT SUM(balance) FROM accounts WHERE isDeleted = 0")
    fun getTotalBalanceFlow(): Flow<Double?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity)

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE isSynced = 1 AND isDeleted = 0")
    suspend fun getSyncedAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE isDeleted = 1 AND isSynced = 0")
    suspend fun getDeletedAccounts(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts WHERE isSynced = 0")
    fun getUnsyncedCountFlow(): Flow<Int>
}