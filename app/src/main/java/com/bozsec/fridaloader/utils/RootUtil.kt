package com.bozsec.fridaloader.utils

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class for handling Root operations and Shell command execution.
 */
class RootUtil {

    /**
     * Checks if the device is rooted by checking common binary locations and attempting to execute 'su'.
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        val buildTags = android.os.Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun checkRootMethod3(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val input = BufferedReader(InputStreamReader(process.inputStream))
            input.readLine() != null
        } catch (t: Throwable) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Executes a command with Root privileges.
     */
    suspend fun execute(command: String): CommandResult {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            var os: DataOutputStream? = null
            var errorStream: BufferedReader? = null
            var inputStream: BufferedReader? = null

            try {
                process = Runtime.getRuntime().exec("su")
                os = DataOutputStream(process.outputStream)

                os.writeBytes(command + "\n")
                os.writeBytes("exit\n")
                os.flush()

                process.waitFor()
                
                // Read stdout
                inputStream = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (inputStream.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                // Read stderr
                errorStream = BufferedReader(InputStreamReader(process.errorStream))
                val error = StringBuilder()
                while (errorStream.readLine().also { line = it } != null) {
                    error.append(line).append("\n")
                }

                CommandResult(
                    success = process.exitValue() == 0,
                    output = output.toString().trim(),
                    error = error.toString().trim()
                )

            } catch (e: Exception) {
                CommandResult(false, "", e.message ?: "Unknown error")
            } finally {
                try {
                    os?.close()
                    inputStream?.close()
                    errorStream?.close()
                    process?.destroy()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String
    )
}
