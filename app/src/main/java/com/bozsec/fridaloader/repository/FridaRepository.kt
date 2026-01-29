package com.bozsec.fridaloader.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.bozsec.fridaloader.utils.RootUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.os.Build

class FridaRepository(
    private val rootUtil: RootUtil = RootUtil(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {
    private val gson = Gson()
    
    // Cached releases
    private var cachedReleases: List<GitHubRelease>? = null

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("assets") val assets: List<GitHubAsset>
    )

    data class GitHubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("size") val size: Long
    )
    
    // In Android 'Build.SUPPORTED_ABIS' gives us list like ["arm64-v8a", "armeabi-v7a", "armeabi"]
    // Frida assets look like: frida-server-16.1.11-android-arm64.xz, frida-server-16.1.11-android-x86_64.xz
    
    private fun getDeviceArch(): String {
        val supportedAbis = Build.SUPPORTED_ABIS
        if (supportedAbis.isNotEmpty()) {
            return when {
                supportedAbis[0].contains("arm64") -> "arm64"
                supportedAbis[0].contains("armeabi") -> "arm"
                supportedAbis[0].contains("x86_64") -> "x86_64"
                supportedAbis[0].contains("x86") -> "x86" // Frida usually uses just 'x86' for 32-bit intel
                else -> "arm64" // Fallback
            }
        }
        return "arm64"
    }

    /**
     * Fetch available Frida versions from GitHub (multiple pages to get 16.x versions)
     */
    suspend fun fetchAvailableVersions(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.i("FridaRepository", "Fetching Frida versions from GitHub...")
                val allReleases = mutableListOf<GitHubRelease>()
                
                // Fetch multiple pages to get older versions (16.x, 15.x etc.)
                for (page in 1..5) {
                    try {
                        val request = Request.Builder()
                            .url("https://api.github.com/repos/frida/frida/releases?per_page=50&page=$page")
                            .build()

                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) {
                            android.util.Log.w("FridaRepository", "Page $page failed: ${response.code}")
                            break
                        }

                        val body = response.body?.string() ?: break
                        val releases = gson.fromJson(body, Array<GitHubRelease>::class.java)
                        
                        if (releases.isEmpty()) break
                        allReleases.addAll(releases)
                        android.util.Log.i("FridaRepository", "Fetched page $page: ${releases.size} releases")
                        
                        // Stop if we have 15.0.x versions
                        if (releases.any { it.tagName.startsWith("15.0.") }) break
                    } catch (e: Exception) {
                        android.util.Log.e("FridaRepository", "Error fetching page $page: ${e.message}")
                        break
                    }
                }
                
                // Cache releases
                cachedReleases = allReleases.toList()
                
                android.util.Log.i("FridaRepository", "Total releases fetched: ${allReleases.size}")
                
                // Return version tags
                allReleases.map { it.tagName }.toList()
            } catch (e: Exception) {
                android.util.Log.e("FridaRepository", "Error fetching versions: ${e.message}", e)
                emptyList()
            }
        }
    }

    suspend fun fetchLatestFridaServer(destinationFile: File, selectedVersion: String, onProgress: (Int) -> Unit): File? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.i("FridaRepository", "Downloading Frida version: $selectedVersion")
                
                // Use cached releases or fetch new ones
                var releases = cachedReleases
                
                // If no cache, fetch all versions first
                if (releases == null) {
                    android.util.Log.i("FridaRepository", "No cached releases, fetching all versions...")
                    fetchAvailableVersions() // This will populate cachedReleases
                    releases = cachedReleases
                }
                
                // If still null or version not found, try fetching again with more pages
                if (releases == null || releases.find { it.tagName == selectedVersion } == null) {
                    android.util.Log.i("FridaRepository", "Version not in cache, fetching more releases...")
                    val allReleases = mutableListOf<GitHubRelease>()
                    
                    for (page in 1..10) {
                        val request = Request.Builder()
                            .url("https://api.github.com/repos/frida/frida/releases?per_page=50&page=$page")
                            .build()

                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful) break

                        val body = response.body?.string() ?: break
                        val pageReleases = gson.fromJson(body, Array<GitHubRelease>::class.java)
                        
                        if (pageReleases.isEmpty()) break
                        allReleases.addAll(pageReleases)
                        
                        // Stop if we found the version we're looking for
                        if (pageReleases.any { it.tagName == selectedVersion }) break
                    }
                    
                    releases = allReleases.toList()
                    cachedReleases = releases
                }
                
                // Find exact version match
                val release = releases?.find { it.tagName == selectedVersion }
                    ?: throw IOException("Version $selectedVersion not found in ${releases?.size ?: 0} releases")
                
                android.util.Log.i("FridaRepository", "Found release: ${release.tagName}")

                // 2. Find matching asset
                val arch = getDeviceArch()
                val targetName = "android-$arch.xz"
                
                val asset = release.assets.find { 
                    it.name.startsWith("frida-server-") && 
                    it.name.endsWith(targetName) &&
                    !it.name.contains("gum")
                } ?: throw IOException("No matching asset found for arch: $arch in version ${release.tagName}")

                android.util.Log.i("FridaRepository", "Downloading: ${asset.name}")

                // 3. Download
                val dlRequest = Request.Builder().url(asset.downloadUrl).build()
                val dlResponse = client.newCall(dlRequest).execute()
                val dlBody = dlResponse.body ?: throw IOException("Empty download body")
                
                // Temp file for XZ
                val xzFile = File(destinationFile.absolutePath + ".xz")
                val totalBytes = asset.size
                var downloadedBytes = 0L

                dlBody.byteStream().use { input ->
                    FileOutputStream(xzFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            downloadedBytes += bytes
                            onProgress(((downloadedBytes.toFloat() / totalBytes.toFloat()) * 100).toInt())
                            bytes = input.read(buffer)
                        }
                    }
                }
                
                // 4. Decompress
                BufferedInputStream(xzFile.inputStream()).use { bufferedIn ->
                    XZCompressorInputStream(bufferedIn).use { xzIn ->
                        FileOutputStream(destinationFile).use { out ->
                            val buffer = ByteArray(8 * 1024)
                            var len = xzIn.read(buffer)
                            while (len != -1) {
                                out.write(buffer, 0, len)
                                len = xzIn.read(buffer)
                            }
                        }
                    }
                }
                
                // Clean up xz
                xzFile.delete()
                
                android.util.Log.i("FridaRepository", "Download and extraction completed successfully")
                
                return@withContext destinationFile
            } catch (e: Exception) {
                android.util.Log.e("FridaRepository", "Error downloading Frida: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }
}
