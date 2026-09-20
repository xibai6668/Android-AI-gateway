package dev.aigw.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address

/** 取当前网络的局域网 IPv4 地址，用于「局域网」那条接入地址。 */
object LanAddresses {

    fun current(context: Context): List<String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.linkAddresses
            .map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
