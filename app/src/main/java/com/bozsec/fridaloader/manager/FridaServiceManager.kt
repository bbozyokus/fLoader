package com.bozsec.fridaloader.manager

import android.content.Context
import android.os.Build
import com.bozsec.fridaloader.utils.RootUtil
import java.io.File
import kotlin.random.Random

class FridaServiceManager(private val context: Context, private val rootUtil: RootUtil = RootUtil()) {
    
    // Use app's private files directory for storage
    private val filesDir = context.filesDir.absolutePath
    
    // Alternative execution directories (will try in order)
    private val execDirs = listOf(
        "/data/local/tmp",
        "/data/local",
        "/sdcard",
        "/data/media/0",
        "/mnt/sdcard"
    )
    
    private var currentBinaryName: String? = null
    private var currentExecDir: String? = null
    
    /**
     * Get the binary file path for execution
     */
    private fun getExecPath(binaryName: String, execDir: String): String {
        return "$execDir/$binaryName"
    }
    
    /**
     * Get the binary file path for storage
     */
    private fun getStoragePath(binaryName: String): String {
        return "$filesDir/$binaryName"
    }
    
    /**
     * Find a working execution directory
     */
    private suspend fun findWorkingExecDir(binaryName: String): String? {
        val storagePath = getStoragePath(binaryName)
        
        for (dir in execDirs) {
            try {
                android.util.Log.d("FridaServiceManager", "Testing directory: $dir")
                
                // Check if directory exists, create if needed
                val mkdirResult = rootUtil.execute("su -c 'mkdir -p $dir 2>/dev/null; test -d $dir && echo OK'")
                if (!mkdirResult.output.contains("OK")) {
                    android.util.Log.d("FridaServiceManager", "Directory not accessible: $dir")
                    continue
                }
                
                val execPath = getExecPath(binaryName, dir)
                
                // Remove old file if exists
                rootUtil.execute("su -c 'rm -f $execPath' 2>/dev/null")
                
                // Copy with root
                val copyResult = rootUtil.execute("su -c 'cp $storagePath $execPath'")
                if (!copyResult.success) {
                    android.util.Log.d("FridaServiceManager", "Failed to copy to $dir: ${copyResult.error}")
                    continue
                }
                
                // Set full permissions with root
                rootUtil.execute("su -c 'chmod 777 $execPath'")
                rootUtil.execute("su -c 'chown root:root $execPath' 2>/dev/null")
                rootUtil.execute("su -c 'chcon u:object_r:system_file:s0 $execPath' 2>/dev/null")
                
                // Verify file exists
                val verifyResult = rootUtil.execute("su -c 'test -f $execPath && echo EXISTS'")
                if (!verifyResult.output.contains("EXISTS")) {
                    android.util.Log.d("FridaServiceManager", "File not found after copy in $dir")
                    continue
                }
                
                // Test execution with root
                val testResult = rootUtil.execute("su -c '$execPath --version'")
                if (testResult.success && testResult.output.isNotBlank()) {
                    android.util.Log.i("FridaServiceManager", "✓ Found working directory: $dir (version: ${testResult.output.trim()})")
                    return dir
                } else {
                    // Clean up failed attempt
                    rootUtil.execute("su -c 'rm -f $execPath' 2>/dev/null")
                    android.util.Log.d("FridaServiceManager", "Execution test failed in $dir - Output: ${testResult.output}, Error: ${testResult.error}")
                }
            } catch (e: Exception) {
                android.util.Log.d("FridaServiceManager", "Error testing $dir: ${e.message}")
                continue
            }
        }
        
        return null
    }
    
    /**
     * Check if binary exists in files directory
     */
    suspend fun checkBinaryExists(binaryName: String): Boolean {
        val binaryFile = File(filesDir, binaryName)
        val exists = binaryFile.exists()
        
        if (!exists) {
            // Debug: List files in directory
            val listResult = rootUtil.execute("su -c 'ls -la $filesDir'")
            android.util.Log.e("FridaServiceManager", "Binary not found: ${binaryFile.absolutePath}")
            android.util.Log.e("FridaServiceManager", "Files in dir: ${listResult.output}")
        }
        
        return exists
    }
    
    /**
     * Copy binary from storage to execution directory
     */
    private suspend fun copyToExecDir(binaryName: String): Boolean {
        val storagePath = getStoragePath(binaryName)
        val execPath = currentExecDir?.let { getExecPath(binaryName, it) } ?: return false
        
        // Remove old file if exists
        rootUtil.execute("su -c 'rm -f $execPath' 2>/dev/null")
        
        // Copy to exec directory with root
        val copyResult = rootUtil.execute("su -c 'cp $storagePath $execPath'")
        if (!copyResult.success) {
            android.util.Log.e("FridaServiceManager", "Failed to copy to exec dir: ${copyResult.error}")
            return false
        }
        
        // Set full permissions with root
        rootUtil.execute("su -c 'chmod 777 $execPath'")
        rootUtil.execute("su -c 'chown root:root $execPath' 2>/dev/null")
        rootUtil.execute("su -c 'chcon u:object_r:system_file:s0 $execPath' 2>/dev/null")
        
        // Verify
        val verifyResult = rootUtil.execute("su -c 'test -f $execPath && echo OK'")
        return verifyResult.output.contains("OK")
    }
    
    /**
     * Starts the Frida Server.
     * @param binaryName The name of the binary file
     * @param port The port to listen on
     * @param useRandomName If true, copies the binary to a random name before execution
     * @param useRandomPort If true, generates a random port (10000-65000)
     * @param isRemoteMode If true, uses network mode (-l), otherwise uses USB mode (-D)
     * @return The command that needs to be run on the PC
     */
    suspend fun startServer(binaryName: String, port: Int, useRandomName: Boolean, useRandomPort: Boolean = false, isRemoteMode: Boolean = false): String {
        // Stop any existing instances first
        stopServer()

        var actualBinaryName = binaryName
        var actualPort = port

        // Generate random port if requested
        if (useRandomPort) {
            actualPort = Random.nextInt(10000, 65001)
        }

        // Verify binary exists in storage
        val storageFile = File(filesDir, binaryName)
        if (!storageFile.exists()) {
            throw Exception("Original binary not found: $binaryName")
        }

        // Find a working execution directory
        android.util.Log.i("FridaServiceManager", "Finding working execution directory...")
        val workingDir = findWorkingExecDir(binaryName)
        if (workingDir == null) {
            throw Exception("Could not find a working execution directory.\nTried: ${execDirs.joinToString(", ")}\nCheck logcat for details.")
        }
        
        currentExecDir = workingDir
        currentBinaryName = actualBinaryName
        
        val binaryPath = getExecPath(actualBinaryName, workingDir)
        
        android.util.Log.i("FridaServiceManager", "Using directory: $workingDir")
        android.util.Log.i("FridaServiceManager", "Binary path: $binaryPath")

        // Start command - run as root daemon
        val startCmd = if (isRemoteMode) {
            // Remote mode with network listening
            "su -c 'cd $workingDir && nohup ./$actualBinaryName -D -l 0.0.0.0:$actualPort >/dev/null 2>&1 &'"
        } else {
            // USB mode only - add verbose logging for debugging
            "su -c 'cd $workingDir && nohup ./$actualBinaryName -D >/dev/null 2>&1 &'"
        }

        android.util.Log.i("FridaServiceManager", "Executing: $startCmd")
        
        // Disable USAP (Unspecialized App Process) to fix spawn issues on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.util.Log.i("FridaServiceManager", "Disabling USAP for better spawn compatibility...")
            rootUtil.execute("su -c 'setprop persist.device_config.runtime_native.usap_pool_enabled false'")
            rootUtil.execute("su -c 'setprop persist.device_config.runtime_native.usap_pool_size_max 0'")
        }
        
        val result = rootUtil.execute(startCmd)
        android.util.Log.i("FridaServiceManager", "Start result - Success: ${result.success}, Output: ${result.output}, Error: ${result.error}")
        
        // Wait longer for server to start (especially on slower devices)
        kotlinx.coroutines.delay(3000)

        // Check if started successfully
        if (!isServerRunning(actualBinaryName)) {
            // Try to get error info
            val psResult = rootUtil.execute("su -c 'ps -A | grep -E \"frida|$actualBinaryName\"'")
            val lsResult = rootUtil.execute("su -c 'ls -la $binaryPath'")
            throw Exception("Failed to start server - process not found\n\nWorking dir: $workingDir\nBinary: $actualBinaryName\n\nFile info:\n${lsResult.output}\n\nProcesses:\n${psResult.output}")
        }

        android.util.Log.i("FridaServiceManager", "✓ Server started successfully!")

        // Return PC command based on mode
        return if (isRemoteMode) {
            "adb forward tcp:$actualPort tcp:$actualPort && frida -H 127.0.0.1:$actualPort"
        } else {
            "frida -U"
        }
    }

    /**
     * Stops the Frida Server.
     */
    suspend fun stopServer() {
        // Kill all frida processes
        rootUtil.execute("su -c 'killall -9 frida-server' 2>/dev/null")
        
        // Also try killing by current binary name if set
        currentBinaryName?.let { name ->
            rootUtil.execute("su -c 'killall -9 $name' 2>/dev/null")
            
            // Clean up from all exec directories
            for (dir in execDirs) {
                val execPath = getExecPath(name, dir)
                rootUtil.execute("su -c 'rm -f $execPath' 2>/dev/null")
            }
        }
        
        // Kill any process using port 27042 (default Frida port)
        val netstatResult = rootUtil.execute("su -c 'netstat -tulpn | grep 27042'")
        if (netstatResult.success && netstatResult.output.isNotBlank()) {
            // Extract PID from netstat output
            val pidRegex = """(\d+)/""".toRegex()
            val match = pidRegex.find(netstatResult.output)
            match?.groupValues?.get(1)?.let { pid ->
                android.util.Log.i("FridaServiceManager", "Killing process on port 27042: PID $pid")
                rootUtil.execute("su -c 'kill -9 $pid'")
            }
        }
        
        currentBinaryName = null
        currentExecDir = null
    }

    /**
     * Check if server is running for a specific binary
     */
    suspend fun isServerRunning(binaryName: String = currentBinaryName ?: ""): Boolean {
        if (binaryName.isEmpty()) return false
        
        // Try multiple methods to check if process is running
        
        // Method 1: Check by process name
        val psResult = rootUtil.execute("su -c 'ps -A | grep $binaryName'")
        if (psResult.success && psResult.output.contains(binaryName) && !psResult.output.contains("grep")) {
            return true
        }
        
        // Method 2: Check by full path in all exec dirs
        for (dir in execDirs) {
            val fullPath = getExecPath(binaryName, dir)
            val psPathResult = rootUtil.execute("su -c 'ps -A | grep \"$fullPath\"'")
            if (psPathResult.success && psPathResult.output.contains(fullPath) && !psPathResult.output.contains("grep")) {
                return true
            }
        }
        
        // Method 3: Check pidof
        val pidofResult = rootUtil.execute("su -c 'pidof $binaryName'")
        if (pidofResult.success && pidofResult.output.isNotBlank()) {
            return true
        }
        
        return false
    }

    /**
     * Install Frida server binary to app's files directory
     * Now renames the binary to the saved name immediately
     */
    suspend fun installFridaServer(sourceFile: File, targetName: String): Boolean {
        if (!sourceFile.exists()) return false

        try {
            val targetFile = File(filesDir, targetName)
            val targetPath = targetFile.absolutePath
            
            // Remove old file if exists with root
            rootUtil.execute("su -c 'rm -f $targetPath' 2>/dev/null")
            
            // Copy file
            sourceFile.copyTo(targetFile, overwrite = true)
            
            // Make executable with root - use 777 for maximum compatibility
            val chmodResult = rootUtil.execute("su -c 'chmod 777 $targetPath'")
            if (!chmodResult.success) {
                android.util.Log.e("FridaServiceManager", "chmod failed: ${chmodResult.error}")
                return false
            }
            
            // Change ownership to root
            rootUtil.execute("su -c 'chown root:root $targetPath' 2>/dev/null")
            
            // Fix SELinux context - try multiple contexts
            rootUtil.execute("su -c 'chcon u:object_r:system_file:s0 $targetPath' 2>/dev/null")
            rootUtil.execute("su -c 'chcon u:object_r:system_data_file:s0 $targetPath' 2>/dev/null")
            
            // Try to set SELinux to permissive for this file (some devices need this)
            rootUtil.execute("su -c 'setenforce 0' 2>/dev/null")
            
            // Verify file exists
            val verifyResult = rootUtil.execute("su -c 'test -f $targetPath && echo OK'")
            if (!verifyResult.output.contains("OK")) {
                android.util.Log.e("FridaServiceManager", "File verification failed")
                return false
            }
            
            // Test execution
            val testResult = rootUtil.execute("su -c '$targetPath --version'")
            if (!testResult.success || testResult.output.isBlank()) {
                android.util.Log.e("FridaServiceManager", "Execution test failed: ${testResult.error}")
                return false
            }
            
            android.util.Log.i("FridaServiceManager", "Binary installed successfully: $targetName (${testResult.output.trim()})")
            
            // Cleanup source
            sourceFile.delete()
            
            return true
        } catch (e: Exception) {
            android.util.Log.e("FridaServiceManager", "Installation error: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Clear all Frida binaries and kill all processes
     */
    suspend fun clearCache(): Boolean {
        return try {
            // Kill all frida processes
            rootUtil.execute("su -c 'killall -9 frida-server' 2>/dev/null")
            rootUtil.execute("su -c 'killall -9 frida' 2>/dev/null")
            
            // Kill by current binary name if set
            currentBinaryName?.let { name ->
                rootUtil.execute("su -c 'killall -9 $name' 2>/dev/null")
            }
            
            // Remove all binaries from storage directory
            val removeStorage = rootUtil.execute("su -c 'rm -rf $filesDir/*' 2>/dev/null")
            
            // Remove all binaries from exec directories
            for (dir in execDirs) {
                rootUtil.execute("su -c 'rm -f $dir/frida-server $dir/frida' 2>/dev/null")
                // Also remove any 5-letter named files (random names)
                rootUtil.execute("su -c 'find $dir -maxdepth 1 -type f -name \"?????\" -delete' 2>/dev/null")
            }
            
            currentBinaryName = null
            currentExecDir = null
            
            android.util.Log.i("FridaServiceManager", "Cache cleared successfully")
            true
        } catch (e: Exception) {
            android.util.Log.e("FridaServiceManager", "Error clearing cache: ${e.message}")
            false
        }
    }
    
    /**
     * Debug function to check binary status
     */
    suspend fun debugBinaryStatus(binaryName: String): String {
        val storagePath = getStoragePath(binaryName)
        val result = StringBuilder()
        
        result.appendLine("=== Binary Debug Info ===")
        result.appendLine("Binary Name: $binaryName")
        result.appendLine("Storage Path: $storagePath")
        result.appendLine("Files Dir: $filesDir")
        result.appendLine("Current Exec Dir: $currentExecDir")
        
        // Check if file exists in storage
        val fileExists = File(storagePath).exists()
        result.appendLine("File Exists in Storage (Java): $fileExists")
        
        // List files in storage directory
        val lsResult = rootUtil.execute("su -c 'ls -la $filesDir'")
        result.appendLine("\nFiles in storage directory:")
        result.appendLine(lsResult.output)
        
        // Check all exec directories
        result.appendLine("\n=== Checking Exec Directories ===")
        for (dir in execDirs) {
            result.appendLine("\nDirectory: $dir")
            val checkResult = rootUtil.execute("su -c 'test -d $dir && test -w $dir && echo OK || echo FAIL'")
            result.appendLine("Writable: ${checkResult.output.trim()}")
            
            val execPath = getExecPath(binaryName, dir)
            val existsResult = rootUtil.execute("su -c 'test -f $execPath && echo EXISTS || echo NOT_FOUND'")
            result.appendLine("Binary exists: ${existsResult.output.trim()}")
        }
        
        // Check file permissions in storage
        val statResult = rootUtil.execute("su -c 'stat $storagePath'")
        result.appendLine("\nStorage file stat:")
        result.appendLine(statResult.output)
        
        // Try to execute from storage
        val testResult = rootUtil.execute("su -c '$storagePath --version'")
        result.appendLine("\nTest execution from storage:")
        result.appendLine("Success: ${testResult.success}")
        result.appendLine("Output: ${testResult.output}")
        result.appendLine("Error: ${testResult.error}")
        
        return result.toString()
    }
}
