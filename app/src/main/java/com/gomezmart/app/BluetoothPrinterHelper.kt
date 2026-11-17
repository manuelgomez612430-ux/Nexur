package com.gomezmart.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.widget.Toast
import java.io.OutputStream
import java.util.*

class BluetoothPrinterHelper(context: Context) {

    private val appContext = context.applicationContext
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connectAndPrint(device: BluetoothDevice, content: String) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream

            val stream = outputStream ?: return
            val cleanContent = content.replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u").replace("ñ", "n")
            
            // Comandos ESC/POS (Inicializar + Centrar)
            writeInChunks(stream, byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 0x01))
            
            // Cuerpo del ticket en paquetes para evitar desbordamiento del búfer
            writeInChunks(stream, cleanContent.toByteArray(Charsets.UTF_8))
            
            // Alimentación de papel (3 líneas)
            writeInChunks(stream, byteArrayOf(0x1B, 0x64, 0x03))
            
            stream.flush()
            Toast.makeText(appContext, "Imprimiendo comprobante...", Toast.LENGTH_SHORT).show()
            
            // Cerrar socket Bluetooth limpiamente tras la transmisión
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    disconnect()
                }
            }, 1500)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(appContext, "Error de impresión: ${e.message}", Toast.LENGTH_LONG).show()
            disconnect()
        }
    }

    private fun writeInChunks(stream: OutputStream, bytes: ByteArray) {
        val chunkSize = 256
        var offset = 0
        while (offset < bytes.size) {
            val count = Math.min(chunkSize, bytes.size - offset)
            stream.write(bytes, offset, count)
            stream.flush()
            offset += count
            if (offset < bytes.size) {
                try { Thread.sleep(30) } catch (_: Exception) {}
            }
        }
    }

    fun disconnect() {
        try {
            outputStream?.flush()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            outputStream = null
            bluetoothSocket = null
        }
    }
}
