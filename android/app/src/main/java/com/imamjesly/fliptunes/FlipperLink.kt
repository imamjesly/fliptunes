package com.imamjesly.fliptunes

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The Bluetooth Low Energy connection to the Flipper.
 *
 * - Connects to the Flipper the user picked (remembered in Prefs).
 * - Sends text to the Flipper's serial RX characteristic, in chunks that fit the link.
 * - Listens for command lines ("C:PP") on the TX characteristic.
 * - Reconnects automatically every 5 seconds if the link drops.
 *
 * All Bluetooth callbacks are moved onto the main thread so state stays simple.
 */
@SuppressLint("MissingPermission") // the screen only starts the bridge after permissions are granted
class FlipperLink(
    private val context: Context,
    private val onCommand: (Protocol.FlipperCommand) -> Unit,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var ready = false
    private var running = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private var chunkSize = 20 // grows after the MTU is negotiated
    private val incoming = StringBuilder()

    /** Called each time the link becomes ready, so the service can send the current song. */
    var onReady: (() -> Unit)? = null

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        close()
        setState(LinkState.OFF, "Stopped")
    }

    /** Used after the user picks a different Flipper. */
    fun reconnect() {
        close()
        if (running) connect()
    }

    fun send(text: String) {
        main.post {
            if (!ready) return@post
            val bytes = text.toByteArray(Charsets.US_ASCII)
            var i = 0
            while (i < bytes.size) {
                val end = minOf(i + chunkSize, bytes.size)
                writeQueue.addLast(bytes.copyOfRange(i, end))
                i = end
            }
            pump()
        }
    }

    // ---------------------------------------------------------------------

    private fun connect() {
        val address = Prefs.address(context)
        if (address == null) {
            setState(LinkState.OFF, "Choose your Flipper first")
            return
        }
        if (adapter?.isEnabled != true) {
            setState(LinkState.ERROR, "Phone Bluetooth is off")
            retryLater()
            return
        }
        setState(LinkState.CONNECTING, "Connecting to ${Prefs.name(context) ?: address}")
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun close() {
        ready = false
        rx = null
        writeQueue.clear()
        writing = false
        incoming.clear()
        gatt?.close()
        gatt = null
    }

    private fun retryLater() {
        if (!running) return
        main.postDelayed({ if (running && gatt == null) connect() }, 5000)
    }

    /** Send the next queued chunk. Only one write can be in flight at a time. */
    private fun pump() {
        if (writing || !ready) return
        val g = gatt ?: return
        val c = rx ?: return
        val data = writeQueue.removeFirstOrNull() ?: return
        writing = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) {
            // The stack was busy; put it back and try again shortly
            writeQueue.addFirst(data)
            writing = false
            main.postDelayed({ pump() }, 50)
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        main.post {
            incoming.append(String(bytes, Charsets.US_ASCII))
            while (true) {
                val end = incoming.indexOf("\n")
                if (end < 0) break
                val line = incoming.substring(0, end).trim()
                incoming.delete(0, end + 1)
                val command = Protocol.parseCommand(line)
                if (command != null) {
                    AppState.log("Flipper sent: $line")
                    onCommand(command)
                }
            }
        }
    }

    private fun setState(state: LinkState, detail: String) {
        AppState.link.value = state
        AppState.linkDetail.value = detail
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    setState(LinkState.CONNECTING, "Connected, setting up...")
                    g.requestMtu(247) // bigger packets = fewer chunks
                } else {
                    AppState.log("Disconnected (status $status)")
                    close()
                    setState(LinkState.CONNECTING, "Waiting for Flipper... (retrying)")
                    retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                chunkSize = (mtu - 3).coerceIn(20, 180)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val service = g.getService(Protocol.SERVICE_UUID)
                val rxChar = service?.getCharacteristic(Protocol.RX_UUID)
                val txChar = service?.getCharacteristic(Protocol.TX_UUID)
                if (rxChar == null || txChar == null) {
                    setState(LinkState.ERROR, "Flipper serial service not found")
                    g.disconnect()
                    return@post
                }
                rx = rxChar
                // Ask the Flipper to send us its messages (indications)
                g.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(Protocol.CCCD_UUID)
                if (cccd == null) {
                    onLinkReady()
                } else if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { onLinkReady() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                if (status != BluetoothGatt.GATT_SUCCESS) AppState.log("Write failed (status $status)")
                pump()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == Protocol.TX_UUID) handleIncoming(value)
        }

        // Android 12
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == Protocol.TX_UUID) {
                @Suppress("DEPRECATION")
                handleIncoming(c.value ?: return)
            }
        }
    }

    private fun onLinkReady() {
        ready = true
        setState(LinkState.CONNECTED, "Connected to ${Prefs.name(context) ?: "Flipper"}")
        AppState.log("Flipper link ready")
        onReady?.invoke()
        pump()
    }
}
