package com.lookee.tv.data

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** The TV's LAN IPv4 address for the phone setup URL, or "" to let the Go core detect it. */
fun lanAddress(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return ""
    return props.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
        .orEmpty()
}
