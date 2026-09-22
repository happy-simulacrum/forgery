package com.forgery.app.feature.analyze.impl.di

import com.forgery.app.feature.analyze.impl.AnalyzeImageSource
import com.forgery.app.feature.analyze.impl.ContentResolverAnalyzeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyzeModule {
    @Binds
    abstract fun bindImageSource(impl: ContentResolverAnalyzeSource): AnalyzeImageSource
}
