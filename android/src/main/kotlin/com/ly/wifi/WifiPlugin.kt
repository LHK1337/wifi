package com.ly.wifi

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import com.ly.wifi.NetworkType.*

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.net.NetworkInterface
import java.util.jar.Manifest

/** WifiPlugin */
class WifiPlugin : FlutterPlugin, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private var permissionResult: Result? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "plugins.ly.com/wifi")
        channel.setMethodCallHandler(this)
    }

    private fun intToIpAddressString(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    // https://github.com/flutter/plugins/blob/master/packages/connectivity/connectivity/android/src/main/java/io/flutter/plugins/connectivity/Connectivity.java
    @SuppressWarnings("deprecation")
    private fun getNetworkType(): NetworkType {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return none
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return wifi
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return mobile
            }
        } else {
            val info = connectivityManager.activeNetworkInfo
            if (info == null || !info.isConnected) {
                return none
            }
            when (info.type) {
                ConnectivityManager.TYPE_ETHERNET,
                ConnectivityManager.TYPE_WIFI,
                ConnectivityManager.TYPE_WIMAX -> return wifi
                ConnectivityManager.TYPE_MOBILE,
                ConnectivityManager.TYPE_MOBILE_DUN,
                ConnectivityManager.TYPE_MOBILE_HIPRI -> return mobile
            }
        }
        return none
    }

    private fun requestPermission(permissionName: String, requestCode: Int) {
        if (activity != null) {
            ActivityCompat.requestPermissions(activity!!, arrayOf(permissionName), requestCode)
        } else {
            permissionResult?.error("unavailable", "unable to request permission", "requesting permissions requires a foreground activity");
            permissionResult = null;
        }
    }

    private fun hasPermission(permissionName: String): Boolean {
        return ActivityCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
    }

    private fun getLevelForSignalStrength(strength: Int): Int {
        return if (strength == 0) {
            -1
        } else if (strength <= 0 && strength >= -55) {
            3
        } else if (strength < -55 && strength >= -80) {
            2
        } else if (strength < -80 && strength >= -100) {
            1
        } else {
            0
        }
    }

    private fun getIpAddress(): String? {
        when (getNetworkType()) {
            wifi -> {
                val ipInteger = wifiManager.connectionInfo.ipAddress
                return intToIpAddressString(ipInteger)
            }
            mobile -> {
                for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                    for (inetAddress in networkInterface.inetAddresses) {
                        if (!inetAddress.isLoopbackAddress) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            }
            else -> return null
        }
        return null
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "networkInfo" -> {
                val info = wifiManager.connectionInfo
                val dhcpInfo = wifiManager.dhcpInfo
                val map = mapOf(
                        "ssid" to info.ssid.substring(1, info.ssid.length - 1),
                        "bssid" to info.bssid,
                        "level" to getLevelForSignalStrength(info.rssi),
                        "ip" to getIpAddress(),
                        "linkSpeed" to info.linkSpeed,
                        "downloadLinkSpeed" to info.rxLinkSpeedMbps,
                        "uploadLinkSpeed" to info.txLinkSpeedMbps,
                        "hiddenSsid" to info.hiddenSSID,
                        "dhcpInfo" to mapOf(
                                "dns1" to intToIpAddressString(dhcpInfo.dns1),
                                "dns2" to intToIpAddressString(dhcpInfo.dns2),
                                "gateway" to intToIpAddressString(dhcpInfo.gateway),
                                "netmask" to intToIpAddressString(dhcpInfo.netmask)
                        )
                )
                result.success(map)
            }
            "list" -> {
                if (hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    val scanResults = wifiManager.scanResults;
                    val resultList = mutableListOf<Map<String, Any>>()
                    for (scanResult in scanResults) {
                        val channelWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            scanResult.channelWidth
                        } else {
                            -1
                        }
                        val map = mapOf(
                                "ssid" to scanResult.SSID,
                                "bssid" to scanResult.BSSID,
                                "level" to getLevelForSignalStrength(scanResult.level),
                                "frequency" to scanResult.frequency,
                                "capabilities" to scanResult.capabilities,
                                "channelWidth" to channelWidth
                        )
                        resultList.add(map)
                    }
                    result.success(resultList)
                } else {
                    result.error("permission", "the ssid is unknown", "this feature requires location permission starting android q")
                }
            }
            "locationPermission" -> {
                permissionResult?.error("failed", "permission request timed out", "permission request called while requesting")
                permissionResult = result
                requestPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_ACCESS_FINE_LOCATION_PERMISSION)
            }
            "changeNetworkPermission" -> {
                permissionResult?.error("failed", "permission request timed out", "permission request called while requesting")
                permissionResult = result
                requestPermission(android.Manifest.permission.CHANGE_WIFI_STATE, REQUEST_CHANGE_WIFI_STATE_PERMISSION)
            }
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    companion object {
        private const val REQUEST_ACCESS_FINE_LOCATION_PERMISSION = 1;
        private const val REQUEST_CHANGE_WIFI_STATE_PERMISSION = 2;
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?): Boolean {
        val granted = !(grantResults == null || grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        when (requestCode) {
            REQUEST_ACCESS_FINE_LOCATION_PERMISSION, REQUEST_CHANGE_WIFI_STATE_PERMISSION -> {
                permissionResult?.success(granted)
                permissionResult = null
                return true
            }
        }
        return false
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }
}
