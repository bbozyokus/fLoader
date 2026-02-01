package com.bozsec.fridaloader.ssl

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Uygulama analiz bileşeni.
 * 
 * Yüklü uygulamaları veya APK dosyalarını analiz ederek:
 * - Uygulama teknolojisini tespit eder (Flutter, React Native, Xamarin, Native, Cordova)
 * - SSL pinning yöntemini tespit eder
 * - Desteklenen CPU mimarilerini tespit eder
 * - Split APK durumunu kontrol eder
 * 
 * Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 3.1, 3.2, 3.3
 */
class AppAnalyzer(private val context: Context) {

    /**
     * Teknoloji tespiti için kontrol edilecek dosya imzaları.
     * Her teknoloji için karakteristik dosya pattern'ları tanımlanmıştır.
     */
    private val technologySignatures = mapOf(
        AppTechnology.FLUTTER to listOf(
            "lib/*/libflutter.so",
            "lib/*/libapp.so"
        ),
        AppTechnology.REACT_NATIVE to listOf(
            "lib/*/libreactnativejni.so",
            "assets/index.android.bundle"
        ),
        AppTechnology.XAMARIN to listOf(
            "lib/*/libmonodroid.so",
            "lib/*/libmonosgen-2.0.so"
        ),
        AppTechnology.CORDOVA to listOf(
            "assets/www/cordova.js",
            "assets/www/index.html"
        )
    )

    /**
     * Native uygulama için SSL pinning tespiti dosya pattern'ları.
     */
    private val sslPinningSignatures = mapOf(
        SSLPinningMethod.OKHTTP_CERTIFICATE_PINNER to listOf(
            "okhttp3/CertificatePinner",
            "com/squareup/okhttp/CertificatePinner"
        ),
        SSLPinningMethod.NETWORK_SECURITY_CONFIG to listOf(
            "res/xml/network_security_config.xml"
        )
    )

    /**
     * Desteklenen CPU mimarileri.
     */
    private val supportedArchitectures = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86_64",
        "x86"
    )

    /**
     * Yüklü bir uygulamayı analiz eder.
     * 
     * @param packageName Analiz edilecek uygulamanın paket adı
     * @return AppAnalysisResult analiz sonucu
     * @throws PackageManager.NameNotFoundException Uygulama bulunamazsa
     * 
     * Requirements: 1.1, 1.3, 2.1, 2.2, 3.1, 3.2, 3.3
     */
    suspend fun analyzeInstalledApp(packageName: String): AppAnalysisResult = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        
        // Paket bilgilerini al
        val packageInfo: PackageInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw e
        }
        
        val applicationInfo: ApplicationInfo = packageInfo.applicationInfo
            ?: throw PackageManager.NameNotFoundException("ApplicationInfo not found for $packageName")
        
        // APK yollarını al
        val apkPaths = getApkPaths(applicationInfo)
        val isSplitApk = apkPaths.size > 1
        
        // APK içeriğini analiz et
        val apkContents = getApkContents(apkPaths)
        
        // Teknoloji tespiti
        val technology = detectTechnology(apkContents)
        
        // SSL pinning tespiti
        val sslPinningMethod = detectSSLPinning(technology, apkContents)
        
        // Mimari tespiti
        val architectures = detectArchitectures(apkContents)
        
        // Flutter versiyonu tespiti (varsa)
        val flutterVersion = if (technology == AppTechnology.FLUTTER) {
            detectFlutterVersion(apkContents)
        } else null
        
        // Bypass uygulanabilirliği
        val canBypass = technology == AppTechnology.FLUTTER && architectures.isNotEmpty()
        val bypassMethod = if (canBypass) {
            "libflutter.so BoringSSL patch"
        } else null
        
        AppAnalysisResult(
            packageName = packageName,
            appName = applicationInfo.loadLabel(packageManager).toString(),
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            technology = technology,
            sslPinningMethod = sslPinningMethod,
            isSplitApk = isSplitApk,
            apkPaths = apkPaths,
            architectures = architectures,
            flutterVersion = flutterVersion,
            canBypass = canBypass,
            bypassMethod = bypassMethod
        )
    }

    /**
     * APK dosyasını analiz eder.
     * 
     * @param apkPath Analiz edilecek APK dosyasının yolu
     * @return AppAnalysisResult analiz sonucu
     * @throws IllegalArgumentException APK dosyası geçersizse
     * 
     * Requirements: 1.2, 1.3, 2.1, 2.2, 3.1, 3.2, 3.3
     */
    suspend fun analyzeApkFile(apkPath: String): AppAnalysisResult = withContext(Dispatchers.IO) {
        val apkFile = File(apkPath)
        
        if (!apkFile.exists()) {
            throw IllegalArgumentException("APK dosyası bulunamadı: $apkPath")
        }
        
        if (!apkFile.canRead()) {
            throw IllegalArgumentException("APK dosyası okunamıyor: $apkPath")
        }
        
        // APK'dan paket bilgilerini al
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageArchiveInfo(apkPath, 0)
            ?: throw IllegalArgumentException("Geçersiz APK dosyası: $apkPath")
        
        // ApplicationInfo'yu APK yolu ile güncelle
        packageInfo.applicationInfo?.apply {
            sourceDir = apkPath
            publicSourceDir = apkPath
        }
        
        val apkPaths = listOf(apkPath)
        
        // APK içeriğini analiz et
        val apkContents = getApkContents(apkPaths)
        
        // Teknoloji tespiti
        val technology = detectTechnology(apkContents)
        
        // SSL pinning tespiti
        val sslPinningMethod = detectSSLPinning(technology, apkContents)
        
        // Mimari tespiti
        val architectures = detectArchitectures(apkContents)
        
        // Flutter versiyonu tespiti (varsa)
        val flutterVersion = if (technology == AppTechnology.FLUTTER) {
            detectFlutterVersion(apkContents)
        } else null
        
        // Bypass uygulanabilirliği
        val canBypass = technology == AppTechnology.FLUTTER && architectures.isNotEmpty()
        val bypassMethod = if (canBypass) {
            "libflutter.so BoringSSL patch"
        } else null
        
        AppAnalysisResult(
            packageName = packageInfo.packageName ?: "unknown",
            appName = packageInfo.applicationInfo?.loadLabel(packageManager)?.toString() 
                ?: apkFile.nameWithoutExtension,
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            technology = technology,
            sslPinningMethod = sslPinningMethod,
            isSplitApk = false, // Tek APK dosyası
            apkPaths = apkPaths,
            architectures = architectures,
            flutterVersion = flutterVersion,
            canBypass = canBypass,
            bypassMethod = bypassMethod
        )
    }

    /**
     * APK içeriğine göre uygulama teknolojisini tespit eder.
     * 
     * Tespit sırası:
     * 1. Flutter (libflutter.so + libapp.so)
     * 2. React Native (libreactnativejni.so veya index.android.bundle)
     * 3. Xamarin (libmonodroid.so veya libmonosgen-2.0.so)
     * 4. Cordova (cordova.js + index.html)
     * 5. Native Java/Kotlin (varsayılan)
     * 
     * @param apkContents APK içindeki dosya yolları listesi
     * @return Tespit edilen AppTechnology
     * 
     * Requirements: 2.1, 2.2
     */
    fun detectTechnology(apkContents: List<String>): AppTechnology {
        // Her teknoloji için imzaları kontrol et
        for ((technology, signatures) in technologySignatures) {
            if (matchesSignatures(apkContents, signatures)) {
                return technology
            }
        }
        
        // Hiçbir teknoloji tespit edilemezse Native olarak işaretle
        return AppTechnology.NATIVE_JAVA_KOTLIN
    }

    /**
     * Uygulama teknolojisine göre SSL pinning yöntemini tespit eder.
     * 
     * - Flutter: Her zaman FLUTTER_BORINGSSL
     * - Native: OkHttp CertificatePinner, TrustManager veya Network Security Config kontrolü
     * - Diğer: UNKNOWN
     * 
     * @param technology Tespit edilen uygulama teknolojisi
     * @param apkContents APK içindeki dosya yolları listesi
     * @return Tespit edilen SSLPinningMethod
     * 
     * Requirements: 3.1, 3.2, 3.3
     */
    fun detectSSLPinning(technology: AppTechnology, apkContents: List<String>): SSLPinningMethod {
        return when (technology) {
            // Flutter her zaman BoringSSL kullanır
            AppTechnology.FLUTTER -> SSLPinningMethod.FLUTTER_BORINGSSL
            
            // Native uygulamalar için detaylı kontrol
            AppTechnology.NATIVE_JAVA_KOTLIN -> detectNativeSSLPinning(apkContents)
            
            // React Native genellikle OkHttp kullanır
            AppTechnology.REACT_NATIVE -> {
                if (hasOkHttpPinning(apkContents)) {
                    SSLPinningMethod.OKHTTP_CERTIFICATE_PINNER
                } else if (hasNetworkSecurityConfig(apkContents)) {
                    SSLPinningMethod.NETWORK_SECURITY_CONFIG
                } else {
                    SSLPinningMethod.NONE_DETECTED
                }
            }
            
            // Xamarin için kontrol
            AppTechnology.XAMARIN -> {
                if (hasNetworkSecurityConfig(apkContents)) {
                    SSLPinningMethod.NETWORK_SECURITY_CONFIG
                } else {
                    SSLPinningMethod.NONE_DETECTED
                }
            }
            
            // Cordova için kontrol
            AppTechnology.CORDOVA -> {
                if (hasNetworkSecurityConfig(apkContents)) {
                    SSLPinningMethod.NETWORK_SECURITY_CONFIG
                } else {
                    SSLPinningMethod.NONE_DETECTED
                }
            }
            
            // Bilinmeyen teknoloji
            AppTechnology.UNKNOWN -> SSLPinningMethod.UNKNOWN
        }
    }

    /**
     * APK içindeki desteklenen CPU mimarilerini tespit eder.
     * 
     * lib/ dizini altındaki mimari klasörlerini kontrol eder:
     * - arm64-v8a
     * - armeabi-v7a
     * - x86_64
     * - x86
     * 
     * @param apkContents APK içindeki dosya yolları listesi
     * @return Tespit edilen mimari listesi
     * 
     * Requirements: 8.1
     */
    fun detectArchitectures(apkContents: List<String>): List<String> {
        val detectedArchitectures = mutableSetOf<String>()
        
        for (content in apkContents) {
            for (arch in supportedArchitectures) {
                if (content.startsWith("lib/$arch/")) {
                    detectedArchitectures.add(arch)
                }
            }
        }
        
        return detectedArchitectures.toList()
    }

    // ============================================================================
    // PRIVATE HELPER METHODS
    // ============================================================================

    /**
     * ApplicationInfo'dan APK yollarını alır.
     * Split APK durumunda tüm parçaları döndürür.
     */
    private fun getApkPaths(applicationInfo: ApplicationInfo): List<String> {
        val paths = mutableListOf<String>()
        
        // Ana APK
        applicationInfo.sourceDir?.let { paths.add(it) }
        
        // Split APK'lar (Android 5.0+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            applicationInfo.splitSourceDirs?.let { splits ->
                paths.addAll(splits)
            }
        }
        
        return paths
    }

    /**
     * APK dosyalarının içerik listesini alır.
     * Birden fazla APK varsa tüm içerikleri birleştirir.
     */
    private fun getApkContents(apkPaths: List<String>): List<String> {
        val contents = mutableListOf<String>()
        
        for (apkPath in apkPaths) {
            try {
                ZipFile(apkPath).use { zipFile ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        contents.add(entry.name)
                    }
                }
            } catch (e: Exception) {
                // APK okunamıyorsa devam et
                continue
            }
        }
        
        return contents
    }

    /**
     * Verilen imzaların APK içeriğinde bulunup bulunmadığını kontrol eder.
     * Wildcard (*) pattern'larını destekler.
     */
    private fun matchesSignatures(apkContents: List<String>, signatures: List<String>): Boolean {
        for (signature in signatures) {
            val pattern = signature.replace("*", ".*")
            val regex = Regex(pattern)
            
            val found = apkContents.any { content ->
                regex.matches(content)
            }
            
            if (found) {
                return true
            }
        }
        return false
    }

    /**
     * Native uygulama için SSL pinning yöntemini tespit eder.
     */
    private fun detectNativeSSLPinning(apkContents: List<String>): SSLPinningMethod {
        // OkHttp CertificatePinner kontrolü
        if (hasOkHttpPinning(apkContents)) {
            return SSLPinningMethod.OKHTTP_CERTIFICATE_PINNER
        }
        
        // Network Security Config kontrolü
        if (hasNetworkSecurityConfig(apkContents)) {
            return SSLPinningMethod.NETWORK_SECURITY_CONFIG
        }
        
        // TrustManager kontrolü (dex içinde arama gerektirir - basitleştirilmiş)
        if (hasTrustManagerUsage(apkContents)) {
            return SSLPinningMethod.TRUST_MANAGER_CUSTOM
        }
        
        return SSLPinningMethod.NONE_DETECTED
    }

    /**
     * OkHttp CertificatePinner kullanımını kontrol eder.
     */
    private fun hasOkHttpPinning(apkContents: List<String>): Boolean {
        val okHttpPatterns = listOf(
            "okhttp3/CertificatePinner",
            "com/squareup/okhttp/CertificatePinner"
        )
        
        return apkContents.any { content ->
            okHttpPatterns.any { pattern ->
                content.contains(pattern.replace("/", "/"))
            }
        }
    }

    /**
     * Network Security Config kullanımını kontrol eder.
     */
    private fun hasNetworkSecurityConfig(apkContents: List<String>): Boolean {
        return apkContents.any { content ->
            content == "res/xml/network_security_config.xml"
        }
    }

    /**
     * Özel TrustManager kullanımını kontrol eder.
     * Not: Bu basitleştirilmiş bir kontroldür, tam tespit için dex analizi gerekir.
     */
    private fun hasTrustManagerUsage(apkContents: List<String>): Boolean {
        // Basitleştirilmiş kontrol - gerçek implementasyonda dex analizi yapılmalı
        return false
    }

    /**
     * Flutter versiyonunu tespit etmeye çalışır.
     * libflutter.so dosyasından versiyon bilgisi çıkarılabilir.
     */
    private fun detectFlutterVersion(apkContents: List<String>): String? {
        // Flutter versiyonu genellikle libflutter.so içinde gömülüdür
        // Tam tespit için binary analizi gerekir
        // Şimdilik null döndürüyoruz
        return null
    }
}
