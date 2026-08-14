package com.naxor.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "price_history")
public class PriceHistoryEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public int productId;
    public double oldPrice;
    public double newPrice;
    public long timestamp;

    public PriceHistoryEntity(int productId, double oldPrice, double newPrice) {
        this.productId = productId;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.timestamp = System.currentTimeMillis();
    }
}

