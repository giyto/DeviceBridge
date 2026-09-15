package ru.hznik.devicebridge.domain.model

data class ServerEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "Server host must not be blank" }
        require(port in 1..65_535) { "Server port must be in 1..65535" }
    }

    val url: String = "http://$host:$port"
}
