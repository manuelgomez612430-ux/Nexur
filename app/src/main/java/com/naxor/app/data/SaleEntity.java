package com.naxor.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "sales")
public class SaleEntity {
    @PrimaryKey
    @NonNull
    public String id;
    public String transactionId; // Nuevo campo para agrupar ventas
    public String productId; // Cambiado a String
    public String nombreProducto;
    public String categoria; // Nuevo campo para mÃ©tricas
    public int cantidad;
    public double precioVenta;
    public double costoUnitario;
    public double total;
    public String paymentMethod; // EFECTIVO, DIGITAL, TARJETA
    public long timestamp;
    public boolean isSynced = false;
    public boolean isDeleted = false;

    // Campos para Comprobantes ElectrÃ³nicos (SUNAT)
    public String documentType; // BOLETA, FACTURA, NOTA_VENTA
    public String series;       // B001, F001, NV01
    public int correlative;
    public String customerDoc;  // DNI o RUC
    public String customerName; // Nombre o RazÃ³n Social
    public String customerAddress;

    public SaleEntity() {
        this.id = java.util.UUID.randomUUID().toString();
    }

    @Ignore
    public SaleEntity(String transactionId, String productId, String nombreProducto, String categoria, int cantidad, double precioVenta, double costoUnitario, String paymentMethod) {
        this.id = java.util.UUID.randomUUID().toString();
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
        this.isSynced = false;
        this.isDeleted = false;
    }
}

