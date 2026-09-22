package com.forgery.app.feature.inpaint.impl.di

import com.forgery.app.feature.inpaint.impl.ContentResolverImageReader
import com.forgery.app.feature.inpaint.impl.ImageAttachmentReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InpaintModule {
    @Binds
    abstract fun bindImageReader(impl: ContentResolverImageReader): ImageAttachmentReader
}
