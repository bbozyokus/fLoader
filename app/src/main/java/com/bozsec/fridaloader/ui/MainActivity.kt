package com.bozsec.fridaloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.view.View
import java.io.File
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.bozsec.fridaloader.databinding.ActivityMainBinding
import com.bozsec.fridaloader.manager.FridaServiceManager
import com.bozsec.fridaloader.repository.FridaRepository
import com.bozsec.fridaloader.utils.NetworkUtil
import com.bozsec.fridaloader.utils.RootUtil
import com.bozsec.fridaloader.utils.ProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private val rootUtil = RootUtil()
    private val repository = FridaRepository(rootUtil)
    private lateinit var manager: FridaServiceManager
    private lateinit var proxyManager: ProxyManager
    
    // Server running state
    private var isServerRunning = false
    
    // Proxy running state
    private var isProxyRunning = false
    
    // SharedPreferences constants
    private val PREFS_NAME = "FridaLoaderPrefs"
    private val KEY_BINARY_NAME = "binary_name"
    private val KEY_USE_RANDOM_NAME = "use_random_name"
    private val KEY_FRIDA_VERSION = "frida_version"
    
    // Frida version options - will be populated from GitHub or local cache
    private var fridaVersions = mutableListOf<String>()
    private var selectedFridaVersion: String = ""
    
    // Cache keys for versions
    private val KEY_CACHED_VERSIONS = "cached_frida_versions"
    private val KEY_VERSIONS_TIMESTAMP = "versions_timestamp"
    
    private fun getCachedVersions(): List<String> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val versionsJson = prefs.getString(KEY_CACHED_VERSIONS, null) ?: return emptyList()
        return versionsJson.split(",").filter { it.isNotEmpty() }
    }
    
    private fun saveCachedVersions(versions: List<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CACHED_VERSIONS, versions.joinToString(","))
            .putLong(KEY_VERSIONS_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }
    
    private fun getSavedBinaryName(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BINARY_NAME, "") ?: ""
    }
    
    private fun saveBinaryName(name: String, isRandom: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_BINARY_NAME, name)
            putBoolean(KEY_USE_RANDOM_NAME, isRandom)
            apply()
        }
    }
    
    private fun getSavedFridaVersion(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FRIDA_VERSION, "Recommended") ?: "Recommended"
    }
    
    private fun saveFridaVersion(version: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FRIDA_VERSION, version).apply()
        selectedFridaVersion = version
    }
    
    private fun loadFridaVersions() {
        scope.launch {
            binding.spinnerFridaVersion.isEnabled = false
            
            // Show loading
            val loadingAdapter = android.widget.ArrayAdapter(
                this@MainActivity, 
                android.R.layout.simple_spinner_item, 
                listOf("Loading versions...")
            )
            loadingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerFridaVersion.adapter = loadingAdapter
            
            try {
                // First check local cache
                val cachedVersions = getCachedVersions()
                
                val versions = if (cachedVersions.isNotEmpty()) {
                    // Use cached versions
                    android.util.Log.i("MainActivity", "Using ${cachedVersions.size} cached versions")
                    cachedVersions
                } else {
                    // Fetch from GitHub and cache
                    android.util.Log.i("MainActivity", "Fetching versions from GitHub...")
                    val fetchedVersions = withContext(Dispatchers.IO) {
                        repository.fetchAvailableVersions()
                    }
                    if (fetchedVersions.isNotEmpty()) {
                        saveCachedVersions(fetchedVersions)
                        android.util.Log.i("MainActivity", "Cached ${fetchedVersions.size} versions")
                    }
                    fetchedVersions
                }
                
                if (versions.isNotEmpty()) {
                    fridaVersions.clear()
                    fridaVersions.addAll(versions)
                    
                    val adapter = android.widget.ArrayAdapter(
                        this@MainActivity, 
                        android.R.layout.simple_spinner_item, 
                        fridaVersions
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerFridaVersion.adapter = adapter
                    
                    // Find recommended version (first 16.x for Android 11+)
                    val savedVersion = getSavedFridaVersion()
                    val recommendedVersion = if (Build.VERSION.SDK_INT >= 30) {
                        fridaVersions.find { it.startsWith("16.") } ?: fridaVersions.first()
                    } else {
                        fridaVersions.first()
                    }
                    
                    // Set selection
                    val versionToSelect = if (savedVersion.isNotEmpty() && fridaVersions.contains(savedVersion)) {
                        savedVersion
                    } else {
                        recommendedVersion
                    }
                    
                    val position = fridaVersions.indexOf(versionToSelect)
                    if (position >= 0) {
                        binding.spinnerFridaVersion.setSelection(position)
                    }
                    selectedFridaVersion = versionToSelect
                    
                    // Show recommendation message
                    if (Build.VERSION.SDK_INT >= 30) {
                        Toast.makeText(this@MainActivity, "Android 11+: Recommended 16.x versions", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Setup listener
                    binding.spinnerFridaVersion.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                            val selected = fridaVersions[pos]
                            saveFridaVersion(selected)
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                    
                    binding.spinnerFridaVersion.isEnabled = true
                } else {
                    // No versions loaded - show error with helpful message
                    val errorAdapter = android.widget.ArrayAdapter(
                        this@MainActivity, 
                        android.R.layout.simple_spinner_item, 
                        listOf("Failed to load - Check network")
                    )
                    errorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerFridaVersion.adapter = errorAdapter
                    binding.spinnerFridaVersion.isEnabled = false
                    
                    Toast.makeText(this@MainActivity, "Failed to load versions. Check internet/proxy settings.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error loading versions: ${e.message}", e)
                
                val errorAdapter = android.widget.ArrayAdapter(
                    this@MainActivity, 
                    android.R.layout.simple_spinner_item, 
                    listOf("Error - Retry later")
                )
                errorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerFridaVersion.adapter = errorAdapter
                binding.spinnerFridaVersion.isEnabled = false
                
                Toast.makeText(this@MainActivity, "Error loading versions: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.spinnerFridaVersion.isEnabled = fridaVersions.isNotEmpty()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force dark theme
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize manager with context
        manager = FridaServiceManager(this, rootUtil)
        proxyManager = ProxyManager(this)
        
        // Show first-time setup dialog if needed
        showFirstTimeSetupIfNeeded()

        initUI()
        performStartupChecks()
    }

    private fun showFirstTimeSetupIfNeeded() {
        // Get old binary name before changing
        val oldBinaryName = getSavedBinaryName()
        
        // Always ask on app start
        android.app.AlertDialog.Builder(this)
            .setTitle("Binary Name Setup")
            .setMessage("Would you like to use a random name for the Frida binary?\n\nThis makes it harder for apps to detect Frida.")
            .setCancelable(false)
            .setPositiveButton("Yes (Random)") { _, _ ->
                // Generate 5-char random alphabetic name
                val chars = "abcdefghijklmnopqrstuvwxyz"
                val randomName = (1..5)
                    .map { chars.random() }
                    .joinToString("")
                
                // Clean up old binary if name changed
                if (oldBinaryName.isNotEmpty() && oldBinaryName != randomName) {
                    cleanupOldBinary(oldBinaryName)
                }
                
                saveBinaryName(randomName, true)
                Toast.makeText(this, "Binary will be named: $randomName", Toast.LENGTH_LONG).show()
                performStartupChecks()
            }
            .setNegativeButton("No (Default)") { _, _ ->
                // Clean up old binary if name changed
                if (oldBinaryName.isNotEmpty() && oldBinaryName != "frida-server") {
                    cleanupOldBinary(oldBinaryName)
                }
                
                saveBinaryName("frida-server", false)
                Toast.makeText(this, "Using default name: frida-server", Toast.LENGTH_SHORT).show()
                performStartupChecks()
            }
            .show()
    }
    
    private fun cleanupOldBinary(oldName: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Delete from app storage
                    val storageFile = java.io.File(filesDir, oldName)
                    if (storageFile.exists()) {
                        storageFile.delete()
                    }
                    
                    // Delete from exec directories using root
                    val execDirs = listOf("/data/local/tmp", "/data/local", "/sdcard")
                    for (dir in execDirs) {
                        rootUtil.execute("su -c 'rm -f $dir/$oldName 2>/dev/null'")
                    }
                    
                    android.util.Log.i("MainActivity", "Cleaned up old binary: $oldName")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error cleaning up old binary: ${e.message}")
                }
            }
        }
    }

    private fun initUI() {
        val arch = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "Unknown"
        val ip = NetworkUtil.getLocalIpAddress()
        
        binding.tvDeviceInfo.text = "Device: ${Build.MODEL} ($arch) | IP: $ip"
        
        // Load Frida versions from GitHub
        loadFridaVersions()

        binding.btnToggleServer.setOnClickListener {
            toggleServer()
        }

        binding.btnDownload.setOnClickListener {
            downloadFrida()
        }
        
        binding.btnCheckStatus.setOnClickListener {
            checkFridaStatus()
        }
        
        binding.btnClearCache.setOnClickListener {
            clearCache()
        }
        
        // Debug button - long press on status to show debug info
        binding.tvStatus.setOnLongClickListener {
            showDebugInfo()
            true
        }

        binding.switchRandomPort.setOnCheckedChangeListener { _, isChecked ->
             if(isChecked) {
                 val randomPort = Random.nextInt(20000, 60000)
                 binding.etPort.setText(randomPort.toString())
                 binding.etPort.isEnabled = false
             } else {
                 binding.etPort.setText("27042")
                 binding.etPort.isEnabled = true
             }
        }
        
        // Show/hide port options based on connection mode
        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            val isRemote = checkedId == binding.radioRemote.id
            binding.layoutPort.visibility = if (isRemote) View.VISIBLE else View.GONE
        }
        
        // Proxy settings
        binding.btnToggleProxy.setOnClickListener {
            toggleProxy()
        }
        
        binding.btnResetNetwork.setOnClickListener {
            resetNetwork()
        }
        
        // Don't load current proxy on startup - let user control it manually
    }
    
    private fun showDebugInfo() {
        scope.launch {
            val binaryName = getSavedBinaryName()
            if (binaryName.isEmpty()) {
                Toast.makeText(this@MainActivity, "No binary name saved", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val debugInfo = withContext(Dispatchers.IO) {
                manager.debugBinaryStatus(binaryName)
            }
            
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Debug Info")
                .setMessage(debugInfo)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Debug Info", debugInfo)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun performStartupChecks() {
        // Set initial state to unknown/checking
        binding.tvStatus.text = "Status: CHECKING..."
        binding.tvStatus.setTextColor(Color.GRAY)
        binding.btnToggleServer.isEnabled = false
        
        scope.launch {
            // Check root access
            val rooted = withContext(Dispatchers.IO) { rootUtil.isDeviceRooted() }
            if (!rooted) {
                Toast.makeText(this@MainActivity, "ROOT REQUIRED BUT NOT FOUND!", Toast.LENGTH_LONG).show()
                binding.tvStatus.text = "Status: NOT ROOTED"
                binding.tvStatus.setTextColor(Color.RED)
                binding.cardControls.alpha = 0.5f
                binding.btnToggleServer.isEnabled = false
                return@launch
            }
            
            // Check frida binary existence
            val binaryName = getSavedBinaryName()
            if (binaryName.isEmpty()) {
                // No binary name saved yet, enable button for first time setup
                binding.tvProgress.text = "Frida: Not Installed (Download Required)"
                binding.tvProgress.setTextColor(Color.parseColor("#FFA500")) // Orange
                binding.btnToggleServer.isEnabled = true
                updateStatus(false)
                return@launch
            }
            
            val binaryExists = withContext(Dispatchers.IO) { manager.checkBinaryExists(binaryName) }
            if (binaryExists) {
                binding.tvProgress.text = "Frida: Installed ✓ ($binaryName)"
                binding.tvProgress.setTextColor(Color.GREEN)
                binding.btnToggleServer.isEnabled = true
            } else {
                binding.tvProgress.text = "Frida: Not Found (Download Required)"
                binding.tvProgress.setTextColor(Color.RED)
                binding.btnToggleServer.isEnabled = true
            }
            
            // Check server status
            checkServerStatus()
            binding.btnToggleServer.isEnabled = true
        }
    }

    private fun checkServerStatus() {
        scope.launch {
            val binaryName = getSavedBinaryName()
            val running = if(binaryName.isNotEmpty()) {
                withContext(Dispatchers.IO) { manager.isServerRunning(binaryName) }
            } else false
            updateStatus(running)
        }
    }
    
    private fun updateStatus(running: Boolean) {
        isServerRunning = running
        if (running) {
             binding.tvStatus.text = "Status: RUNNING"
             binding.tvStatus.setTextColor(Color.GREEN)
             binding.btnToggleServer.text = "Stop Server"
             binding.btnToggleServer.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
         } else {
             binding.tvStatus.text = "Status: STOPPED"
             binding.tvStatus.setTextColor(Color.GRAY)
             binding.btnToggleServer.text = "Start Server"
             // Use Material primary color for default state
             binding.btnToggleServer.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6200EE"))
         }
    }

    private fun checkFridaStatus() {
        scope.launch {
            val binaryName = getSavedBinaryName()
            if (binaryName.isEmpty()) {
                Toast.makeText(this@MainActivity, "Setup not complete", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val binaryExists = withContext(Dispatchers.IO) { manager.checkBinaryExists(binaryName) }
            val serverRunning = withContext(Dispatchers.IO) { manager.isServerRunning(binaryName) }
            
            val statusMsg = buildString {
                appendLine("Root: ✓")
                appendLine("Binary: ${if (binaryExists) "✓ Installed ($binaryName)" else "✗ Not Found"}")
                appendLine("Server: ${if (serverRunning) "✓ Running" else "✗ Stopped"}")
            }
            
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Frida Status")
                .setMessage(statusMsg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun toggleServer() {
        if (isServerRunning) {
            stopFrida()
        } else {
            startFrida()
        }
    }

    private fun startFrida() {
        scope.launch {
            val binaryName = getSavedBinaryName()
            if (binaryName.isEmpty()) {
                Toast.makeText(this@MainActivity, "Complete setup first", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // First, check if binary exists
            val binaryExists = withContext(Dispatchers.IO) { manager.checkBinaryExists(binaryName) }
            
            if (!binaryExists) {
                // Show helpful dialog
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Frida Server Not Found")
                    .setMessage("Frida server binary is not installed yet.\n\nPlease download it first using the 'Download Frida Server' button below.")
                    .setPositiveButton("OK") { _, _ ->
                        // Optionally scroll to download button or highlight it
                        Toast.makeText(this@MainActivity, "👇 Click 'Download Frida Server' button", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Download Now") { _, _ ->
                        // Trigger download
                        downloadFrida()
                    }
                    .show()
                return@launch
            }
            
            // Binary exists, proceed with starting
            val portStr = binding.etPort.text.toString()
            val port = portStr.toIntOrNull() ?: 27042
            val useRandomPort = binding.switchRandomPort.isChecked
            val isRemoteMode = binding.radioRemote.isChecked

            binding.btnToggleServer.isEnabled = false
            binding.tvStatus.text = "Starting..."

            try {
                val command = withContext(Dispatchers.IO) {
                    manager.startServer(binaryName, port, false, useRandomPort, isRemoteMode)
                }
                
                updateStatus(true)
                Toast.makeText(this@MainActivity, "Server Started!", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                updateStatus(false)
            } finally {
                binding.btnToggleServer.isEnabled = true
            }
        }
    }

    private fun stopFrida() {
        binding.btnToggleServer.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    manager.stopServer()
                }
                updateStatus(false)
                Toast.makeText(this@MainActivity, "Server Stopped", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnToggleServer.isEnabled = true
            }
        }
    }
    
    private fun clearCache() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear Cache")
            .setMessage("This will:\n• Delete all Frida binaries\n• Kill all Frida processes\n• Clear app data\n\nContinue?")
            .setPositiveButton("Yes") { _, _ ->
                scope.launch {
                    try {
                        binding.btnClearCache.isEnabled = false
                        binding.tvProgress.visibility = View.VISIBLE
                        binding.tvProgress.text = "Clearing cache..."
                        
                        val success = withContext(Dispatchers.IO) {
                            manager.clearCache()
                        }
                        
                        if (success) {
                            // Clear SharedPreferences
                            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
                            
                            updateStatus(false)
                            binding.tvProgress.text = "Cache cleared successfully"
                            binding.tvProgress.setTextColor(Color.GREEN)
                            Toast.makeText(this@MainActivity, "Cache Cleared!", Toast.LENGTH_SHORT).show()
                            
                            // Re-run startup checks
                            performStartupChecks()
                            
                            // Hide progress after 2 seconds
                            kotlinx.coroutines.delay(2000)
                            binding.tvProgress.visibility = View.GONE
                        } else {
                            binding.tvProgress.text = "Failed to clear cache"
                            binding.tvProgress.setTextColor(Color.RED)
                            Toast.makeText(this@MainActivity, "Clear Failed", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        binding.btnClearCache.isEnabled = true
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun downloadFrida() {
        // Check if binary name is set, if not ask user first
        val currentBinaryName = getSavedBinaryName()
        if (currentBinaryName.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Binary Name Setup")
                .setMessage("Choose a name for the Frida binary before downloading:")
                .setCancelable(false)
                .setPositiveButton("Random (Recommended)") { _, _ ->
                    val chars = "abcdefghijklmnopqrstuvwxyz"
                    val randomName = (1..5).map { chars.random() }.joinToString("")
                    saveBinaryName(randomName, true)
                    Toast.makeText(this, "Binary will be named: $randomName", Toast.LENGTH_SHORT).show()
                    startDownload()
                }
                .setNegativeButton("Default (frida-server)") { _, _ ->
                    saveBinaryName("frida-server", false)
                    startDownload()
                }
                .show()
        } else {
            startDownload()
        }
    }
    
    private fun startDownload() {
        binding.btnDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        
        // Get selected version (exact version tag from GitHub, e.g., "16.5.6")
        val selectedVersion = selectedFridaVersion
        if (selectedVersion.isEmpty()) {
            Toast.makeText(this, "Please wait for versions to load", Toast.LENGTH_SHORT).show()
            binding.btnDownload.isEnabled = true
            binding.progressBar.visibility = View.GONE
            return
        }
        
        binding.tvProgress.text = "Downloading Frida $selectedVersion"
        
        scope.launch {
            try {
                runOnUiThread { binding.tvProgress.text = "Downloading Frida $selectedVersion..." }
                
                // Download to local cache first
                val tempFile = File(cacheDir, "frida_dl_temp")
                if (tempFile.exists()) tempFile.delete()
                
                // Use exact version tag directly (e.g., "16.5.6")
                val file = repository.fetchLatestFridaServer(tempFile, selectedVersion) { progress ->
                    runOnUiThread {
                        binding.progressBar.progress = progress
                        binding.tvProgress.text = "Downloading: $progress%"
                    }
                }
                
                    if (file != null) {
                        runOnUiThread { binding.tvProgress.text = "Installing (Root)..." }
                        
                        // Binary name is already set before download starts
                        val binaryName = getSavedBinaryName()

                        // Install using new manager method
                        val success = withContext(Dispatchers.IO) { 
                            manager.installFridaServer(file, binaryName) 
                        }
                        
                        if (success) {
                            // Show version compatibility warning
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Installation Successful")
                                .setMessage("Frida Server $selectedVersion installed!\n\n⚠️ IMPORTANT: Make sure your PC's frida-tools version matches this version.\n\nRun on PC:\npip install frida-tools==$selectedVersion\n\nOr check version with:\nfrida --version")
                                .setPositiveButton("OK", null)
                                .show()
                            
                            binding.tvProgress.text = "Frida: Installed ✓ ($binaryName)"
                            binding.tvProgress.setTextColor(Color.GREEN)
                    } else {
                        Toast.makeText(this@MainActivity, "Installation Failed (Check Root)", Toast.LENGTH_LONG).show()
                        binding.tvProgress.text = "Install Failed"
                        binding.tvProgress.setTextColor(Color.RED)
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Download Failed", Toast.LENGTH_LONG).show()
                    binding.tvProgress.text = "Download Failed"
                    binding.tvProgress.setTextColor(Color.RED)
                }
            } catch (e: Exception) {
                 Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                 binding.tvProgress.text = "Error"
                 binding.tvProgress.setTextColor(Color.RED)
            } finally {
                binding.btnDownload.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun toggleProxy() {
        if (isProxyRunning) {
            clearProxy()
        } else {
            applyProxy()
        }
    }
    
    private fun updateProxyButton() {
        if (isProxyRunning) {
            binding.btnToggleProxy.text = "Stop Proxy"
            binding.btnToggleProxy.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        } else {
            binding.btnToggleProxy.text = "Start Proxy"
            binding.btnToggleProxy.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6200EE"))
        }
    }
    
    private fun applyProxy() {
        val address = binding.etProxyAddress.text.toString().trim()
        val portStr = binding.etProxyPort.text.toString().trim()
        val bypass = binding.etProxyBypass.text.toString().trim()
        
        if (address.isEmpty()) {
            Toast.makeText(this, "Please enter proxy address", Toast.LENGTH_SHORT).show()
            return
        }
        
        val port = portStr.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }
        
        scope.launch {
            try {
                binding.btnToggleProxy.isEnabled = false
                binding.tvProxyStatus.text = "Applying proxy..."
                binding.tvProxyStatus.setTextColor(Color.parseColor("#FFA500")) // Orange
                
                val success = withContext(Dispatchers.IO) {
                    proxyManager.setWifiProxy(address, port, bypass)
                }
                
                if (success) {
                    isProxyRunning = true
                    updateProxyButton()
                    binding.tvProxyStatus.text = "Active: $address:$port"
                    binding.tvProxyStatus.setTextColor(Color.GREEN)
                    Toast.makeText(this@MainActivity, "Proxy started successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvProxyStatus.text = "Failed to apply proxy"
                    binding.tvProxyStatus.setTextColor(Color.RED)
                    Toast.makeText(this@MainActivity, "Failed to apply proxy (check root)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                binding.tvProxyStatus.text = "Error: ${e.message}"
                binding.tvProxyStatus.setTextColor(Color.RED)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnToggleProxy.isEnabled = true
            }
        }
    }
    
    private fun clearProxy() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Stop Proxy")
            .setMessage("Remove WiFi proxy settings?")
            .setPositiveButton("Yes") { _, _ ->
                scope.launch {
                    try {
                        binding.btnToggleProxy.isEnabled = false
                        binding.tvProxyStatus.text = "Clearing proxy..."
                        binding.tvProxyStatus.setTextColor(Color.parseColor("#FFA500"))
                        
                        val success = withContext(Dispatchers.IO) {
                            proxyManager.clearWifiProxy()
                        }
                        
                        if (success) {
                            isProxyRunning = false
                            updateProxyButton()
                            binding.tvProxyStatus.text = "No proxy configured"
                            binding.tvProxyStatus.setTextColor(Color.GRAY)
                            Toast.makeText(this@MainActivity, "Proxy cleared! WiFi reconnecting...", Toast.LENGTH_LONG).show()
                        } else {
                            binding.tvProxyStatus.text = "Failed to clear proxy"
                            binding.tvProxyStatus.setTextColor(Color.RED)
                            Toast.makeText(this@MainActivity, "Failed to clear proxy", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        binding.btnToggleProxy.isEnabled = true
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }
    
    private fun resetNetwork() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset Network")
            .setMessage("This will:\n• Clear all proxy settings\n• Restart WiFi\n• Fix DNS issues\n\nUse this if you have connection problems.\n\nContinue?")
            .setPositiveButton("Yes") { _, _ ->
                scope.launch {
                    try {
                        binding.btnResetNetwork.isEnabled = false
                        binding.tvProxyStatus.text = "Resetting network..."
                        binding.tvProxyStatus.setTextColor(Color.parseColor("#FF9800"))
                        
                        val success = withContext(Dispatchers.IO) {
                            proxyManager.resetNetwork()
                        }
                        
                        if (success) {
                            binding.tvProxyStatus.text = "Network reset complete"
                            binding.tvProxyStatus.setTextColor(Color.GREEN)
                            Toast.makeText(this@MainActivity, "Network reset! WiFi reconnecting...", Toast.LENGTH_LONG).show()
                            
                            // Hide status after 3 seconds
                            kotlinx.coroutines.delay(3000)
                            binding.tvProxyStatus.text = "No proxy configured"
                            binding.tvProxyStatus.setTextColor(Color.GRAY)
                        } else {
                            binding.tvProxyStatus.text = "Reset failed"
                            binding.tvProxyStatus.setTextColor(Color.RED)
                            Toast.makeText(this@MainActivity, "Reset failed", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        binding.btnResetNetwork.isEnabled = true
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }
}
