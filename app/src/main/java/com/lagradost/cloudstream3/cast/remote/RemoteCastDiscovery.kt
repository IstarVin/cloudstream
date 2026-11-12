package com.lagradost.cloudstream3.cast.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

data class RemoteCastDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** Only known when found over NSD */
    val deviceId: String? = null,
)

/**
 * Handles both sides of NSD (mDNS) for the cast feature:
 * the TV advertises itself, the phone looks for TVs.
 */
object RemoteCastDiscovery {
    private const val TAG = "RemoteCastDiscovery"
    private const val ATTR_ID = "id"

    private val mainHandler = Handler(Looper.getMainLooper())

    // ----- Receiver (TV) -----

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(context: Context, serviceName: String, deviceId: String, port: Int) {
        unregister(context)
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = REMOTE_CAST_SERVICE_TYPE
            this.port = port
            setAttribute(ATTR_ID, deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Registered ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "registerService failed", t)
            registrationListener = null
        }
    }

    fun unregister(context: Context) {
        val listener = registrationListener ?: return
        registrationListener = null
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        try {
            nsd?.unregisterService(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "unregisterService failed", t)
        }
    }

    // ----- Controller (phone) -----

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveryNsd: NsdManager? = null
    private var onDevices: ((List<RemoteCastDevice>) -> Unit)? = null

    private val found = LinkedHashMap<String, RemoteCastDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Devices found so far, in discovery order */
    val devices: List<RemoteCastDevice>
        get() = synchronized(found) { found.values.toList() }

    /**
     * Starts looking for TVs, [callback] is invoked on the main thread every time the list changes.
     * Call [stopDiscovery] when done.
     */
    fun startDiscovery(context: Context, callback: (List<RemoteCastDevice>) -> Unit) {
        stopDiscovery()
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return
        discoveryNsd = nsd
        onDevices = callback
        synchronized(found) { found.clear() }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val removed = synchronized(found) { found.remove(serviceInfo.serviceName) }
                if (removed != null) notifyChanged()
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(REMOTE_CAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "discoverServices failed", t)
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        onDevices = null
        try {
            discoveryNsd?.stopServiceDiscovery(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "stopServiceDiscovery failed", t)
        }
        discoveryNsd = null
    }

    private fun notifyChanged() {
        val callback = onDevices ?: return
        val snapshot = devices
        mainHandler.post { callback(snapshot) }
    }

    // NsdManager only supports one active resolve at a time on many versions, so queue them.
    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
        }
        resolveNext()
    }

    private fun resolveNext() {
        val nsd = discoveryNsd ?: return
        val next = synchronized(resolveQueue) {
            if (resolving) return
            val item = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            item
        }

        fun done() {
            synchronized(resolveQueue) { resolving = false }
            resolveNext()
        }

        @Suppress("DEPRECATION")
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "Resolve failed: $errorCode")
                    done()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try {
                        val host = serviceInfo.bestHostAddress()
                        if (host != null) {
                            val id = serviceInfo.attributes?.get(ATTR_ID)?.toString(Charsets.UTF_8)
                            val device = RemoteCastDevice(
                                name = serviceInfo.serviceName,
                                host = host,
                                port = serviceInfo.port,
                                deviceId = id,
                            )
                            synchronized(found) { found[serviceInfo.serviceName] = device }
                            notifyChanged()
                        }
                    } finally {
                        done()
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "resolveService failed", t)
            done()
        }
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.bestHostAddress(): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses
        } else {
            listOfNotNull(host)
        }
        // IPv4 is much more reliable for ExoPlayer/sockets on local networks
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())
            ?.hostAddress
    }
}
