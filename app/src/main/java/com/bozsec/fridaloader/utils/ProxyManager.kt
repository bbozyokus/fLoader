package com.bozsec.fridaloader.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

class ProxyManager(private val context: Context) {
    
    private val rootUtil = RootUtil()
    
    /**
     * Set WiFi proxy using root commands
     * @param address Proxy address (e.g., "192.168.1.100")
     * @param port Proxy port (e.g., 8080)
     * @param bypass Comma-separated bypass list (e.g., "localhost,127.0.0.1")
     */
    suspend fun setWifiProxy(address: String, port: Int, bypass: String = ""): Boolean {
        return try {
            android.util.Log.i("ProxyManager", "Setting proxy: $address:$port, bypass: $bypass")
            
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            
            // Check if WiFi is enabled
            if (!wifiManager.isWifiEnabled) {
                android.util.Log.e("ProxyManager", "WiFi is disabled")
                return false
            }
            
            val wifiInfo = wifiManager.connectionInfo
            android.util.Log.i("ProxyManager", "WiFi Info: $wifiInfo")
            
            if (wifiInfo == null) {
                android.util.Log.e("ProxyManager", "WiFi info is null")
                return false
            }
            
            val networkId = wifiInfo.networkId
            android.util.Log.i("ProxyManager", "Network ID: $networkId")
            
            if (networkId == -1) {
                android.util.Log.e("ProxyManager", "Not connected to WiFi (networkId = -1)")
                // Try to proceed anyway with global settings
            }
            
            // Get SSID for logging
            val ssid = wifiInfo.ssid?.replace("\"", "") ?: "unknown"
            android.util.Log.i("ProxyManager", "SSID: $ssid")
            
            // Format bypass list for Android (pipe-separated)
            val bypassFormatted = bypass.replace(",", "|").replace(" ", "")
            
            // Use content command to modify WiFi configuration directly
            val commands = mutableListOf<String>()
            
            // Method 1: Use settings put for global proxy (some apps respect this)
            commands.add("settings put global http_proxy $address:$port")
            commands.add("settings put global global_http_proxy_host $address")
            commands.add("settings put global global_http_proxy_port $port")
            if (bypassFormatted.isNotEmpty()) {
                commands.add("settings put global global_http_proxy_exclusion_list '$bypassFormatted'")
            }
            
            // Method 2: Try using content provider (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && networkId != -1) {
                android.util.Log.i("ProxyManager", "Trying content provider method for Android 10+")
                commands.add("cmd wifi set-proxy $networkId $address $port '$bypassFormatted'")
            }
            
            // Execute all commands
            var anySuccess = false
            for (cmd in commands) {
                val result = rootUtil.execute("su -c '$cmd'")
                android.util.Log.i("ProxyManager", "Command: $cmd -> Success: ${result.success}, Output: ${result.output}, Error: ${result.error}")
                if (result.success) {
                    anySuccess = true
                }
            }
            
            // Force WiFi reconnection to apply changes
            android.util.Log.i("ProxyManager", "Reconnecting WiFi to apply changes...")
            val reconnectResult = rootUtil.execute("su -c 'svc wifi disable && sleep 2 && svc wifi enable'")
            android.util.Log.i("ProxyManager", "Reconnect result: ${reconnectResult.success}")
            
            anySuccess
            
        } catch (e: Exception) {
            android.util.Log.e("ProxyManager", "Error setting proxy: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Clear WiFi proxy settings
     */
    suspend fun clearWifiProxy(): Boolean {
        return try {
            android.util.Log.i("ProxyManager", "Clearing proxy settings")
            
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val networkId = wifiInfo?.networkId ?: -1
            
            val commands = mutableListOf<String>()
            
            // Clear global settings
            commands.add("settings delete global http_proxy")
            commands.add("settings delete global global_http_proxy_host")
            commands.add("settings delete global global_http_proxy_port")
            commands.add("settings delete global global_http_proxy_exclusion_list")
            commands.add("settings delete global global_proxy_pac_url")
            
            // Also clear system settings (some apps check these)
            commands.add("settings delete system http_proxy")
            commands.add("settings delete system http_proxy_host")
            commands.add("settings delete system http_proxy_port")
            
            // Clear WiFi-specific proxy (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && networkId != -1) {
                commands.add("cmd wifi clear-proxy $networkId")
            }
            
            var anySuccess = false
            for (cmd in commands) {
                val result = rootUtil.execute("su -c '$cmd'")
                android.util.Log.i("ProxyManager", "Clear command: $cmd -> ${result.success}")
                if (result.success) anySuccess = true
            }
            
            // Force WiFi reconnection with longer delay
            android.util.Log.i("ProxyManager", "Reconnecting WiFi to apply changes...")
            val reconnectResult = rootUtil.execute("su -c 'svc wifi disable && sleep 2 && svc wifi enable'")
            android.util.Log.i("ProxyManager", "Reconnect result: ${reconnectResult.success}")
            
            // Wait for WiFi to reconnect
            kotlinx.coroutines.delay(3000)
            
            // Fix DNS if it's set to IPv6 link-local
            val dns1Result = rootUtil.execute("su -c 'getprop net.dns1'")
            val dns1 = dns1Result.output.trim()
            if (dns1.startsWith("fe80::")) {
                android.util.Log.w("ProxyManager", "DNS1 is IPv6 link-local ($dns1), fixing...")
                // Get DNS2 which should be the router
                val dns2Result = rootUtil.execute("su -c 'getprop net.dns2'")
                val dns2 = dns2Result.output.trim()
                if (dns2.isNotEmpty() && !dns2.startsWith("fe80::")) {
                    rootUtil.execute("su -c 'setprop net.dns1 $dns2'")
                    android.util.Log.i("ProxyManager", "DNS1 fixed to $dns2")
                } else {
                    // Fallback to Google DNS
                    rootUtil.execute("su -c 'setprop net.dns1 8.8.8.8'")
                    android.util.Log.i("ProxyManager", "DNS1 fixed to 8.8.8.8")
                }
            }
            
            android.util.Log.i("ProxyManager", "Proxy cleared successfully")
            true
        } catch (e: Exception) {
            android.util.Log.e("ProxyManager", "Error clearing proxy: ${e.message}")
            false
        }
    }
    
    /**
     * Get current proxy settings
     */
    suspend fun getCurrentProxy(): ProxySettings? {
        return try {
            val hostResult = rootUtil.execute("su -c 'settings get global global_http_proxy_host'")
            val portResult = rootUtil.execute("su -c 'settings get global global_http_proxy_port'")
            val bypassResult = rootUtil.execute("su -c 'settings get global global_http_proxy_exclusion_list'")
            
            val host = hostResult.output.trim()
            val portStr = portResult.output.trim()
            val bypass = bypassResult.output.trim().replace("|", ",")
            
            if (host.isNotEmpty() && host != "null" && portStr.isNotEmpty() && portStr != "null") {
                val port = portStr.toIntOrNull() ?: 0
                if (port > 0) {
                    return ProxySettings(host, port, bypass)
                }
            }
            
            null
        } catch (e: Exception) {
            android.util.Log.e("ProxyManager", "Error getting proxy: ${e.message}")
            null
        }
    }
    
    

    
    /**
     * setTransparentProxy using iptables
     * Redirects all TCP traffic on ports 80 and 443 to the specified proxy.
     * Also blocks UDP to force QUIC fallback to TCP.
     * 
     * NOTE: Takes effect immediately for new connections.
     * 
     * @param address Proxy IP address
     * @param port Proxy Port
     * @param enableTcpRedirect Redirect TCP (HTTP/HTTPS) to proxy
     * @param enableUdpBlock Block UDP (QUIC) to force TCP fallback
     * @param enableIpv6Block Block IPv6 to ensure IPv4 usage
     */
    suspend fun setTransparentProxy(
        address: String, 
        port: Int,
        enableTcpRedirect: Boolean = true,
        enableUdpBlock: Boolean = true,
        enableIpv6Block: Boolean = true
    ): Boolean {
        return try {
            android.util.Log.i("ProxyManager", "Setting transparent proxy to $address:$port (TCP: $enableTcpRedirect, UDP: $enableUdpBlock, IPv6: $enableIpv6Block)")
            
            // First clear existing rules to prevent duplicates
            clearTransparentProxy()
            
            val commands = mutableListOf<String>()
            
            // 1. Redirect IPv4 TCP Traffic (HTTP/HTTPS)
            if (enableTcpRedirect) {
                commands.add("iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination $address:$port")
                commands.add("iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination $address:$port")
            }
            
            // 2. Block UDP 443/80 to force QUIC fallback to TCP
            // Flutter often uses QUIC (UDP), which bypasses TCP proxies. Blocking UDP forces it to use TCP.
            if (enableUdpBlock) {
                commands.add("iptables -A OUTPUT -p udp --dport 443 -j DROP")
                commands.add("iptables -A OUTPUT -p udp --dport 80 -j DROP")
            }
            
            // 3. Handle IPv6 (Redirect or Block to force IPv4)
            // Ideally we redirect, but Burp/Charles usually listen on IPv4. 
            // Blocking IPv6 is safer to force app to use IPv4.
            if (enableIpv6Block) {
                commands.add("ip6tables -A OUTPUT -p tcp --dport 443 -j DROP") // Block IPv6 HTTPS
                commands.add("ip6tables -A OUTPUT -p tcp --dport 80 -j DROP")  // Block IPv6 HTTP
                commands.add("ip6tables -A OUTPUT -p udp --dport 443 -j DROP") // Block IPv6 QUIC
            }
            
            var allSuccess = true
            for (cmd in commands) {
                val result = rootUtil.execute("su -c '$cmd'")
                android.util.Log.i("ProxyManager", "Iptables command: $cmd -> ${result.success}")
                // Don't fail total success if ip6tables missing
                if (!result.success && !cmd.startsWith("ip6tables")) {
                    allSuccess = false
                }
            }
            
            allSuccess
        } catch (e: Exception) {
            android.util.Log.e("ProxyManager", "Error setting transparent proxy: ${e.message}")
            false
        }
    }

    /**
     * clearTransparentProxy
     * Removes iptables rules created by setTransparentProxy
     */
    suspend fun clearTransparentProxy(): Boolean {
        return try {
            android.util.Log.i("ProxyManager", "Clearing transparent proxy (iptables)")
            
            val commands = mutableListOf<String>()
            
            // Flush NAT OUTPUT (DNAT rules)
            commands.add("iptables -t nat -F OUTPUT")
            
            // Delete UDP DROP rules from Filter OUTPUT
            // Since we use generic -A, we can try to delete by spec, or just flush OUTPUT if safe?
            // Flushing filter OUTPUT is risky if user has firewall.
            // Safer: Delete specific rules we added.
            commands.add("iptables -D OUTPUT -p udp --dport 443 -j DROP")
            commands.add("iptables -D OUTPUT -p udp --dport 80 -j DROP")
            
            // Clean IPv6
            commands.add("ip6tables -F OUTPUT") // Assuming we don't care about other ipv6 output rules on this pentest device
            
            var anySuccess = false
            for (cmd in commands) {
                val result = rootUtil.execute("su -c '$cmd'")
                android.util.Log.i("ProxyManager", "Clear command: $cmd -> ${result.success}")
                if (result.success) anySuccess = true
            }
            
            anySuccess
        } catch (e: Exception) {
            android.util.Log.e("ProxyManager", "Error clearing transparent proxy: ${e.message}")
            false
        }
    }

    /**
     * Reset network settings - aggressive cleanup for fixing connection issues
     */
    suspend fun resetNetwork(): Boolean {
        return try {
            android.util.Log.i("ProxyManager", "Resetting network settings...")
            
            val commands = mutableListOf<String>()
            
            // Delete all proxy-related settings
            commands.add("settings delete global http_proxy")
            commands.add("settings delete global global_http_proxy_host")
            commands.add("settings delete global global_http_proxy_port")
            commands.add("settings delete global global_http_proxy_exclusion_list")
            commands.add("settings delete global global_proxy_pac_url")
            commands.add("settings delete system http_proxy")
            commands.add("settings delete system http_proxy_host")
            commands.add("settings delete system http_proxy_port")
            
            // Set http_proxy to :0 (explicitly disable)
            commands.add("settings put global http_proxy :0")
            
            // Clear iptables
            commands.add("iptables -t nat -F OUTPUT")
            
            var successCount = 0
            for (cmd in commands) {
                val result = rootUtil.execute("su -c '$cmd'")
                android.util.Log.i("ProxyManager", "Reset command: $cmd -> ${result.success}")
                if (result.success) successCount++
            }
            
            // Restart WiFi
            android.util.Log.i("ProxyManager", "Restarting WiFi...")
            rootUtil.execute("su -c 'svc wifi disable && sleep 2 && svc wifi enable'")
            
            // Wait for WiFi to reconnect
            kotlinx.coroutines.delay(3000)
            
            // Fix DNS if needed
            val dns1Result = rootUtil.execute("su -c 'getprop net.dns1'")
            val dns1 = dns1Result.output.trim()
            if (dns1.startsWith("fe80::") || dns1.isEmpty()) {
                android.util.Log.w("ProxyManager", "Fixing DNS...")
                rootUtil.execute("su -c 'setprop net.dns1 8.8.8.8'")
                rootUtil.execute("su -c 'setprop net.dns2 8.8.4.4'")
            }
            
            android.util.Log.i("ProxyManager", "Network reset completed: $successCount commands succeeded")
            true
        } catch (e: Exception) {
            android.util.Log.e("ProxyManager", "Error resetting network: ${e.message}")
            false
        }
    }
    
    data class ProxySettings(
        val address: String,
        val port: Int,
        val bypass: String
    )
}
