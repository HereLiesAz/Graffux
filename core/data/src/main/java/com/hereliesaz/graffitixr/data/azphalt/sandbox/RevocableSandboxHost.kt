package com.hereliesaz.graffitixr.data.azphalt.sandbox

/**
 * An [AzphaltSandboxHost] that can be cut off. [runSandboxBounded] abandons a timed-out worker
 * thread rather than killing it (the JVM can't), and interruption only lands between guest bytecode
 * steps — so a runaway guest could otherwise keep calling into the real host after its invocation
 * had already been reported as failed. Once [revoke]d, reads return neutral values (0, null, empty)
 * and writes are dropped, so an abandoned guest can no longer observe or change app state.
 */
// One override per AzphaltSandboxHost function is the whole point of a proxy; the count is the
// interface's, not this class's.
@Suppress("TooManyFunctions")
internal class RevocableSandboxHost(private val delegate: AzphaltSandboxHost) : AzphaltSandboxHost {

    @Volatile
    var isRevoked: Boolean = false
        private set

    fun revoke() {
        isRevoked = true
    }

    private inline fun <T> gate(denied: T, call: AzphaltSandboxHost.() -> T): T =
        if (isRevoked) denied else delegate.call()

    override fun requestRedraw() = gate(Unit) { requestRedraw() }
    override fun canvasWidth(): Int = gate(0) { canvasWidth() }
    override fun canvasHeight(): Int = gate(0) { canvasHeight() }
    override fun canvasDpi(): Int = gate(0) { canvasDpi() }
    override fun paramNumber(key: String): Double? = gate(null) { paramNumber(key) }
    override fun paramBool(key: String): Boolean? = gate(null) { paramBool(key) }
    override fun paramString(key: String): String? = gate(null) { paramString(key) }
    override fun colorActive(): Int = gate(0) { colorActive() }
    override fun colorSetActive(rgba: Int) = gate(Unit) { colorSetActive(rgba) }
    override fun assetRead(path: String): ByteArray? = gate(null) { assetRead(path) }
    override fun selectionSize(): Int = gate(0) { selectionSize() }
    override fun selectionRead(): ByteArray = gate(ByteArray(0)) { selectionRead() }
    override fun layerCount(): Int = gate(0) { layerCount() }
}
