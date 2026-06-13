package com.linksaver

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linksaver.ui.theme.AppTheme
import org.json.JSONArray
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    
    var notifGranted by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
    var dbPath by remember { mutableStateOf(prefs.getString("db_path", null)) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(storageGranted, dbPath) {
        if (storageGranted && dbPath != null) {
            if (DatabaseManager.initDb(dbPath!!)) {
                refreshTrigger++
            }
        }
    }

    if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        NotificationPermissionScreen { notifGranted = true }
    } else if (!storageGranted) {
        PermissionScreen { storageGranted = Environment.isExternalStorageManager() }
    } else if (dbPath == null) {
        SetupScreen { path -> prefs.edit().putString("db_path", path).apply(); dbPath = path }
    } else {
        AppNavigation(dbPath!!, refreshTrigger)
    }
}

@Composable
fun AppNavigation(dbPath: String, refreshTrigger: Int) {
    var selectedTab by remember { mutableStateOf(0) }
    var scheduledScript by remember { mutableStateOf<String?>(null) }
    var serverStatusScript by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
    val scriptPath = prefs.getString("script_folder_path", "") ?: ""
    val availableScripts = remember(scriptPath) {
        val dir = File(scriptPath)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file -> file.extension == "py" }?.map { it.name }?.sorted() ?: emptyList()
        } else emptyList()
    }

    if (scheduledScript != null) {
        ScriptScheduleScreen(
            scriptName = scheduledScript!!,
            availableScripts = availableScripts,
            onBack = { scheduledScript = null }
        )
    } else if (serverStatusScript != null) {
        ServerStatusScreen(
            scriptName = serverStatusScript!!,
            onBack = { serverStatusScript = null }
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(icon = { Text("🏠") }, label = { Text("Home") }, selected = selectedTab == 0, onClick = { selectedTab = 0 })
                    NavigationBarItem(icon = { Text("📚") }, label = { Text("DB") }, selected = selectedTab == 1, onClick = { selectedTab = 1 })
                    NavigationBarItem(icon = { Text("📝") }, label = { Text("Logs") }, selected = selectedTab == 2, onClick = { selectedTab = 2 })
                    NavigationBarItem(icon = { Text("💻") }, label = { Text("Term") }, selected = selectedTab == 3, onClick = { selectedTab = 3 })
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (selectedTab) {
                    0 -> DashboardScreen(
                        dbPath, refreshTrigger, availableScripts, scriptPath,
                        onScheduleClick = { scheduledScript = it },
                        onServerStatusClick = { serverStatusScript = it }
                    )
                    1 -> LibraryScreen()
                    2 -> LogsScreen()
                    3 -> TerminalScreen()
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(dbPath: String, refreshTrigger: Int, availableScripts: List<String>, scriptPathRaw: String, onScheduleClick: (String) -> Unit, onServerStatusClick: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("linksaver_prefs", Context.MODE_PRIVATE)
    
    var ytCount by remember { mutableStateOf(0) }
    var webCount by remember { mutableStateOf(0) }
    var scriptEnabled by remember { mutableStateOf(prefs.getBoolean("script_enabled", false)) }
    var scriptPath by remember { mutableStateOf(scriptPathRaw) }
    var showPipManager by remember { mutableStateOf(false) }
    
    LaunchedEffect(refreshTrigger) {
        ytCount = DatabaseManager.getYoutubeLinksCount()
        webCount = DatabaseManager.getWebLinksCount()
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val path = getPathFromTreeUri(context, it)
            prefs.edit().putString("script_folder_path", path).apply()
            scriptPath = path
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Database Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("YouTube Links: $ytCount", modifier = Modifier.padding(top = 8.dp))
                Text("Web Links: $webCount")
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top=16.dp)) {
                    Button(onClick = { Toast.makeText(context, DatabaseManager.exportData(dbPath, "csv"), Toast.LENGTH_LONG).show() }) { Text("CSV") }
                    Button(onClick = { Toast.makeText(context, DatabaseManager.exportData(dbPath, "json"), Toast.LENGTH_LONG).show() }) { Text("JSON") }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Automation Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Enable Global Automations:", modifier = Modifier.weight(1f))
                    Switch(
                        checked = scriptEnabled,
                        onCheckedChange = { isChecked ->
                            scriptEnabled = isChecked
                            prefs.edit().putBoolean("script_enabled", isChecked).apply()
                            PythonRunner.syncAlarms(context)
                        }
                    )
                }

                Text("Folder: ${if (scriptPath.isEmpty()) "No Folder Selected" else scriptPath}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { folderLauncher.launch(null) }, modifier = Modifier.padding(top=8.dp)) {
                    Text("Select Script Folder")
                }

                Button(
                    onClick = { showPipManager = true },
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                    enabled = scriptPath.isNotEmpty()
                ) {
                    Text("📦 Manage PIP Packages")
                }

                if (availableScripts.isNotEmpty()) {
                    Text("Available Scripts", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    availableScripts.forEach { scriptName ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(scriptName, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    
                                    var runExpanded by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        Button(onClick = { runExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Run")
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(expanded = runExpanded, onDismissRequest = { runExpanded = false }) {
                                            DropdownMenuItem(text = { Text("Instant Run") }, onClick = {
                                                runExpanded = false
                                                Thread { PythonRunner.executeSingleScript(context, scriptName) }.start()
                                                Toast.makeText(context, "Running $scriptName", Toast.LENGTH_SHORT).show()
                                            })
                                            DropdownMenuItem(text = { Text("Schedule Run") }, onClick = {
                                                runExpanded = false
                                                onScheduleClick(scriptName)
                                            })
                                        }
                                    }

                                    var serverExpanded by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedButton(onClick = { serverExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Server")
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(expanded = serverExpanded, onDismissRequest = { serverExpanded = false }) {
                                            DropdownMenuItem(text = { Text("Instant Server") }, onClick = {
                                                serverExpanded = false
                                                val sIntent = Intent(context, ServerService::class.java)
                                                sIntent.putExtra("SCRIPT_NAME", scriptName)
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    context.startForegroundService(sIntent)
                                                } else {
                                                    context.startService(sIntent)
                                                }
                                                Toast.makeText(context, "Server Started", Toast.LENGTH_SHORT).show()
                                                onServerStatusClick(scriptName)
                                            })
                                            DropdownMenuItem(text = { Text("Schedule Server") }, onClick = {
                                                serverExpanded = false
                                                onScheduleClick(scriptName)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPipManager && scriptPath.isNotEmpty()) {
        PipPackageManagerDialog(scriptPath = scriptPath, onDismiss = { showPipManager = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipPackageManagerDialog(scriptPath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packageName by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    var installedPackages by remember { mutableStateOf(emptyList<PipPackage>()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        installedPackages = PythonRunner.getInstalledPackages(context)
    }

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text("📦 PIP Package Manager") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name (e.g., yt-dlp)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isInstalling
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        if (packageName.isNotBlank()) {
                            isInstalling = true
                            logs.clear()
                            logs.add("[START] Processing $packageName...")
                            Thread {
                                val success = PythonRunner.installPipPackage(context, scriptPath, packageName.trim()) { logLine ->
                                    logs.add(logLine)
                                }
                                isInstalling = false
                                scope.launch(Dispatchers.Main) {
                                    if (success) {
                                        Toast.makeText(context, "Successfully installed $packageName", Toast.LENGTH_SHORT).show()
                                        packageName = ""
                                    } else {
                                        Toast.makeText(context, "Failed to install $packageName", Toast.LENGTH_LONG).show()
                                    }
                                    refreshTrigger++
                                }
                            }.start()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isInstalling && packageName.isNotBlank()
                ) {
                    Text(if (isInstalling) "Installing..." else "Install Package")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (logs.isNotEmpty()) {
                    Text("Installation Logs:", fontWeight = FontWeight.Bold)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color(0xFF181818))
                            .padding(8.dp)
                    ) {
                        items(logs) { log ->
                            Text(log, color = Color.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text("Installed Packages (${installedPackages.size}):", fontWeight = FontWeight.Bold)
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(installedPackages) { pkg ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(pkg.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(
                                    onClick = {
                                        val success = PythonRunner.uninstallPipPackage(context, pkg)
                                        if (success) {
                                            Toast.makeText(context, "Successfully uninstalled ${pkg.displayName}", Toast.LENGTH_SHORT).show()
                                            refreshTrigger++
                                        } else {
                                            Toast.makeText(context, "Failed to uninstall ${pkg.displayName}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = !isInstalling
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Uninstall", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isInstalling
            ) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStatusScreen(scriptName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var tunnelerExpanded by remember { mutableStateOf(false) }
    val tunnelerOptions = listOf("serveo.net", "localhost.run")
    var selectedTunneler by remember { mutableStateOf(tunnelerOptions[0]) }
    
    var localPort by remember { mutableStateOf("5000") }
    var advancedExpanded by remember { mutableStateOf(false) }
    var domain by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server: $scriptName") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("SSH Tunnel Configuration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            Box {
                OutlinedTextField(
                    value = selectedTunneler,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider Server") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { tunnelerExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                    }
                )
                DropdownMenu(expanded = tunnelerExpanded, onDismissRequest = { tunnelerExpanded = false }) {
                    tunnelerOptions.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { selectedTunneler = option; tunnelerExpanded = false })
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = localPort,
                onValueChange = { localPort = it },
                label = { Text("Local Port (Default 5000)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(modifier = Modifier.fillMaxWidth().clickable { advancedExpanded = !advancedExpanded }) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Advanced Options", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Icon(if (advancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            
            if (advancedExpanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("Custom Subdomain (Serveo only)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    val port = localPort.toIntOrNull() ?: 5000
                    TunnelerManager.startTunnel(context, scriptName, port, domain, selectedTunneler)
                    Toast.makeText(context, "Requesting tunnel... Check Terminal tab!", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Tunnel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScheduleScreen(scriptName: String, availableScripts: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    var isCronMode by remember { mutableStateOf(false) }
    var cronExpression by remember { mutableStateOf("* * * * *") }
    var times = remember { mutableStateListOf<String>() }
    var runAsServer by remember { mutableStateOf(false) }
    var triggerScript by remember { mutableStateOf("None") }

    LaunchedEffect(Unit) {
        val schedule = DatabaseManager.getSchedule(scriptName)
        if (schedule != null) {
            runAsServer = schedule.runAsServer
            triggerScript = if (schedule.triggerScript.isBlank()) "None" else schedule.triggerScript
            val rawTimes = schedule.timesJson
            if (rawTimes.startsWith("cron:")) {
                isCronMode = true
                cronExpression = rawTimes.substringAfter("cron:")
            } else {
                isCronMode = false
                val arr = try { JSONArray(rawTimes) } catch (e: Exception) { JSONArray() }
                for (i in 0 until arr.length()) {
                    times.add(arr.getString(i))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedule: $scriptName") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Schedule Mode:", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Row {
                    FilterChip(
                        selected = !isCronMode,
                        onClick = { isCronMode = false },
                        label = { Text("Daily Times") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = isCronMode,
                        onClick = { isCronMode = true },
                        label = { Text("Cron") }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (isCronMode) {
                OutlinedTextField(
                    value = cronExpression,
                    onValueChange = { cronExpression = it },
                    label = { Text("Cron Expression") },
                    placeholder = { Text("* * * * *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = "Format: minute hour dayOfMonth month dayOfWeek\n(e.g., '0 12 * * *' for everyday at 12:00 PM)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text("Execution Times", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                times.forEachIndexed { index, time ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(time, modifier = Modifier.weight(1f), fontSize = 18.sp)
                        IconButton(onClick = { times.removeAt(index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove Time")
                        }
                    }
                }
                Button(onClick = {
                    val cal = Calendar.getInstance()
                    TimePickerDialog(context, { _, h, m ->
                        val formatted = String.format("%02d:%02d", h, m)
                        if (!times.contains(formatted)) times.add(formatted)
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Add Time")
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run as Persistent Server:", modifier = Modifier.weight(1f))
                Switch(checked = runAsServer, onCheckedChange = { runAsServer = it })
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Trigger Next Script (On Success)", fontWeight = FontWeight.Bold)
            var expandedTrigger by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(onClick = { expandedTrigger = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(triggerScript)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expandedTrigger, onDismissRequest = { expandedTrigger = false }) {
                    DropdownMenuItem(text = { Text("None") }, onClick = { triggerScript = "None"; expandedTrigger = false })
                    availableScripts.filter { it != scriptName }.forEach { script ->
                        DropdownMenuItem(text = { Text(script) }, onClick = {
                            triggerScript = script
                            expandedTrigger = false
                        })
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val timesData = if (isCronMode) {
                        "cron:" + cronExpression.trim()
                    } else {
                        val arr = JSONArray()
                        times.forEach { arr.put(it) }
                        arr.toString()
                    }
                    DatabaseManager.saveSchedule(scriptName, timesData, runAsServer, if (triggerScript == "None") "" else triggerScript)
                    PythonRunner.syncAlarms(context)
                    Toast.makeText(context, "Schedule Saved", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            ) {
                Text("Save Configuration")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    var searchQuery by remember { mutableStateOf("") }
    var links by remember { mutableStateOf(listOf<LinkItem>()) }
    var editingLink by remember { mutableStateOf<LinkItem?>(null) }
    LaunchedEffect(searchQuery, editingLink) {
        links = DatabaseManager.searchLinks(searchQuery)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search URLs or Tags") },
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(links, key = { "${it.isYoutube}_${it.id}" }) { link ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editingLink = link },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(if (link.isYoutube) "YouTube" else "Web", style = MaterialTheme.typography.labelSmall)
                        Text(link.tag.ifBlank { "No Tag" }, fontWeight = FontWeight.Bold)
                        Text(link.url, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (editingLink != null) {
        var details by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        LaunchedEffect(editingLink) {
            details = DatabaseManager.getFullLinkDetails(editingLink!!.id, editingLink!!.isYoutube)
        }
        AlertDialog(
            onDismissRequest = { editingLink = null },
            title = { Text("Link Details") },
            text = {
                LazyColumn {
                    items(details.entries.toList()) { entry ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(entry.key.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(entry.value.ifBlank { "N/A" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editingLink = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    editingLink?.let { link ->
                        DatabaseManager.deleteLink(link.id, link.isYoutube)
                        links = links.filterNot { it.id == link.id && it.isYoutube == link.isYoutube }
                    }
                    editingLink = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
fun LogsScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    var logs by remember { mutableStateOf(listOf<AppLog>()) }
    var stats by remember { mutableStateOf(Pair(0, 0)) }
    var refreshTrigger by remember { mutableStateOf(0) }
    LaunchedEffect(selectedTab, refreshTrigger) {
        stats = DatabaseManager.getLogStats()
        logs = DatabaseManager.getLogs(if (selectedTab == 0) "SUCCESS" else "FAILURE")
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Review", fontWeight = FontWeight.Bold)
                    Text("Success: ${stats.first} | Failed: ${stats.second}")
                }
                IconButton(onClick = {
                    DatabaseManager.clearLogs(if (selectedTab == 0) "SUCCESS" else "FAILURE")
                    refreshTrigger++
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                }
            }
        }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Success") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Failures") })
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(logs) { log ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(log.datetime, style = MaterialTheme.typography.labelSmall)
                        Text(log.message)
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalScreen() {
    val sessionLogs = OutputLogger.sessionLogs
    val keys = sessionLogs.keys.toList()
    var selectedTabIndex by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (keys.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No terminal output yet.")
            }
        } else {
            ScrollableTabRow(selectedTabIndex = selectedTabIndex.coerceIn(0, maxOf(0, keys.size - 1))) {
                keys.forEachIndexed { index, title ->
                    Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(title) })
                }
            }
            val selectedKey = keys.getOrNull(selectedTabIndex)
            if (selectedKey != null) {
                val logs = sessionLogs[selectedKey] ?: emptyList()
                LazyColumn(modifier = Modifier.fillMaxSize().background(Color(0xFF181818)).padding(8.dp)) {
                    items(logs) { log ->
                        val color = when {
                            log.contains("[SUCCESS]") || log.contains("http://") || log.contains("https://") -> Color(0xFF55FF55) // Bright Green
                            log.contains("[ERROR]") || log.contains("Exception") || log.contains("Error") -> Color(0xFFFF5555) // Red
                            log.contains("[INFO]") || log.contains("Starting") -> Color(0xFFFFFF55) // Yellow
                            else -> Color(0xFFCCCCCC) // Light Gray
                        }
                        SelectionContainer {
                            Text(log, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationPermissionScreen(onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted -> 
        if(isGranted) onGranted() 
    }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Notification Access", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("We need notifications to reliably run your automated Python scripts in the background and show server statuses.", modifier = Modifier.padding(top=16.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onGranted()
                }
            }, 
            modifier = Modifier.padding(top=32.dp)
        ) { 
            Text("Grant Permission") 
        }
    }
}

@Composable
fun SetupScreen(onPathSelected: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { onPathSelected(getPathFromTreeUri(context, it)) }
    }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Initial Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Button(onClick = { launcher.launch(null) }, modifier = Modifier.padding(top=32.dp)) { Text("Select Folder") }
    }
}

@Composable
fun PermissionScreen(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onPermissionGranted() }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Storage Access", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Button(onClick = {
            launcher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") })
        }, modifier = Modifier.padding(top=32.dp)) { Text("Grant Permission") }
    }
}

fun getPathFromTreeUri(context: Context, uri: Uri): String {
    val path = uri.path ?: return ""
    val match = Regex("/tree/([^:]+):(.*)").find(path)
    if (match == null) return Environment.getExternalStorageDirectory().absolutePath

    val volumeId = match.groupValues[1]
    val relativePath = match.groupValues[2]

    if (volumeId.equals("primary", true)) {
        return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
    }

    val externalDirs = context.getExternalFilesDirs(null)
    for (dir in externalDirs) {
        if (dir != null && !dir.absolutePath.contains("emulated/0")) {
            val rootPath = dir.absolutePath.split("/Android/data")[0]
            if (rootPath.contains(volumeId)) {
                return "$rootPath/$relativePath"
            }
        }
    }
    return "/storage/43BC-100D/$relativePath"
}