package com.naxor.app.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "calculations")
public class CalculationEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String nombre;
    public String categoria;
    public double costoLote;
    public int unidades;
    public double margen;
    public double costoTransporte;
    public double precioSugerido;
    public long timestamp;

    public CalculationEntity() {}

    @Ignore
    public CalculationEntity(String nombre, String categoria, double costoLote, int unidades, double margen, double costoTransporte, double precioSugerido) {
        this.nombre = nombre;
        this.categoria = categoria;
        this.costoLote = costoLote;
        this.unidades = unidades;
        this.margen = margen;
        this.costoTransporte = costoTransporte;
        this.precioSugerido = precioSugerido;
        this.timestamp = System.currentTimeMillis();
    }
}

