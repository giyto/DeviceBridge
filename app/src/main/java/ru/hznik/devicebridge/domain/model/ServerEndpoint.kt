package ru.hznik.devicebridge.domain.model

data class ServerEndpoint(
    val host: String,
    val port: Int,
    /** Served over HTTPS by the phone's own certificate authority. */
    val secure: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { "Server host must not be blank" }
        require(port in 1..65_535) { "Server port must be in 1..65535" }
    }

    /**
     * The address to open in a browser. It stays `http://` in secure mode too: there it leads to
     * the certificate setup page, or straight on to HTTPS once the browser trusts the phone, so
     * nobody meets the browser's certificate error screen first.
     */
    val url: String = "http://$host:$port"
}
