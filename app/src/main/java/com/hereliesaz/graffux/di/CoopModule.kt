package com.hereliesaz.graffux.di

import com.hereliesaz.graffitixr.common.coop.NoOpOpEmitter
import com.hereliesaz.graffitixr.common.coop.OpEmitter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The shared editor injects an [OpEmitter] (its hook into a co-op session). Graffux has no
 * collaboration, so it binds the [NoOpOpEmitter] — editor mutations are simply not broadcast.
 * (In GraffitiXR the same binding points at the real collab OpEmitterImpl.)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoopModule {
    @Binds
    @Singleton
    abstract fun bindOpEmitter(impl: NoOpOpEmitter): OpEmitter
}
