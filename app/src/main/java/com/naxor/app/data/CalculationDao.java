package com.naxor.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface CalculationDao {
    @Insert
    void insert(CalculationEntity calculation);

    @Delete
    void delete(CalculationEntity calculation);

    @Query("DELETE FROM calculations")
    void deleteAllCalculations();

    @Query("SELECT * FROM calculations ORDER BY timestamp DESC")
    List<CalculationEntity> getAllCalculations();

    @Query("SELECT * FROM calculations WHERE nombre LIKE :searchQuery OR categoria LIKE :searchQuery ORDER BY timestamp DESC")
    List<CalculationEntity> searchCalculations(String searchQuery);

    @Query("SELECT * FROM calculations WHERE categoria = :categoria ORDER BY timestamp DESC")
    List<CalculationEntity> getCalculationsByCategory(String categoria);
}
