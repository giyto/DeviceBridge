package ru.hznik.devicebridge.domain.model

data class ServerEndpoint(
    val host: String,
    val port: Int,
    /** Served over HTTPS by the phone's own certificate authority. */
    val secure: Boolean = false,
    /** The name the phone answers to on the local network, e.g. `devicebridge.local`. */
    val localName: String? = null,
    val nameStatus: LocalNameStatus = LocalNameStatus.NotUsed,
) {
    init {
        require(host.isNotBlank()) { "Server host must not be blank" }
        require(port in 1..65_535) { "Server port must be in 1..65535" }
        require((localName != null) == (nameStatus is LocalNameStatus.Claimed)) {
            "A local name goes together with a claimed name status"
        }
    }

    /** `ip:port`: the Host of a request made by IP. */
    val ipAuthority: String = "$host:$port"

    /**
     * The one address to open in a browser: by name while the phone holds one, by IP only when it
     * could not get a name. It stays `http://` in secure mode too: there it leads to the
     * certificate setup page, or straight on to HTTPS once the browser trusts the phone, so nobody
     * meets the browser's certificate error screen first.
     */
    val url: String = "http://" + (localName?.let { "$it:$port" } ?: ipAuthority)

    /** The Host header the server serves: the name while it holds one, otherwise the IP. */
    val authorities: Set<String>
        get() = setOf(localName?.let { "$it:$port" } ?: ipAuthority)

    /** The same endpoint after another device took the name while the server was running. */
    fun withNameLost(): ServerEndpoint =
        copy(localName = null, nameStatus = LocalNameStatus.Unavailable(LocalNameStatus.Reason.CONFLICT, requestedLabel()))

    private fun requestedLabel(): String? = (nameStatus as? LocalNameStatus.Claimed)?.requestedLabel
}

/** Whether the phone answers to a name on the local network, and why not when it does not. */
sealed interface LocalNameStatus {
    /** No name was tried: tests and builds without mDNS. */
    data object NotUsed : LocalNameStatus

    /**
     * [requestedLabel] is the label from the settings, e.g. `devicebridge`; when it was taken,
     * the phone holds a suffixed one and the UI says so.
     */
    data class Claimed(val requestedLabel: String, val requestedTaken: Boolean) : LocalNameStatus

    data class Unavailable(val reason: Reason, val requestedLabel: String? = null) : LocalNameStatus

    enum class Reason {
        /** The name and all its suffixes are taken by other devices. */
        TAKEN,

        /** Another device started answering to the name while the server was running. */
        CONFLICT,

        /** This phone or network does not let the app use mDNS. */
        NETWORK,

        /** Secure mode is on and the phone's certificate does not cover this name. */
        CERTIFICATE,
    }
}
