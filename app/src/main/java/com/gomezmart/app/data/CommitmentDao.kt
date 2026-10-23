package com.gomezmart.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CommitmentDao {
    @Query("SELECT * FROM commitments WHERE isDeleted = 0 ORDER BY isPaid ASC, dueDate ASC, recurringDay ASC")
    fun getAllCommitments(): Flow<List<CommitmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommitment(commitment: CommitmentEntity)

    @Update
    suspend fun updateCommitment(commitment: CommitmentEntity)

    @Query("UPDATE commitments SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: String)

    @Delete
    suspend fun delete(commitment: CommitmentEntity)

    @Query("SELECT * FROM commitments WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsynced(): List<CommitmentEntity>

    @Query("SELECT * FROM commitments WHERE isDeleted = 1")
    suspend fun getDeletedCommitments(): List<CommitmentEntity>

    @Query("SELECT COUNT(*) FROM commitments WHERE isSynced = 0 AND isDeleted = 0")
    fun getUnsyncedCountFlow(): Flow<Int>

    @Query("SELECT * FROM commitments WHERE isDeleted = 0 AND isPaid = 0")
    suspend fun getActiveCommitmentsOnce(): List<CommitmentEntity>

    @Query("SELECT SUM(amount) FROM commitments WHERE isDeleted = 0 AND isPaid = 0")
    fun getTotalPendingAmountFlow(): Flow<Double?>

    @Query("SELECT SUM(amount) FROM commitments WHERE isDeleted = 0 AND isPaid = 0")
    suspend fun getTotalPendingAmountOnce(): Double?
}
