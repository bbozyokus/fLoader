package com.bozsec.fridaloader.ssl

/**
 * Flutter SSL Bypass için veri modelleri ve enum tanımlamaları.
 * 
 * Bu dosya, SSL bypass işlemlerinde kullanılan tüm veri yapılarını içerir.
 */

// ============================================================================
// ENUM TANIMLARI
// ============================================================================

/**
 * Uygulama teknolojisi türleri.
 * APK analizi sonucunda tespit edilen uygulama geliştirme teknolojisi.
 */
enum class AppTechnology {
    /** Flutter framework ile geliştirilmiş uygulama */
    FLUTTER,
    /** React Native framework ile geliştirilmiş uygulama */
    REACT_NATIVE,
    /** Xamarin framework ile geliştirilmiş uygulama */
    XAMARIN,
    /** Native Java/Kotlin ile geliştirilmiş uygulama */
    NATIVE_JAVA_KOTLIN,
    /** Apache Cordova ile geliştirilmiş uygulama */
    CORDOVA,
    /** Teknoloji tespit edilemedi */
    UNKNOWN
}

/**
 * SSL Pinning yöntemleri.
 * Uygulamanın kullandığı SSL sertifika doğrulama yöntemi.
 */
enum class SSLPinningMethod {
    /** Flutter'ın BoringSSL tabanlı sertifika doğrulaması */
    FLUTTER_BORINGSSL,
    /** OkHttp CertificatePinner kullanımı */
    OKHTTP_CERTIFICATE_PINNER,
    /** Özel TrustManager implementasyonu */
    TRUST_MANAGER_CUSTOM,
    /** Android Network Security Config kullanımı */
    NETWORK_SECURITY_CONFIG,
    /** SSL pinning tespit edilemedi */
    NONE_DETECTED,
    /** Bilinmeyen SSL pinning yöntemi */
    UNKNOWN
}

/**
 * Bypass işlem adımları.
 * SSL bypass sürecindeki her bir adımı temsil eder.
 */
enum class ProcessStep {
    /** APK dosyası cihazdan çıkarılıyor */
    EXTRACTING_APK,
    /** Split APK'lar birleştiriliyor */
    MERGING_SPLITS,
    /** Flutter libflutter.so yamalanıyor */
    PATCHING_FLUTTER,
    /** APK imzalanıyor */
    SIGNING_APK,
    /** Uygulama cihaza kuruluyor */
    INSTALLING
}

// ============================================================================
// DATA CLASS TANIMLARI
// ============================================================================

/**
 * Proxy yapılandırması.
 * SSL trafiğini yönlendirmek için kullanılacak proxy sunucu bilgileri.
 * 
 * @property ipAddress Proxy sunucusunun IPv4 adresi
 * @property port Proxy sunucusunun port numarası
 */
data class ProxyConfig(
    val ipAddress: String,
    val port: Int
) {
    /**
     * Proxy yapılandırmasının geçerli olup olmadığını kontrol eder.
     * 
     * Geçerlilik kriterleri:
     * - IP adresi geçerli bir IPv4 formatında olmalı (0.0.0.0 - 255.255.255.255)
     * - Port numarası 1-65535 aralığında olmalı
     * 
     * @return Yapılandırma geçerliyse true, değilse false
     */
    fun isValid(): Boolean {
        val ipPattern = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        return ipPattern.matches(ipAddress) && port in 1..65535
    }
}

/**
 * Bypass işlemi sonucu.
 * SSL bypass işleminin tamamlanma durumunu ve sonuçlarını içerir.
 * 
 * @property success İşlem başarılı mı
 * @property outputApkPath Yamalanmış APK dosyasının yolu (başarılıysa)
 * @property patchedArchitectures Başarıyla yamalanan mimariler listesi
 * @property error Hata durumunda hata bilgisi
 */
data class BypassResult(
    val success: Boolean,
    val outputApkPath: String?,
    val patchedArchitectures: List<String>,
    val error: BypassError?
)

/**
 * Bypass hata türleri.
 * SSL bypass işlemi sırasında oluşabilecek tüm hata durumlarını temsil eder.
 */
sealed class BypassError {
    /** Uygulama cihazda bulunamadı */
    data class AppNotFound(val packageName: String) : BypassError()
    
    /** APK dosyası geçersiz veya bozuk */
    data class InvalidApk(val reason: String) : BypassError()
    
    /** Uygulama Flutter ile geliştirilmemiş */
    data class NotFlutterApp(val detectedTech: String) : BypassError()
    
    /** libflutter.so dosyası bulunamadı */
    data class LibFlutterNotFound(val architecture: String) : BypassError()
    
    /** Flutter versiyonu desteklenmiyor */
    data class UnsupportedFlutterVersion(val version: String) : BypassError()
    
    /** Yamalama işlemi başarısız */
    data class PatchingFailed(val reason: String) : BypassError()
    
    /** İmzalama işlemi başarısız */
    data class SigningFailed(val reason: String) : BypassError()
    
    /** Kurulum işlemi başarısız */
    data class InstallationFailed(val reason: String) : BypassError()
    
    /** Yetersiz depolama alanı */
    data class InsufficientStorage(val required: Long, val available: Long) : BypassError()
    
    /** Root yetkisi gerekli */
    data class RootRequired(val operation: String) : BypassError()
    
    /** İşlem kullanıcı tarafından iptal edildi */
    object Cancelled : BypassError()
}

/**
 * Kurulum sonucu.
 * Yamalanmış APK'nın cihaza kurulum durumunu içerir.
 * 
 * @property success Kurulum başarılı mı
 * @property error Hata durumunda hata mesajı
 */
data class InstallResult(
    val success: Boolean,
    val error: String?
)

/**
 * Mimari pattern bilgisi.
 * libflutter.so yamalama için kullanılan byte pattern'ları.
 * 
 * @property searchPattern Aranacak byte dizisi
 * @property patchPattern Değiştirilecek byte dizisi
 * @property offset Pattern'ın başlangıç offset'i
 */
data class ArchPattern(
    val searchPattern: ByteArray,
    val patchPattern: ByteArray,
    val offset: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArchPattern

        if (!searchPattern.contentEquals(other.searchPattern)) return false
        if (!patchPattern.contentEquals(other.patchPattern)) return false
        if (offset != other.offset) return false

        return true
    }

    override fun hashCode(): Int {
        var result = searchPattern.contentHashCode()
        result = 31 * result + patchPattern.contentHashCode()
        result = 31 * result + offset
        return result
    }
}

/**
 * Uygulama analiz sonucu.
 * APK veya yüklü uygulama analizinin detaylı sonuçlarını içerir.
 * 
 * @property packageName Uygulama paket adı
 * @property appName Uygulama görünen adı
 * @property versionName Versiyon adı (örn: "1.0.0")
 * @property versionCode Versiyon kodu
 * @property technology Tespit edilen uygulama teknolojisi
 * @property sslPinningMethod Tespit edilen SSL pinning yöntemi
 * @property isSplitApk Split APK mi
 * @property apkPaths APK dosya yolları listesi
 * @property architectures Desteklenen CPU mimarileri
 * @property flutterVersion Tespit edilen Flutter versiyonu (varsa)
 * @property canBypass SSL bypass uygulanabilir mi
 * @property bypassMethod Önerilen bypass yöntemi açıklaması
 */
data class AppAnalysisResult(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val technology: AppTechnology,
    val sslPinningMethod: SSLPinningMethod,
    val isSplitApk: Boolean,
    val apkPaths: List<String>,
    val architectures: List<String>,
    val flutterVersion: String?,
    val canBypass: Boolean,
    val bypassMethod: String?
)

/**
 * APK çıkarma sonucu.
 * Yüklü uygulamadan APK dosyası çıkarma işleminin sonucu.
 * 
 * @property success İşlem başarılı mı
 * @property apkPaths Çıkarılan APK dosya yolları
 * @property isSplit Split APK mi
 * @property error Hata durumunda hata mesajı
 */
data class ExtractResult(
    val success: Boolean,
    val apkPaths: List<String>,
    val isSplit: Boolean,
    val error: String?
)

/**
 * APK birleştirme sonucu.
 * Split APK'ların tek APK'ya birleştirilme işleminin sonucu.
 * 
 * @property success İşlem başarılı mı
 * @property mergedApkPath Birleştirilmiş APK dosya yolu
 * @property error Hata durumunda hata mesajı
 */
data class MergeResult(
    val success: Boolean,
    val mergedApkPath: String?,
    val error: String?
)

/**
 * Yamalama sonucu.
 * libflutter.so yamalama işleminin sonucu.
 * 
 * @property success İşlem başarılı mı
 * @property patchedArchitectures Başarıyla yamalanan mimariler
 * @property failedArchitectures Yamalama başarısız olan mimariler
 * @property error Hata durumunda hata mesajı
 */
data class PatchResult(
    val success: Boolean,
    val patchedArchitectures: List<String>,
    val failedArchitectures: List<String>,
    val error: String?
)

/**
 * İmzalama sonucu.
 * APK imzalama işleminin sonucu.
 * 
 * @property success İşlem başarılı mı
 * @property signedApkPath İmzalanmış APK dosya yolu
 * @property error Hata durumunda hata mesajı
 */
data class SignResult(
    val success: Boolean,
    val signedApkPath: String?,
    val error: String?
)
