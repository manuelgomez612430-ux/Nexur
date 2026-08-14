package com.naxor.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface PriceHistoryDao {
    @Insert
    void insert(PriceHistoryEntity history);

    @Query("SELECT * FROM price_history WHERE productId = :productId ORDER BY timestamp DESC")
    List<PriceHistoryEntity> getHistoryByProduct(int productId);
}

