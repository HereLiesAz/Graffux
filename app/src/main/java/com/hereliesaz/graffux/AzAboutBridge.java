package com.hereliesaz.graffux;

import androidx.compose.runtime.Composer;
import com.hereliesaz.aznavrail.AzNavRailScopeImpl;
import com.hereliesaz.aznavrail.internal.AboutOverlayKt;
import com.hereliesaz.aznavrail.internal.MoreFromAzOverlayKt;
import com.hereliesaz.aznavrail.model.AzAdvancedConfig;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * Reaches AzNavRail's About reader, which the library declares {@code internal} and reaches only
 * from its own rail and drop-down. Java sees past Kotlin's module visibility; the two overlays are
 * ordinary public methods in the bytecode. Called from {@link AboutReaderKt} with the caller's
 * composer, exactly as the Compose compiler would call them.
 */
final class AzAboutBridge {
    private AzAboutBridge() {}

    static void about(
        String repoUrl,
        AzNavRailScopeImpl scope,
        Function0<Unit> onOpenMoreFromAz,
        Function0<Unit> onDismiss,
        Composer composer
    ) {
        AboutOverlayKt.AboutOverlay(repoUrl, scope, onOpenMoreFromAz, onDismiss, composer, 0);
    }

    /** The "More from Az" page, from the library's own default manifest. */
    static void moreFromAz(AzNavRailScopeImpl scope, Function0<Unit> onDismiss, Composer composer) {
        String manifest = new AzAdvancedConfig().getMoreFromAzJsonUrl();
        MoreFromAzOverlayKt.MoreFromAzOverlay(manifest, scope, onDismiss, composer, 0);
    }
}
