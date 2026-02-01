package com.bozsec.fridaloader.ssl

import android.content.Context
import android.content.pm.PackageManager
import com.bozsec.fridaloader.utils.RootUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSL Bypass (Artık sadece Analiz) işlemlerini yöneten bileşen.
 * 
 * Kullanıcının isteği üzerine Patch özellikleri kaldırılmıştır.
 * Bu sınıf artık sadece uygulama analizi yaparak kullanıcıyı bilgilendirir.
 * 
 * @property context Android uygulama context'i
 * @property rootUtil Root komutları çalıştırmak için yardımcı sınıf
 */
class SSLBypassManager(
    private val context: Context,
    private val rootUtil: RootUtil
) {
    /**
     * İşlem durumu sealed class.
     */
    sealed class BypassState {
        object Idle : BypassState()
        data class Analyzing(val progress: Int, val message: String) : BypassState()
        data class Error(val error: BypassError) : BypassState()
        // Success artık analiz sonucunu dönmüyor, sadece state geçişi için
    }

    private val appAnalyzer = AppAnalyzer(context)

    // Durum yönetimi
    private val _state = MutableStateFlow<BypassState>(BypassState.Idle)
    val state: StateFlow<BypassState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)

    /**
     * Yüklü bir uygulamayı analiz eder.
     */
    suspend fun analyzeApp(packageName: String): AppAnalysisResult = withContext(Dispatchers.IO) {
        try {
            _state.value = BypassState.Analyzing(0, "Analyzing app info...")
            
            if (isCancelled.get()) throw CancellationException("Cancelled")
            
            _state.value = BypassState.Analyzing(30, "Detecting technology...")
            
            val result = appAnalyzer.analyzeInstalledApp(packageName)
            
            _state.value = BypassState.Analyzing(100, "Analysis complete")
            _state.value = BypassState.Idle
            
            result
        } catch (e: PackageManager.NameNotFoundException) {
            _state.value = BypassState.Error(BypassError.AppNotFound(packageName))
            throw e
        } catch (e: Exception) {
            _state.value = BypassState.Error(BypassError.InvalidApk(e.message ?: "Unknown error"))
            throw e
        }
    }

    /**
     * APK dosyasını analiz eder.
     */
    suspend fun analyzeApk(apkPath: String): AppAnalysisResult = withContext(Dispatchers.IO) {
        try {
            _state.value = BypassState.Analyzing(0, "Reading APK...")
            
            if (isCancelled.get()) throw CancellationException("Cancelled")
            
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                throw IllegalArgumentException("APK file not found: $apkPath")
            }
            
            _state.value = BypassState.Analyzing(30, "Detecting technology...")
            
            val result = appAnalyzer.analyzeApkFile(apkPath)
            
            _state.value = BypassState.Analyzing(100, "Analysis complete")
            _state.value = BypassState.Idle
            
            result
        } catch (e: Exception) {
            _state.value = BypassState.Error(BypassError.InvalidApk(e.message ?: "Unknown error"))
            throw e
        }
    }

    fun reset() {
        isCancelled.set(false)
        _state.value = BypassState.Idle
    }

    private class CancellationException(message: String) : Exception(message)
}
