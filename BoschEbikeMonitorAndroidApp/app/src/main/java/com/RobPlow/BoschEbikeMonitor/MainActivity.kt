package com.RobPlow.BoschEbikeMonitor

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.*

class MainActivity : ComponentActivity() {

    // Your bike's specific MAC address - UPDATE THIS!
    private val BOSCH_BIKE_MAC = "00:04:63:82:25:AC"

    // Bosch eBike Service UUIDs
    private val BOSCH_STATUS_SERVICE_UUID = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_STATUS_CHAR_UUID = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4")

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    private val scanResults = mutableStateListOf<ScanResult>()
    private var isScanning by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var bikeData by mutableStateOf("No data")
    private var rawHexData by mutableStateOf("No data")
    private var manualMacAddress by mutableStateOf(BOSCH_BIKE_MAC)
    private val dataLog = mutableStateListOf<String>()

    // Live bike status
    private var currentAssistMode by mutableStateOf("Unknown")
    private var currentBattery1 by mutableStateOf("Unknown")
    private var currentBattery2 by mutableStateOf("Unknown")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeBluetooth()
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

            // Manual MAC Address Input
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
                        placeholder = { Text("XX:XX:XX:XX:XX:XX") },
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

            // Bike Data Display
            if (connectionStatus == "Connected") {
                // Live Status Card - Always at top
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

                        Text(
                            text = "⚡ Assist Mode: $currentAssistMode",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "🔋 Battery 1: $currentBattery1",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "🔋 Battery 2: $currentBattery2",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Raw Data Card
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "📡 Raw Data:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = rawHexData,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = bikeData,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Data Log
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📋 Data Log:",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Button(
                                onClick = { dataLog.clear() },
                                modifier = Modifier.size(80.dp, 32.dp)
                            ) {
                                Text("Clear", fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier.height(200.dp)
                        ) {
                            items(dataLog) { logEntry ->
                                Text(
                                    text = logEntry,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
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

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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
    }

    private fun connectToBoschBike() {
        if (!hasPermissions()) return

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
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
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
        android.os.Handler(mainLooper).postDelayed({
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
                        // Reset live status
                        currentAssistMode = "Unknown"
                        currentBattery1 = "Unknown"
                        currentBattery2 = "Unknown"
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
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

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
                val data = char.value
                val hexString = data.joinToString("-") { "%02X".format(it) }
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

                runOnUiThread {
                    rawHexData = hexString
                    bikeData = parseBoschData(data)

                    // Add to log with timestamp
                    val logEntry = "$timestamp [${data.size}b]: $hexString"
                    dataLog.add(0, logEntry) // Add to beginning

                    // Keep only last 50 entries
                    if (dataLog.size > 50) {
                        dataLog.removeAt(dataLog.size - 1)
                    }

                    Log.d("BLE_DATA", logEntry)
                }
            }
        }
    }

    private fun parseBoschData(data: ByteArray): String {
        return when (data.size) {
            6 -> parseShortPacket(data, "6-byte (likely speed/power)")
            7 -> parseShortPacket(data, "7-byte (likely speed/power+)")
            in 20..35 -> parseLongPacket(data, "Long packet (likely full status)")
            else -> parseGenericPacket(data)
        }
    }

    private fun parseShortPacket(data: ByteArray, type: String): String {
        val bytes = data.map { it.toInt() and 0xFF }
        val hexString = bytes.joinToString("-") { "%02X".format(it) }

        // Check for assist mode in short packets too
        val assistInfo = findBoschAssist(bytes)

        // Try to identify speed (often 2 bytes combined)
        val possibleSpeed1 = if (bytes.size >= 2) (bytes[0] shl 8) + bytes[1] else 0 // Big endian
        val possibleSpeed2 = if (bytes.size >= 2) (bytes[1] shl 8) + bytes[0] else 0 // Little endian
        val possibleSpeed3 = if (bytes.size >= 4) (bytes[2] shl 8) + bytes[3] else 0

        return """
            $type:
            Raw: $hexString
            $assistInfo
            
            Possible speeds (km/h):
            • B0+B1 (BE): ${possibleSpeed1 / 10.0}
            • B0+B1 (LE): ${possibleSpeed2 / 10.0}
            ${if (bytes.size >= 4) "• B2+B3 (BE): ${possibleSpeed3 / 10.0}" else ""}
            
            All bytes: ${bytes.mapIndexed { i, b -> "B$i=0x%02X(%d)".format(b, b) }.joinToString(", ")}
        """.trimIndent()
    }

    private fun parseLongPacket(data: ByteArray, type: String): String {
        val bytes = data.map { it.toInt() and 0xFF }
        val hexString = bytes.joinToString("-") { "%02X".format(it) }

        // Look for Bosch battery patterns
        val batteryInfo = findBoschBattery(bytes)
        val assistInfo = findBoschAssist(bytes)

        return """
            $type (${data.size} bytes):
            Raw: ${hexString.take(50)}${if (hexString.length > 50) "..." else ""}
            
            ${batteryInfo}
            ${assistInfo}
            
            First 8 bytes: ${bytes.take(8).mapIndexed { i, b -> "B$i=0x%02X(%d)".format(b, b) }.joinToString(", ")}
        """.trimIndent()
    }

    private fun findBoschBattery(bytes: List<Int>): String {
        // Look for battery patterns:
        // 30-04-80-88-08-XX (XX = battery value)
        // 30-04-80-BC-08-XX (XX = battery percentage)

        var batteryInfo = ""

        for (i in 0..bytes.size - 6) {
            val pattern = bytes.subList(i, i + 5)
            when {
                pattern == listOf(0x30, 0x04, 0x80, 0x88, 0x08) -> {
                    val batteryValue = bytes[i + 5]
                    currentBattery1 = "$batteryValue"
                    batteryInfo += "🔋 Battery 1: $batteryValue (raw value) at position $i\n"
                }
                pattern == listOf(0x30, 0x04, 0x80, 0xBC, 0x08) -> {
                    val batteryPercent = bytes[i + 5]
                    currentBattery2 = "$batteryPercent%"
                    batteryInfo += "🔋 Battery 2: $batteryPercent% at position $i\n"
                }
            }
        }

        return if (batteryInfo.isNotEmpty()) batteryInfo.trimEnd() else "🔋 Battery: Pattern not found"
    }

    private fun findBoschAssist(bytes: List<Int>): String {
        // Look for assist pattern: 30-04-98-09-08-XX (XX = assist mode)

        for (i in 0..bytes.size - 6) {
            val pattern = bytes.subList(i, i + 5)
            if (pattern == listOf(0x30, 0x04, 0x98, 0x09, 0x08)) {
                val assistMode = bytes[i + 5]
                currentAssistMode = "$assistMode"
                return "⚡ Assist Mode: $assistMode at position $i"
            }
        }
        return "⚡ Assist Mode: Pattern not found"
    }

    private fun parseGenericPacket(data: ByteArray): String {
        val bytes = data.map { it.toInt() and 0xFF }
        return """
            Unknown packet (${data.size} bytes):
            Raw: ${bytes.joinToString("-") { "%02d".format(it) }}
            Hex: ${bytes.joinToString("-") { "0x%02X".format(it) }}
        """.trimIndent()
    }

    private fun disconnect() {
        if (hasPermissions()) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
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

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}

@Composable
fun BoschEBikeMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}