package com.sensirion.sps30.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.sensirion.sps30.wire.ServerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Bluetooth-LE transport carrying the SAME newline-delimited JSONL as the TCP path,
 * over a Nordic-UART-Service (NUS) characteristic pair. Chosen so it works on both the
 * Pi (BlueZ) and the future ESP32-C6 (BLE-only). See BLUETOOTH_DESIGN.md.
 *
 * Uses the deprecated (but still functional through API 35) synchronous GATT value
 * methods to avoid API-33 vs older branching; the app gates the BLUETOOTH_SCAN/CONNECT
 * permissions before this runs, hence @SuppressLint("MissingPermission").
 */
@SuppressLint("MissingPermission")
class BleTransport(private val appContext: Context) : Transport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(replay = 0, extraBufferCapacity = 512)
    override val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    private val outbound = Channel<String>(capacity = 64)
    override fun send(line: String) { outbound.trySend(line) }

    private val bluetooth by lazy {
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    override suspend fun run() {
        val adapter = bluetooth.adapter
        var backoffMs = 1000L
        while (currentCoroutineContext().isActive) {
            if (adapter == null || !adapter.isEnabled) {
                _state.value = ConnectionState.Reconnecting("Bluetooth is off")
                delay(3000); continue
            }
            _state.value = ConnectionState.Connecting("scanning…")
            val device = scanForDevice(adapter.bluetoothLeScanner)
            if (device == null) {
                _state.value = ConnectionState.Reconnecting("no SPS30 device found")
                delay(backoffMs); backoffMs = (backoffMs * 2).coerceAtMost(8000L); continue
            }
            _state.value = ConnectionState.Connecting(device.name ?: device.address)
            try {
                connectAndStream(device)
                _state.value = ConnectionState.Reconnecting("disconnected")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Reconnecting(e.message ?: "BLE error")
            }
            backoffMs = 1000L
            delay(backoffMs)
        }
    }

    /** Scan for the first advertiser exposing the NUS service; null on timeout. */
    private suspend fun scanForDevice(scanner: android.bluetooth.le.BluetoothLeScanner?): BluetoothDevice? {
        if (scanner == null) return null
        return withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NUS_SERVICE)).build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (cont.isActive) {
                            runCatching { scanner.stopScan(this) }
                            cont.resume(result.device)
                        }
                    }
                }
                scanner.startScan(listOf(filter), settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    /** Connect, discover NUS, raise MTU, enable notifications, then pump both ways
     *  until the peer disconnects or the coroutine is cancelled. */
    private suspend fun connectAndStream(device: BluetoothDevice) = coroutineScope {
        val events = Channel<Ev>(Channel.UNLIMITED)
        var rx: BluetoothGattCharacteristic? = null
        var tx: BluetoothGattCharacteristic? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(if (newState == BluetoothProfile.STATE_CONNECTED) Ev.Connected else Ev.Disconnected)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.trySend(Ev.Services) }
            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { events.trySend(Ev.Mtu) }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                events.trySend(Ev.NotifyReady)
            }
            @Deprecated("uniform across API levels")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (c.uuid == NUS_TX) c.value?.let { events.trySend(Ev.Rx(it)) }
            }
        }

        val gatt = device.connectGatt(appContext, false, callback)
        try {
            suspend fun await(target: Ev, timeout: Long = OP_TIMEOUT_MS): Boolean {
                val ev = withTimeoutOrNull(timeout) {
                    var e = events.receive()
                    while (e != target && e !is Ev.Disconnected) e = events.receive()
                    e
                }
                if (ev is Ev.Disconnected) throw java.io.IOException("disconnected during setup")
                return ev != null
            }

            if (!await(Ev.Connected, CONNECT_TIMEOUT_MS)) throw java.io.IOException("connect timeout")
            if (!gatt.discoverServices()) throw java.io.IOException("discoverServices failed")
            if (!await(Ev.Services)) throw java.io.IOException("service discovery timeout")

            val svc = gatt.getService(NUS_SERVICE) ?: throw java.io.IOException("NUS service not found")
            rx = svc.getCharacteristic(NUS_RX); tx = svc.getCharacteristic(NUS_TX)
            if (rx == null || tx == null) throw java.io.IOException("NUS characteristics missing")

            gatt.requestMtu(PREFERRED_MTU); await(Ev.Mtu) // best-effort; ignore result

            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD) ?: throw java.io.IOException("no CCCD on TX")
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            if (!await(Ev.NotifyReady)) throw java.io.IOException("enable-notify timeout")

            _state.value = ConnectionState.Connected(device.name ?: device.address)

            // writer: drain outbound command lines to the RX characteristic
            val writer = launch {
                for (line in outbound) writeLine(gatt, rx!!, line)
            }
            try {
                val reassembler = JsonlReassembler { _messages.tryEmit(it) }
                while (true) {
                    when (val ev = events.receive()) {
                        is Ev.Rx -> reassembler.feed(ev.bytes)
                        is Ev.Disconnected -> break
                        else -> Unit
                    }
                }
            } finally {
                writer.cancel()
            }
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /** Write one command line, split into MTU-sized chunks if needed. */
    @Suppress("DEPRECATION")
    private fun writeLine(gatt: BluetoothGatt, rx: BluetoothGattCharacteristic, line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + WRITE_CHUNK, bytes.size)
            rx.value = bytes.copyOfRange(off, end)
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(rx)
            off = end
        }
    }

    private sealed interface Ev {
        data object Connected : Ev
        data object Disconnected : Ev
        data object Services : Ev
        data object Mtu : Ev
        data object NotifyReady : Ev
        data class Rx(val bytes: ByteArray) : Ev
    }

    companion object {
        // Nordic UART Service (the de-facto "BLE serial"); identical on Pi + ESP32-C6.
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write (phone->host)
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify (host->phone)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val PREFERRED_MTU = 247
        const val WRITE_CHUNK = 180 // conservative < negotiated MTU
        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val OP_TIMEOUT_MS = 8_000L
    }
}
