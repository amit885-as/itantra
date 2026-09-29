package com.amit.itantra

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amit.itantra.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

class MainActivity : ComponentActivity() {

    private lateinit var btManager: BluetoothManager
    private lateinit var speechManager: SpeechManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // For MVP we assume granted. In prod, handle denials.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val androidBtManager = getSystemService(AndroidBluetoothManager::class.java)
        btManager = BluetoothManager(this, androidBtManager?.adapter)
        speechManager = SpeechManager(this)

        // Request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO)
            )
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SenderScreen(btManager, speechManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.destroy()
    }
}

@SuppressLint("MissingPermission")
@Composable
fun SenderScreen(btManager: BluetoothManager, speechManager: SpeechManager) {
    val connectionState by btManager.connectionState.collectAsState()
    var devices by remember { mutableStateOf<List<PairedDevice>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    
    val isListening by speechManager.isListening.collectAsState()
    val speechText by speechManager.speechText.collectAsState()
    
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val languages = listOf(
        Triple("Hindi", "hi", "hi-IN"),
        Triple("English", "en", "en-US")
    )
    var selectedLanguage by remember { mutableStateOf(languages[0]) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    val emergencyInteractionSource = remember { MutableInteractionSource() }
    val isEmergencyPressed by emergencyInteractionSource.collectIsPressedAsState()
    
    // Emergency long press handler
    LaunchedEffect(isEmergencyPressed) {
        if (isEmergencyPressed) {
            // Wait 1 full second of holding
            kotlinx.coroutines.delay(1000)
            if (connectionState == "Connected") {
                try {
                    val payload = org.json.JSONObject().apply {
                        put("type", "emergency")
                    }.toString()
                    btManager.sendMessage(payload)
                } catch(e: Exception) { e.printStackTrace() }
            }
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            speechManager.startListening(selectedLanguage.third)
        } else {
            if (isListening) {
                speechManager.stopListening()
            }
        }
    }
    
    // Send when speech finishes
    LaunchedEffect(speechText, isListening) {
        if (!isListening && speechText.isNotEmpty() && !speechText.startsWith("Error:") && speechText != "Listening...") {
            if (connectionState == "Connected") {
                try {
                    val payload = org.json.JSONObject().apply {
                        put("type", "message")
                        put("lang", selectedLanguage.second)
                        put("text", speechText)
                    }.toString()
                    btManager.sendMessage(payload)
                } catch(e: Exception) { e.printStackTrace() }
            }
        }
    }

    val isConnected = connectionState == "Connected"

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isConnected) {
                if (btManager.lastConnectedDevice != null) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                btManager.reconnect()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Reconnect")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedButton(onClick = {
                    devices = btManager.getPairedDevices()
                }) {
                    Text("Select Paired Device")
                }

                if (devices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                        items(devices) { pairedDevice ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        coroutineScope.launch {
                                            btManager.connectToDevice(pairedDevice.device)
                                        }
                                    }
                            ) {
                                Text(
                                    text = "${pairedDevice.name} - ${pairedDevice.address}",
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Box {
                OutlinedButton(
                    onClick = { dropdownExpanded = true },
                    enabled = isConnected
                ) {
                    Text("Spoken Language: ${selectedLanguage.first}")
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.first) },
                            onClick = { 
                                selectedLanguage = lang
                                dropdownExpanded = false 
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { /* Handled by interactionSource */ },
                interactionSource = interactionSource,
                enabled = isConnected
            ) {
                Text(if (isPressed) "Release to Send" else "Hold to Speak")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // The EMERGENCY button
            Button(
                onClick = { /* Handled by interactionSource */ },
                interactionSource = emergencyInteractionSource,
                enabled = isConnected,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color.Red else Color.Gray
                ),
                modifier = Modifier.size(140.dp)
            ) {
                Text(
                    if (isEmergencyPressed) "Holding..." else "EMERGENCY\n(Hold 1s)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            
            if (speechText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Recognized (${selectedLanguage.first}):")
                Text(text = speechText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}