package ai.droidpilot.app.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * mDNS discovery of adbd's wireless-debugging services (public NsdManager API):
 *   _adb-tls-pairing._tcp → the temporary PAIRING port
 *   _adb-tls-connect._tcp → the CONNECTION port
 * Port of Shizuku's AdbMdns (Apache-2.0). Only services that resolve to one of
 * our own interfaces (i.e. this very device) and are actually listening on
 * localhost are reported.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val onPortChanged: (Int) -> Unit
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.w(TAG, "discoverServices failed: ${e.message}")
                running = false
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {}
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        try {
            nsdManager.resolveService(info, ResolveListener(this))
        } catch (_: Exception) {}
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) onPortChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val host = resolvedService.host ?: return
        var isLocal = false
        val nis = NetworkInterface.getNetworkInterfaces()
        if (nis != null) {
            while (nis.hasMoreElements() && !isLocal) {
                val ni = nis.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val ha = addrs.nextElement().hostAddress
                    if (ha != null && ha == host.hostAddress) {
                        isLocal = true
                        break
                    }
                }
            }
        }
        if (running && isLocal && isPortAvailable(resolvedService.port)) {
            serviceName = resolvedService.serviceName
            onPortChanged(resolvedService.port)
        }
    }

    /** The port is ours when nothing else in this process already bound it on localhost. */
    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val mdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            mdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            mdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            mdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            mdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val mdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            mdns.onServiceResolved(serviceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
