package com.amit.itantra

import android.Manifest
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amit.itantra.ui.theme.MyApplicationTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var btManager: BluetoothManager
    private lateinit var ttsManager: TtsManager
    private lateinit var emergencyAudioManager: EmergencyAudioManager
    private val translationManager = TranslationManager()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val connectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        } else {
            true
        }
        if (connectGranted) {
            lifecycleScope.launch {
                btManager.startServer()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val androidBtManager = getSystemService(AndroidBluetoothManager::class.java)
        btManager = BluetoothManager(this, androidBtManager?.adapter)
        ttsManager = TtsManager(this)
        emergencyAudioManager = EmergencyAudioManager(this)

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
                ReceiverScreen(btManager, ttsManager, translationManager, emergencyAudioManager)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
        emergencyAudioManager.stopAlarm()
    }
}

@Composable
fun ReceiverScreen(
    btManager: BluetoothManager, 
    ttsManager: TtsManager, 
    translationManager: TranslationManager,
    emergencyAudioManager: EmergencyAudioManager
) {
    val connectionState by btManager.connectionState.collectAsState()
    val receivedMessage by btManager.receivedMessage.collectAsState()
    
    val languages = listOf(
        Pair("Hindi", "hi"),
        Pair("English", "en")
    )
    var selectedOutputLanguage by remember { mutableStateOf(languages[1]) } // Default English
    var dropdownExpanded by remember { mutableStateOf(false) }

    var displayedReceivedText by remember { mutableStateOf("") }
    var displayedTranslatedText by remember { mutableStateOf("") }
    
    var isEmergencyMode by remember { mutableStateOf(false) }
    
    // Start server automatically & restart when disconnected
    LaunchedEffect(connectionState) {
        if (connectionState == "Disconnected") {
            btManager.startServer()
        }
    }
    
    val coroutineScope = rememberCoroutineScope()

    // Process incoming message
    LaunchedEffect(receivedMessage) {
        receivedMessage?.let { msg ->
            if (msg.isNotEmpty()) {
                try {
                    val json = JSONObject(msg)
                    val type = json.optString("type", "message")
                    
                    if (type == "emergency") {
                        isEmergencyMode = true
                    } else {
                        val sourceLang = json.getString("lang")
                        val text = json.getString("text")
                        
                        displayedReceivedText = text
                        displayedTranslatedText = "Translating..."
                        
                        val targetLang = selectedOutputLanguage.second
                        val translated = translationManager.translate(text, sourceLang, targetLang)
                        
                        displayedTranslatedText = translated
                        
                        ttsManager.setLanguage(targetLang)
                        ttsManager.speak(translated)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback if not JSON (e.g. older tests)
                    displayedReceivedText = msg
                    displayedTranslatedText = ""
                    ttsManager.speak(msg)
                }
            }
        }
    }
    
    // Emergency audio flow
    LaunchedEffect(isEmergencyMode) {
        if (isEmergencyMode) {
            ttsManager.stopSpeech()
            emergencyAudioManager.startAlarm()
            
            // Translate the fixed emergency phrase to output language and speak it
            val targetLang = selectedOutputLanguage.second
            val translated = translationManager.translate("EMERGENCY - NEED HELP", "en", targetLang)
            ttsManager.setLanguage(targetLang)
            ttsManager.speak(translated)
            
            // Auto stop alarm sound after 5 seconds to not be endless, but visual alert stays
            delay(5000)
            emergencyAudioManager.stopAlarm()
        } else {
            emergencyAudioManager.stopAlarm()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                OutlinedButton(onClick = { dropdownExpanded = true }) {
                    Text("Output Language: ${selectedOutputLanguage.first}")
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.first) },
                            onClick = { 
                                selectedOutputLanguage = lang
                                dropdownExpanded = false 
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            if (displayedReceivedText.isNotEmpty()) {
                Text(text = "Received (Original):", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = displayedReceivedText, style = MaterialTheme.typography.bodyLarge)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            if (displayedTranslatedText.isNotEmpty()) {
                Text(text = "Translated (Output):", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = displayedTranslatedText, style = MaterialTheme.typography.headlineSmall)
            }
        }
        
        // Full screen emergency overlay
        if (isEmergencyMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "EMERGENCY ALERT", 
                        style = MaterialTheme.typography.displayMedium, 
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { 
                            isEmergencyMode = false
                            emergencyAudioManager.stopAlarm()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Red
                        )
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}