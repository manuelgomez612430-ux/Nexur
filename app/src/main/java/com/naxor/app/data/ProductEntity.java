package com.naxor.app.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "products")
public class ProductEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String codigo;
    public String nombre;
    public String categoria;
    public int stock;
    public double precioCosto;
    public double precioVenta;
    public long timestamp;
    public long expirationDate; // 0 si no tiene vencimiento
    public String photoPath;    // Ruta de la imagen en el cel
    public String location;     // Pasillo / Estante
    public String descripcion;  // Descripción opcional
    public boolean isSynced = true;

    public ProductEntity() {}

    @Ignore
    public ProductEntity(String codigo, String nombre, String categoria, int stock, double precioCosto, double precioVenta) {
        this.codigo = codigo;
        this.nombre = nombre;
        this.categoria = categoria;
        this.stock = stock;
        this.precioCosto = precioCosto;
        this.precioVenta = precioVenta;
        this.timestamp = System.currentTimeMillis();
        this.expirationDate = 0;
        this.photoPath = null;
        this.location = "";
    }
}

