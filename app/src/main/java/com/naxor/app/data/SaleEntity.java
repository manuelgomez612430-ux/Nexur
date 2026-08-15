package com.naxor.app.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "sales")
public class SaleEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String transactionId; // Nuevo campo para agrupar ventas
    public Integer productId; // null if not from inventory
    public String nombreProducto;
    public String categoria; // Nuevo campo para mÃ©tricas
    public int cantidad;
    public double precioVenta;
    public double costoUnitario;
    public double total;
    public String paymentMethod; // EFECTIVO, DIGITAL, TARJETA
    public long timestamp;
    public boolean isSynced = true;

    public SaleEntity() {}

    @Ignore
    public SaleEntity(String transactionId, Integer productId, String nombreProducto, String categoria, int cantidad, double precioVenta, double costoUnitario, String paymentMethod) {
        this.transactionId = transactionId;
        this.productId = productId;
        this.nombreProducto = nombreProducto;
        this.categoria = categoria;
        this.cantidad = cantidad;
        this.precioVenta = precioVenta;
        this.costoUnitario = costoUnitario;
        this.paymentMethod = paymentMethod;
        this.total = cantidad * precioVenta;
        this.timestamp = System.currentTimeMillis();
    }
}

