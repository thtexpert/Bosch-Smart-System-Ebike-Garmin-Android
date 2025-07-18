package com.RobPlow.BoschEbikeMonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

data class BoschMessage(
    val messageId: Int,
    val messageType: Int,
    val value: Int,
    val rawBytes: List<Int>
)

data class BikeStatus(
    var cadence: Int = 0,
    var humanPower: Int = 0,
    var motorPower: Int = 0,
    var speed: Double = 0.0,
    var battery: Int = 0,
    var assistMode: Int = 0,
)

class MainActivity : ComponentActivity() {

    // Your bike's specific MAC address - UPDATE THIS!
    private val BOSCH_BIKE_MAC = "00:04:63:A0:F8:AC" // Replace with your bike's MAC address

    // Bosch eBike Service UUIDs
    private val BOSCH_STATUS_SERVICE_UUID = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_STATUS_CHAR_UUID = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4")

    // Notification
    private val CHANNEL_ID = "bosch_ebike_notifications"
    private val NOTIFICATION_ID = 1

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    // BLE Advertising and GATT Server
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null

    // Standard BLE Service UUIDs for Power Meter and eBike
    private val CYCLING_POWER_SERVICE_UUID: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
    private val CYCLING_POWER_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002A63-0000-1000-8000-00805f9b34fb")
    private val CYCLING_POWER_FEATURE_CHAR_UUID: UUID = UUID.fromString("00002A65-0000-1000-8000-00805f9b34fb") // Optional

    // You might need to define a custom eBike service or find a standard one if it exists
    // For demonstration, let's create a custom "Bosch eBike Data Service"
    private val EBike_DATA_SERVICE_UUID: UUID = UUID.fromString("000018F0-0000-1000-8000-00805f9b34fb") // Example Custom UUID
    private val EBike_SPEED_CADENCE_CHAR_UUID: UUID = UUID.fromString("00002AF1-0000-1000-8000-00805f9b34fb") // Example Custom UUID
    private val EBike_BATTERY_CHAR_UUID: UUID = UUID.fromString("00002AF2-0000-1000-8000-00805f9b34fb") // Example Custom UUID
    private val EBike_ASSIST_MODE_CHAR_UUID: UUID = UUID.fromString("00002AF3-0000-1000-8000-00805f9b34fb") // Example Custom UUID

    private var cyclingPowerMeasurementCharacteristic: BluetoothGattCharacteristic? = null
    private var ebikeSpeedCadenceCharacteristic: BluetoothGattCharacteristic? = null
    private var ebikeBatteryCharacteristic: BluetoothGattCharacteristic? = null
    private var ebikeAssistModeCharacteristic: BluetoothGattCharacteristic? = null
    private var cyclingPowerFeatureCharacteristic: BluetoothGattCharacteristic? = null

    private val scanResults = mutableStateListOf<ScanResult>()
    private var isScanning by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var bikeData by mutableStateOf("No data")
    private var rawHexData by mutableStateOf("No data")
    private var manualMacAddress by mutableStateOf(BOSCH_BIKE_MAC)
    
    // Live bike status
    private var bikeStatus by mutableStateOf(BikeStatus())

    // Previous values for change detection - used for battery notification
    private var previousBattery = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeBluetooth()
            createNotificationChannel()
            // Initialize BLE advertising and GATT server after permissions are granted
            initializeGattServer()
            startAdvertising()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()
        setContent {
            BoschEBikeMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    fun MainScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bosch eBike Monitor",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connection Status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionStatus) {
                        "Connected" -> MaterialTheme.colorScheme.primaryContainer
                        "Connecting..." -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Status: $connectionStatus",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Target: $BOSCH_BIKE_MAC",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { connectToBoschBike() },
                    enabled = !isScanning && connectionStatus != "Connected"
                ) {
                    Text("Scan Devices")
                }

                Button(
                    onClick = { disconnect() },
                    enabled = connectionStatus == "Connected"
                ) {
                    Text("Disconnect")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Direct Connection:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualMacAddress,
                        onValueChange = { manualMacAddress = it },
                        label = { Text("MAC Address") },
                        placeholder = { Text("XX:XX:XX:XX:XX:XX") },  // Manual MAC Address Input
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { connectDirectly(manualMacAddress) },
                        enabled = !isScanning && connectionStatus != "Connected",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect to This MAC")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (connectionStatus == "Connected") {
                // Live Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🚴 Live Bike Status",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🔋 Battery: ${bikeStatus.battery}%", style = MaterialTheme.typography.titleMedium)
                                Text("⚡ Assist: ${getAssistModeName(bikeStatus.assistMode)}", style = MaterialTheme.typography.titleMedium)
                                Text("🦵 Human: ${bikeStatus.humanPower}W", style = MaterialTheme.typography.bodyMedium)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🚀 Speed: ${String.format("%.1f", bikeStatus.speed)} km/h", style = MaterialTheme.typography.titleMedium)
                                Text("🏃 Cadence: ${bikeStatus.cadence} RPM", style = MaterialTheme.typography.bodyMedium)
                                Text("⚙️ Motor: ${bikeStatus.motorPower}W", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // Show scan results for debugging
            if (scanResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Found Devices:",
                    style = MaterialTheme.typography.titleMedium
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(scanResults) { result ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = {
                                if (connectionStatus != "Connected") {
                                    connectToDevice(result.device)
                                }
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = result.device.name ?: "Unknown Device",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = result.device.address,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "RSSI: ${result.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getAssistModeName(mode: Int): String {
        return when (mode) {
            0 -> "Off"
            1 -> "Eco"
            2 -> "Tour"
            3 -> "Sport"
            4 -> "Turbo"
            else -> "Mode $mode"
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, // Added for BLE advertising
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestPermissionLauncher.launch(permissions)
    }

    private fun initializeBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser // Initialize advertiser
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "Bosch eBike Status"
                val descriptionText = "Batteryupdates from your eBike"
                val importance = NotificationManager.IMPORTANCE_DEFAULT
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }

                val notificationManager: NotificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e("NOTIFICATION", "Failed to create notification channel: ${e.message}")
        }
    }

    private fun sendBatteryNotification(message: String) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {

                val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🚴 Bosch eBike")
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)

                with(NotificationManagerCompat.from(this)) {
                    notify(NOTIFICATION_ID, builder.build())
                }
            }
        } catch (e: Exception) {
            Log.e("NOTIFICATION", "Failed to send notification: ${e.message}")
        }
    }

    private fun connectToBoschBike() {
        if (!hasPermissions()) {
            requestBluetoothPermissions()
            return
        }

        connectionStatus = "Scanning for devices..."
        scanResults.clear()
        startGeneralScan()
    }

    private fun connectDirectly(macAddress: String) {
        if (!hasPermissions()) return

        try {
            Log.d("BLE", "Attempting direct connection to: $macAddress")
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            connectionStatus = "Connecting directly to $macAddress..."
            connectToDevice(device)
        } catch (e: Exception) {
            Log.e("BLE", "Failed to connect directly: ${e.message}")
            connectionStatus = "Direct connection failed: ${e.message}"
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startGeneralScan() {
        if (!hasPermissions()) {
            Log.e("BLE", "Missing permissions for scanning")
            return
        }

        Log.d("BLE", "Starting BLE scan...")
        isScanning = true
        scanResults.clear()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                }
            }
            .setReportDelay(0)
            .build()

        try {
            bluetoothLeScanner.startScan(emptyList(), scanSettings, generalScanCallback)
            Log.d("BLE", "Scan started successfully")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to start scan: ${e.message}")
            isScanning = false
            connectionStatus = "Scan failed: ${e.message}"
        }

        // Stop scanning after 15 seconds
        Handler(mainLooper).postDelayed({
            if (isScanning) {
                try {
                    bluetoothLeScanner.stopScan(generalScanCallback)
                    Log.d("BLE", "Scan stopped after timeout")
                } catch (e: Exception) {
                    Log.e("BLE", "Error stopping scan: ${e.message}")
                }
                isScanning = false
                connectionStatus = "Scan complete - found ${scanResults.size} devices"
            }
        }, 15000)
    }

    private val generalScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasPermissions()) return
            val device = result.device
            val deviceName = device.name ?: "Unknown"
            val deviceAddress = device.address

            // Add to results if not already there
            if (!scanResults.any { it.device.address == deviceAddress }) {
                scanResults.add(result)
                // Log all devices for debugging
                Log.d("BLE", "Found device: $deviceName ($deviceAddress)")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE", "Scan failed with error: $errorCode")
            isScanning = false
            connectionStatus = "Scan failed: $errorCode"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermissions()) return

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectionStatus = "Connected! Discovering services..."
                        if (hasPermissions()) {
                            gatt?.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionStatus = "Disconnected"
                        bikeData = "No data"
                        rawHexData = "No data"
                        bikeStatus = BikeStatus() // Reset live status
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        connectionStatus = "Connecting..."
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(BOSCH_STATUS_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BOSCH_STATUS_CHAR_UUID)

                if (characteristic != null && hasPermissions()) {
                    // Enable notifications
                    gatt.setCharacteristicNotification(characteristic, true)

                    val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }

                    runOnUiThread {
                        connectionStatus = "Connected"
                    }
                } else {
                    runOnUiThread {
                        connectionStatus = "Service not found"
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.let { char ->
                val data = char.value ?: return
                val hexString = data.joinToString("-") { "%02X".format(it) }
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

                runOnUiThread {
                    rawHexData = hexString

                    // Parse the concatenated messages
                    val messages = parseBoschPacket(data.map { it.toInt() and 0xFF })
                    val parsedData = processParsedMessages(messages) // This will now update BLE server
                    bikeData = parsedData
                }
            }
        }
    }

    /**
     * Corrected Bosch packet parsing functions
     */

    /**
     * Decode a varint from the byte array starting at the given index
     * Returns Pair(decoded value, number of bytes consumed)
     */
    private fun decodeVarint(bytes: List<Int>, startIndex: Int = 0): Pair<Int, Int> {
        if (startIndex >= bytes.size) return Pair(0, 0)

        var result = 0
        var shift = 0
        var currentIndex = startIndex
        var bytesConsumed = 0

        try {
            while (currentIndex < bytes.size && bytesConsumed < 5) {
                val byte = bytes[currentIndex]
                result = result or ((byte and 0x7F) shl shift)
                bytesConsumed++
                currentIndex++

                // If MSB is 0, this is the last byte
                if ((byte and 0x80) == 0) {
                    break
                }
                shift += 7
            }
        } catch (e: Exception) {
            Log.e("VARINT", "Error decoding varint: ${e.message}")
            return Pair(0, 1)
        }

        return Pair(result, bytesConsumed)
    }

    /**
     * Parse a complete BLE packet that may contain multiple concatenated messages
     */
    private fun parseBoschPacket(bytes: List<Int>): List<BoschMessage> {
        val messages = mutableListOf<BoschMessage>()
        var index = 0

        Log.d("PARSER", "Parsing packet: ${bytes.joinToString("-") { "%02X".format(it) }}")

        try {
            while (index < bytes.size) {
                // Look for message start (0x30)
                if (bytes[index] != 0x30) {
                    index++
                    continue
                }
                // Check if we have enough bytes for a basic message header
                if (index + 2 >= bytes.size) break

                val messageLength = bytes[index + 1]
                Log.d("PARSER", "Found message start at index $index, length: $messageLength")

                // Message length is payload size, not including start byte and length byte
                val totalMessageSize = messageLength + 2

                if (messageLength < 2 || messageLength > 50) {
                    Log.w("PARSER", "Invalid message length: $messageLength")
                    index++
                    continue
                }

                if (index + totalMessageSize > bytes.size) {
                    Log.w("PARSER", "Not enough bytes for complete message (need ${totalMessageSize}, have ${bytes.size - index})")
                    break
                }

                
                if (index + 4 >= bytes.size) 
                    break
                val messageId = (bytes[index + 2] shl 8) or bytes[index + 3] // Extract the message ID (2 bytes after start and length)
                Log.d("PARSER", "Message ID: 0x${messageId.toString(16).uppercase()}")

                val messageBytes = bytes.subList(index, minOf(index + totalMessageSize, bytes.size)) // Get all message bytes including the extra data
                Log.d("PARSER", "Message bytes: ${messageBytes.joinToString("-") { "%02X".format(it) }}")

                // Determine if there's a data type byte and data
                var dataValue = 0
                var dataType = 0
                
                // If message length is <= 2, the dataValue = 0
                if (messageLength > 2) {
                    val dataTypeIndex = 4  // Position after start(1) + length(1) + messageId(2)
                    val dataStartIndex = 5  // Position after data type byte

                    if (dataTypeIndex < messageBytes.size) {
                        dataType = messageBytes[dataTypeIndex]
                        when (dataType) {
                            0x08 -> {
                                // Varint encoded data
                                if (dataStartIndex < messageBytes.size) {
                                    val dataBytes = messageBytes.subList(dataStartIndex, messageBytes.size)
                                    val (value, consumed) = decodeVarint(dataBytes, 0)
                                    dataValue = value
                                    Log.d("PARSER", "Decoded varint value: $dataValue (consumed $consumed bytes)")
                                }
                            }
                            0x0A -> {
                                // Different encoding type - try as raw bytes
                                if (dataStartIndex < messageBytes.size) {
                                    dataValue = messageBytes[dataStartIndex]
                                    Log.d("PARSER", "Using first data byte for 0x0A: $dataValue")
                                }
                            }
                            else -> {
                                // Unknown data type - try to parse as single byte or simple value
                                if (dataStartIndex < messageBytes.size) {
                                    // No varint (0x08) found - value is zero
                                    dataValue = 0
                                    Log.d("PARSER", "No varint found - setting value to 0")                           }
                            }
                        }
                    } else {
                        Log.w("PARSER", "Expected data type byte but message too short")
                    }
                }

                val message = BoschMessage(
                    messageId = messageId,
                    messageType = dataType,
                    value = dataValue,
                    rawBytes = messageBytes
                )

                messages.add(message)
                Log.d("PARSER", "Created message: ID=0x${messageId.toString(16)}, value=$dataValue")

                index += totalMessageSize // Move to the next message
            }
        } catch (e: Exception) {
            Log.e("PARSER", "Error parsing packet: ${e.message}")
            return messages
        }

        Log.d("PARSER", "Parsed ${messages.size} messages")
        return messages
    }

    /**
     * Process parsed messages and update bike status and BLE GATT server
     */
    private fun processParsedMessages(messages: List<BoschMessage>): String {
        if (messages.isEmpty()) return "No messages to parse"

        val parsedInfo = mutableListOf<String>()
        Log.d("PROCESSOR", "Processing ${messages.size} messages")

        try {
            for (message in messages) {
                Log.d("PROCESSOR", "Processing message ID: 0x${message.messageId.toString(16).uppercase()}, value: ${message.value}")

                when (message.messageId) {
                    0x985A -> {
                        // Cadence - divide by 2
                        val cadence = maxOf(0, message.value / 2)
                        bikeStatus = bikeStatus.copy(cadence = cadence)
                        parsedInfo.add("🏃 Cadence: $cadence RPM")
                        updateEbikeSpeedCadenceCharacteristic()
                    }
                    0x985B -> {
                        // Human Power - use value directly
                        val power = maxOf(0, message.value)
                        bikeStatus = bikeStatus.copy(humanPower = power)
                        parsedInfo.add("🦵 Human Power: ${power}W")
                        updateCyclingPowerMeasurementCharacteristic()
                    }
                    0x985D -> {
                        // Motor Power - (Consider if you want to include motor power in BLE characteristics)
                        val power = maxOf(0, message.value)
                        bikeStatus = bikeStatus.copy(motorPower = power)
                        parsedInfo.add("⚙️ Motor Power: ${power}W")
                    }
                    0x982D -> {
                        // Speed - divide by 100 for km/h
                        val speed = maxOf(0.0, message.value / 100.0)
                        bikeStatus = bikeStatus.copy(speed = speed)
                        parsedInfo.add("🚀 Speed: ${String.format("%.1f", speed)} km/h")
                        updateEbikeSpeedCadenceCharacteristic()
                    }
                    0x8088 -> {
                        // Battery percentage
                        val newBattery = message.value.coerceIn(0, 100)
                        if (newBattery != previousBattery && previousBattery != 0) {
                            sendBatteryNotification("🔋 Battery: $newBattery%")
                        }
                        previousBattery = newBattery
                        bikeStatus = bikeStatus.copy(battery = newBattery)
                        parsedInfo.add("🔋 Battery: ${newBattery}%")
                        updateEbikeBatteryCharacteristic()
                    }
                    0x9809 -> {
                        // Assist Mode - use value directly
                        val newAssistMode = message.value.coerceIn(0, 10)
                        bikeStatus = bikeStatus.copy(assistMode = newAssistMode)
                        parsedInfo.add("⚡ Assist Mode: ${getAssistModeName(newAssistMode)}")
                        updateEbikeAssistModeCharacteristic()
                    }
                    else -> {
                        // Unknown message ID
                        parsedInfo.add("❓ Unknown ID 0x${message.messageId.toString(16).uppercase()}: ${message.value}")
                        Log.d("PROCESSOR", "Unknown message ID: 0x${message.messageId.toString(16).uppercase()}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PROCESSOR", "Error processing messages: ${e.message}")
            return "Error processing messages: ${messages.size} found"
        }

        return if (parsedInfo.isNotEmpty()) {
            "Parsed ${messages.size} messages:\n" + parsedInfo.joinToString("\n")
        } else {
            "No recognized messages found"
        }
    }
    private fun disconnect() {
        Log.i("APP_STATE", "Disconnect button pressed.") // Info log for major action

        if (!hasPermissions()) {
            Log.e("DISCONNECT", "Cannot disconnect: Required Bluetooth permissions are missing.")
            runOnUiThread {
                connectionStatus = "Disconnected (Permissions missing)"
            }
            return
        }

        if (bluetoothGatt == null) {
            Log.w("DISCONNECT", "bluetoothGatt was already null. No active GATT connection to close.")
            runOnUiThread {
                connectionStatus = "Disconnected"
            }
        } else {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                runOnUiThread {
                    connectionStatus = "Disconnected"
                }
                Log.i("DISCONNECT", "Successfully disconnected GATT from bike.")
            } catch (e: Exception) {
                Log.e("DISCONNECT", "Error closing GATT connection: ${e.message}")
                runOnUiThread {
                    connectionStatus = "Disconnect Error: ${e.message}"
                }
            }
        }

        // Clean up advertising and GATT server regardless
        try {
            stopAdvertising()
            Log.i("APP_STATE", "BLE advertising stopped.")
        } catch (e: Exception) {
            Log.e("ADVERTISER", "Error stopping advertising on disconnect: ${e.message}")
        }

        try {
            closeGattServer()
            Log.i("APP_STATE", "GATT server closed.")
        } catch (e: Exception) {
            Log.e("GATT_SERVER", "Error closing GATT server on disconnect: ${e.message}")
        }

        runOnUiThread {
            bikeData = "No data"
            rawHexData = "No data"
            bikeStatus = BikeStatus()
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE, // Check for advertising permission
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        Log.d("BLE", "Permissions check: $allGranted")
        permissions.forEach { permission ->
            val granted = ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d("BLE", "$permission: $granted")
        }

        return allGranted
    }
    //region Helper functions to generate BLE characteristic values

    /**
     * Generates the byte array for the Cycling Power Measurement characteristic.
     * Format (little-endian):
     * - Flags (2 bytes): Indicates what data fields are present.
     * - Instantaneous Power (2 bytes, SINT16): Power in Watts.
     *
     * @param instantaneousPower The current instantaneous power in Watts.
     * @return Byte array representing the Cycling Power Measurement characteristic value.
     */
    private fun generateCyclingPowerMeasurement(instantaneousPower: Int): ByteArray {
        val flags: Short = 0x0000 // No flags set for now (e.g., no pedal power balance, no accumulated torque)
        val powerValue: Short = instantaneousPower.toShort() // Convert Int to Short

        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(flags)
            .putShort(powerValue)
            .array()
    }

    /**
     * Generates the byte array for the custom eBike Speed and Cadence characteristic.
     * Format (little-endian):
     * - Speed (2 bytes, UINT16): Speed multiplied by 100 (e.g., 25.50 km/h -> 2550)
     * - Cadence (2 bytes, UINT16): Cadence in RPM
     *
     * @param speed The current speed in km/h.
     * @param cadence The current cadence in RPM.
     * @return Byte array representing the eBike Speed and Cadence characteristic value.
     */
    private fun generateEbikeSpeedCadenceBytes(speed: Double, cadence: Int): ByteArray {
        val speedValue = (speed * 100).toInt().toShort() // Convert to Short, assuming max speed won't exceed Short.MAX_VALUE / 100
        val cadenceValue = cadence.toShort() // Convert to Short

        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(speedValue)
            .putShort(cadenceValue)
            .array()
    }
    //endregion

    //region BLE GATT Server and Advertising

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun initializeGattServer() {
        if (!hasPermissions()) {
            Log.e("GATT_SERVER", "Missing permissions for GATT server")
            return
        }
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        setupGattServices()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun setupGattServices() {
        // Cycling Power Service
        val cyclingPowerService = BluetoothGattService(CYCLING_POWER_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Cycling Power Measurement Characteristic (NOTIFY)
        cyclingPowerMeasurementCharacteristic = BluetoothGattCharacteristic(
            CYCLING_POWER_MEASUREMENT_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        cyclingPowerMeasurementCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // Client Characteristic Configuration Descriptor
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        cyclingPowerService.addCharacteristic(cyclingPowerMeasurementCharacteristic)

        // Cycling Power Feature Characteristic (READ) - Optional
        cyclingPowerFeatureCharacteristic = BluetoothGattCharacteristic(
            CYCLING_POWER_FEATURE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Set a default value for features (e.g., crank revolution measurement supported)
        cyclingPowerFeatureCharacteristic?.value = byteArrayOf(0x02, 0x00) // Flags for "Crank Revolution Measurement Supported"
        cyclingPowerService.addCharacteristic(cyclingPowerFeatureCharacteristic)

        bluetoothGattServer?.addService(cyclingPowerService)
        Log.d("GATT_SERVER", "Cycling Power Service added")

        // Bosch eBike Data Service (Custom Service)
        val ebikeDataService = BluetoothGattService(EBike_DATA_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // eBike Speed and Cadence Characteristic (NOTIFY, READ)
        ebikeSpeedCadenceCharacteristic = BluetoothGattCharacteristic(
            EBike_SPEED_CADENCE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ebikeSpeedCadenceCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // Client Characteristic Configuration Descriptor
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        ebikeDataService.addCharacteristic(ebikeSpeedCadenceCharacteristic)

        // eBike Battery Characteristic (NOTIFY, READ)
        ebikeBatteryCharacteristic = BluetoothGattCharacteristic(
            EBike_BATTERY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ebikeBatteryCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // Client Characteristic Configuration Descriptor
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        ebikeDataService.addCharacteristic(ebikeBatteryCharacteristic)

        // eBike Assist Mode Characteristic (NOTIFY, READ)
        ebikeAssistModeCharacteristic = BluetoothGattCharacteristic(
            EBike_ASSIST_MODE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        ebikeAssistModeCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // Client Characteristic Configuration Descriptor
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        ebikeDataService.addCharacteristic(ebikeAssistModeCharacteristic)


        bluetoothGattServer?.addService(ebikeDataService)
        Log.d("GATT_SERVER", "eBike Data Service added")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val deviceAddress = device?.address ?: "unknown"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("GATT_SERVER", "Device connected to GATT server: $deviceAddress")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("GATT_SERVER", "Device disconnected from GATT server: $deviceAddress")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic == null) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }

            when (characteristic.uuid) {
                CYCLING_POWER_MEASUREMENT_CHAR_UUID -> {
                    // Return current power data (0 if not available)
                    val value = generateCyclingPowerMeasurement(bikeStatus.humanPower)
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    Log.d("GATT_SERVER", "Read request for Power Measurement. Value: ${bikeStatus.humanPower}")
                }
                CYCLING_POWER_FEATURE_CHAR_UUID -> {
                    // Features (already set during service creation)
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
                    Log.d("GATT_SERVER", "Read request for Power Feature. Value: ${characteristic.value?.joinToString("-") { "%02X".format(it) }}")
                }
                EBike_SPEED_CADENCE_CHAR_UUID -> {
                    val value = generateEbikeSpeedCadenceBytes(bikeStatus.speed, bikeStatus.cadence)
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    Log.d("GATT_SERVER", "Read request for eBike Speed/Cadence. Speed: ${bikeStatus.speed}, Cadence: ${bikeStatus.cadence}")
                }
                EBike_BATTERY_CHAR_UUID -> {
                    val value = byteArrayOf(bikeStatus.battery.toByte())
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    Log.d("GATT_SERVER", "Read request for eBike Battery. Value: ${bikeStatus.battery}")
                }
                EBike_ASSIST_MODE_CHAR_UUID -> {
                    val value = byteArrayOf(bikeStatus.assistMode.toByte())
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    Log.d("GATT_SERVER", "Read request for eBike Assist Mode. Value: ${bikeStatus.assistMode}")
                }
                else -> {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    Log.w("GATT_SERVER", "Unknown characteristic read request for ${characteristic.uuid}")
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) { // CCCD
                val value = if (descriptor.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                Log.d("GATT_SERVER", "Read request for CCCD of ${descriptor.characteristic.uuid}")
            } else {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                Log.w("GATT_SERVER", "Unknown descriptor read request for ${descriptor?.uuid}")
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) { // CCCD
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }

                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.d("GATT_SERVER", "Enable notifications for ${descriptor?.characteristic?.uuid}")
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.d("GATT_SERVER", "Disable notifications for ${descriptor?.characteristic?.uuid}")
                }
            } else {
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                Log.w("GATT_SERVER", "Unknown descriptor write request for ${descriptor?.uuid}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        if (!hasPermissions()) {
            Log.e("ADVERTISER", "Missing permissions for advertising")
            return
        }
        if (bluetoothLeAdvertiser == null) {
            Log.e("ADVERTISER", "BluetoothLeAdvertiser not initialized")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(CYCLING_POWER_SERVICE_UUID))
            .addServiceUuid(ParcelUuid(EBike_DATA_SERVICE_UUID)) // Add your custom service UUID
            .build()

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d("ADVERTISER", "Advertising started")
        } catch (e: Exception) {
            Log.e("ADVERTISER", "Failed to start advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d("ADVERTISER", "Advertising successfully started")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("ADVERTISER", "Advertising failed: $errorCode")
            // Handle error codes, e.g., ADVERTISE_FAILED_ALREADY_STARTED, ADVERTISE_FAILED_FEATURE_UNSUPPORTED
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        if (!hasPermissions()) {
            Log.e("ADVERTISER", "Missing permissions for stopping advertising")
            return
        }
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.d("ADVERTISER", "Advertising stopped")
        } catch (e: Exception) {
            Log.e("ADVERTISER", "Error stopping advertising: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGattServer() {
        if (!hasPermissions()) {
            Log.e("GATT_SERVER", "Missing permissions for closing GATT server")
            return
        }
        try {
            bluetoothGattServer?.close()
            bluetoothGattServer = null
            Log.d("GATT_SERVER", "GATT server closed")
        } catch (e: Exception) {
            Log.e("GATT_SERVER", "Error closing GATT server: ${e.message}")
        }
    }

    //region Characteristic Update Functions

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateCyclingPowerMeasurementCharacteristic() {
        val characteristic = cyclingPowerMeasurementCharacteristic ?: return
        val power = bikeStatus.humanPower // Power in Watts

        // Cycling Power Measurement characteristic format (Little Endian)
        // Flags (2 bytes): 0x0001 for "Pedal Power Balance Present", 0x0002 for "Accumulated Torque Present", etc.
        // Power (2 bytes, signed integer, in Watts)
        // Accumulated Crank Revolutions (2 bytes)
        // Last Crank Event Time (2 bytes, 1/1024 s units)

        // For simplicity, let's just send power for now.
        // A real power meter would calculate accumulated crank revolutions and last crank event time.
        // We will use 0 for flags for now, and only send power.
        // Power is a signed 16-bit integer (SINT16)

        val flags: Short = 0x0000 // No flags set for now
        val powerValue: Short = power.toShort() // Convert Int to Short

        val value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(flags)
            .putShort(powerValue)
            .array()

        characteristic.value = value
        bluetoothGattServer?.notifyCharacteristicChanged(null, characteristic, false) // null means all connected devices
        Log.d("GATT_SERVER", "Notified Power: $power Watts")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateEbikeSpeedCadenceCharacteristic() {
        val characteristic = ebikeSpeedCadenceCharacteristic ?: return
        val speed = (bikeStatus.speed * 100).toInt() // Speed in 0.01 km/h increments for int
        val cadence = bikeStatus.cadence // Cadence in RPM

        // Custom format: Speed (2 bytes, little endian), Cadence (2 bytes, little endian)
        val value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(speed.toShort())
            .putShort(cadence.toShort())
            .array()

        characteristic.value = value
        bluetoothGattServer?.notifyCharacteristicChanged(null, characteristic, false)
        Log.d("GATT_SERVER", "Notified eBike Speed/Cadence: Speed=${bikeStatus.speed}, Cadence=${bikeStatus.cadence}")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateEbikeBatteryCharacteristic() {
        val characteristic = ebikeBatteryCharacteristic ?: return
        val battery = bikeStatus.battery.toByte() // Battery in percentage (0-100)

        // Single byte for battery percentage
        characteristic.value = byteArrayOf(battery)
        bluetoothGattServer?.notifyCharacteristicChanged(null, characteristic, false)
        Log.d("GATT_SERVER", "Notified eBike Battery: ${bikeStatus.battery}%")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateEbikeAssistModeCharacteristic() {
        val characteristic = ebikeAssistModeCharacteristic ?: return
        val assistMode = bikeStatus.assistMode.toByte() // Assist mode

        // Single byte for assist mode
        characteristic.value = byteArrayOf(assistMode)
        bluetoothGattServer?.notifyCharacteristicChanged(null, characteristic, false)
        Log.d("GATT_SERVER", "Notified eBike Assist Mode: ${bikeStatus.assistMode}")
    }
    //endregion

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        stopAdvertising()
        closeGattServer()
    }
}

@Composable
fun BoschEBikeMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}