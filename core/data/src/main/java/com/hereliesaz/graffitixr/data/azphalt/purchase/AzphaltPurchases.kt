package com.hereliesaz.graffitixr.data.azphalt.purchase

import android.content.Context
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.azphalt.RepositoryApiClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one [PurchaseCoordinator], wired to the real Play Billing Library, azphalt.store, and the
 * same [ExtensionRepository.installFromStream] verification every other install path uses.
 */
@Singleton
class AzphaltPurchases @Inject constructor(
    @ApplicationContext context: Context,
    private val extensions: ExtensionRepository,
    private val repository: RepositoryApiClient,
) {
    private val store = EntitlementStore(File(context.filesDir, EntitlementStore.FILE_NAME))

    val coordinator = PurchaseCoordinator(
        appId = context.packageName,
        backend = PurchaseBackend(
            gateway = PlayBillingGateway(context),
            api = HttpPurchaseApi(),
            verifier = EntitlementVerifier { extensions.trustedKeys() },
            store = store,
        ),
        hooks = PurchaseHooks(
            install = { id, entitlement ->
                val detail = repository.packageDetail(id) ?: error("$id is no longer in the store")
                val version = detail.latest ?: detail.version
                val now = System.currentTimeMillis()
                val download = repository.download(id, version, entitlementToken = entitlement)
                extensions.recordDownloaded(id, version, now)
                download.stream.use { extensions.installFromStream(it, now, knownId = id, knownVersion = version) }
            },
            revoke = { id, _ -> extensions.uninstall(id) },
            isInstalled = { extensions.isInstalled(it) },
        ),
    )

    /** Paid package ids in the catalogue, for mapping Play products back to packages on restore. */
    fun paidCatalogue(appId: String): List<String> =
        repository.search(appId = appId).filter { it.priceStatus.isPaid }.map { it.id }
}
