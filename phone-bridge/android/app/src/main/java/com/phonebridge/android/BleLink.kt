package com.phonebridge.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Direct Bluetooth link to the iPhone (no server, no internet). The phone advertises a service UUID that
 * only the paired iPhone can compute and that changes every day. A GATT characteristic tells the iPhone the
 * L2CAP channel number; frames then flow over that channel, length-prefixed, encrypted like on the relay.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val ctx: Context,
    private val crypto: BridgeCrypto,          // only for the daily UUID
    private val onOpen: () -> Unit,
    private val onFrame: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    companion object {
        val PSM_CHAR: UUID = UUID.fromString("0B1D6E7A-5C3E-4F2A-9D11-7A6C2F9E4B01")
        private const val MAX_FRAME = 8 shl 20

        fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()
    }

    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    @Volatile private var running = false
    private var server: BluetoothServerSocket? = null
    private var gatt: BluetoothGattServer? = null
    private var day = ""
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: DataOutputStream? = null

    val connected: Boolean get() = out != null

    private val advertiseCallback = object : AdvertiseCallback() {}

    fun start(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled || a.bluetoothLeAdvertiser == null) return false
        running = true
        val srv = try { a.listenUsingInsecureL2capChannel() } catch (e: Exception) { return false }
        server = srv
        advertise(srv.psm)
        thread(name = "pb-ble-accept") {
            while (running) {
                val s = try { srv.accept() } catch (e: Exception) { if (running) Thread.sleep(1000); continue }
                serve(s)
            }
        }
        return true
    }

    fun stop() {
        running = false
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    /** Call now and then: switches to the new UUID after midnight UTC. */
    fun refreshDay() {
        val srv = server ?: return
        if (running && today() != day) {
            try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            advertise(srv.psm)
        }
    }

    private fun advertise(psm: Int) {
        day = today()
        val uuid = crypto.bleUuid(day)
        val psmBytes = byteArrayOf((psm and 0xff).toByte(), ((psm shr 8) and 0xff).toByte())
        val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(PSM_CHAR,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        gatt = manager.openGattServer(ctx, object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                     characteristic: BluetoothGattCharacteristic) {
                val value = if (characteristic.uuid == PSM_CHAR) psmBytes else ByteArray(0)
                gatt?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0,
                    value.copyOfRange(minOf(offset, value.size), value.size))
            }
        })?.also { it.addService(service) }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // nothing that names this phone
            .addServiceUuid(ParcelUuid(uuid))
            .build()
        adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun serve(s: BluetoothSocket) {
        try { socket?.close() } catch (_: Exception) {}
        socket = s
        val input = DataInputStream(s.inputStream)
        out = DataOutputStream(s.outputStream)
        onOpen()
        try {
            while (running) {
                val len = input.readInt()
                if (len <= 0 || len > MAX_FRAME) break
                val buf = ByteArray(len)
                input.readFully(buf)
                onFrame(String(buf, Charsets.UTF_8))
            }
        } catch (_: Exception) {
        } finally {
            out = null
            try { s.close() } catch (_: Exception) {}
            if (socket === s) socket = null
            onClosed()
        }
    }

    /** Sends one frame; returns false when the link is down. */
    fun send(frame: String): Boolean {
        val o = out ?: return false
        return try {
            val b = frame.toByteArray(Charsets.UTF_8)
            synchronized(o) {
                o.writeInt(b.size)
                o.write(b)
                o.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
