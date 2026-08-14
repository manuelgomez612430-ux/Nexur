package com.naxor.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.widget.Toast
import java.io.OutputStream
import java.util.*

class BluetoothPrinterHelper(private val context: Context) {

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connectAndPrint(device: BluetoothDevice, content: String) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream

            // Comandos ESC/POS básicos
            val cleanContent = content.replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u").replace("ñ", "n")
            
            // Inicializar impresora
            outputStream?.write(byteArrayOf(0x1B, 0x40))
            // Texto centrado
            outputStream?.write(byteArrayOf(0x1B, 0x61, 0x01))
            outputStream?.write(cleanContent.toByteArray())
            // Alimentar papel (3 líneas)
            outputStream?.write(byteArrayOf(0x1B, 0x64, 0x03))
            
            outputStream?.flush()
            Toast.makeText(context, "Imprimiendo...", Toast.LENGTH_SHORT).show()
            
            // Cerrar después de un momento
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    disconnect()
                }
            }, 2000)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error de impresión: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnect() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) { e.printStackTrace() }
    }
}
