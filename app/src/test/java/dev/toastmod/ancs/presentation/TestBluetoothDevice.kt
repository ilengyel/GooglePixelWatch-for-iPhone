package dev.toastmod.ancs.presentation

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

internal class TestBluetoothDevice(address: String = "00:11:22:33:44:55") {
    val device: BluetoothDevice = mock(BluetoothDevice::class.java)
    val connections = mutableListOf<Connection>()

    init {
        `when`(device.address).thenReturn(address)
        `when`(device.connectGatt(any(Context::class.java), eq(false),
            any(BluetoothGattCallback::class.java), eq(BluetoothDevice.TRANSPORT_LE)))
            .thenAnswer { invocation ->
                Connection(invocation.getArgument(2)).also { connections += it }.gatt
            }
    }

    class Connection(val callback: BluetoothGattCallback) {
        val gatt: BluetoothGatt = mock(BluetoothGatt::class.java)
        val update = characteristic(AMS_ENTITY_UPDATE)
        val remote = characteristic(AMS_REMOTE_COMMAND)
        val attribute = characteristic(AMS_ENTITY_ATTRIBUTE)
        val source = characteristic(ANCS_NOTIFICAITON_SOURCE)
        val dataSource = characteristic(ANCS_DATA_SOURCE)
        val control = characteristic(ANCS_CONTROL_POINT)
        val operations = mutableListOf<PendingOperation>()
        val pending = ArrayDeque<PendingOperation>()
        var closed = false
            private set
        var writeStartStatus = BluetoothStatusCodes.SUCCESS
        private val services = listOf(
            BluetoothGattService(AMS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(update)
                addCharacteristic(remote)
                addCharacteristic(attribute)
            },
            BluetoothGattService(ANCS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(source)
                addCharacteristic(dataSource)
                addCharacteristic(control)
            }
        ).associateBy { it.uuid }

        init {
            `when`(gatt.getService(any(UUID::class.java))).thenAnswer {
                services[it.getArgument<UUID>(0)]
            }
            `when`(gatt.discoverServices()).thenAnswer {
                record("services", null) { status, _ -> callback.onServicesDiscovered(gatt, status) }
                true
            }
            `when`(gatt.setCharacteristicNotification(any(BluetoothGattCharacteristic::class.java),
                anyBoolean())).thenReturn(true)
            `when`(gatt.writeDescriptor(any(BluetoothGattDescriptor::class.java),
                any(ByteArray::class.java))).thenAnswer {
                val descriptor = it.getArgument<BluetoothGattDescriptor>(0)
                record("descriptor", descriptor.characteristic.uuid, it.getArgument(1)) { status, _ ->
                    callback.onDescriptorWrite(gatt, descriptor, status)
                }
                BluetoothStatusCodes.SUCCESS
            }
            `when`(gatt.writeCharacteristic(any(BluetoothGattCharacteristic::class.java),
                any(ByteArray::class.java), anyInt())).thenAnswer {
                val characteristic = it.getArgument<BluetoothGattCharacteristic>(0)
                if (writeStartStatus == BluetoothStatusCodes.SUCCESS) {
                    record("write", characteristic.uuid, it.getArgument(1)) { status, _ ->
                        callback.onCharacteristicWrite(gatt, characteristic, status)
                    }
                }
                writeStartStatus
            }
            `when`(gatt.readCharacteristic(any(BluetoothGattCharacteristic::class.java))).thenAnswer {
                val characteristic = it.getArgument<BluetoothGattCharacteristic>(0)
                record("read", characteristic.uuid) { status, value ->
                    callback.onCharacteristicRead(gatt, characteristic, value, status)
                }
                true
            }
            doAnswer {
                closed = true
                null
            }.`when`(gatt).close()
        }

        private fun record(
            kind: String,
            uuid: UUID?,
            value: ByteArray = byteArrayOf(),
            complete: (Int, ByteArray) -> Unit
        ) {
            val operation = PendingOperation(kind, uuid, value.copyOf(), complete)
            operations += operation
            pending += operation
        }

        fun finishNext(status: Int = BluetoothGatt.GATT_SUCCESS, value: ByteArray = byteArrayOf()) {
            pending.removeFirst().complete(status, value)
        }

        fun notify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            callback.onCharacteristicChanged(gatt, characteristic, value)
        }

        private fun characteristic(uuid: UUID) = BluetoothGattCharacteristic(uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_WRITE))
        }
    }

    data class PendingOperation(
        val kind: String,
        val uuid: UUID?,
        val value: ByteArray,
        val complete: (Int, ByteArray) -> Unit
    )
}
