package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.fmt.AbstractBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Parse a heysocks:// link into Neko's existing custom outbound profile. */
fun parseHeysocksLink(url: String): AbstractBean {
    val link = url.replaceFirst("heysocks://", "https://a:a@").toHttpUrlOrNull()
        ?: error("Invalid heysocks URL")
    val server = link.host
    val port = if (link.port > 0) link.port else 443
    val name = link.fragment ?: "xhttp $server:$port"
    val json = JSONObject().apply {
        put("type", "xhttp")
        put("tag", name)
        put("server", server)
        put("server_port", port)
        link.queryParameter("password")?.takeIf { it.isNotBlank() }?.let { put("password", it) }
        link.queryParameter("sess")?.takeIf { it.isNotBlank() }?.let { put("sess", it) }
        (link.queryParameter("auth") ?: link.queryParameter("token"))
            ?.takeIf { it.isNotBlank() }?.let { put("auth", it) }
    }
    return ConfigBean().apply {
        applyDefaultValues()
        type = 1
        config = json.toString(2)
        this.name = name
        serverAddress = server
        serverPort = port
    }
}
