package com.msp1974.vacompanion.di

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDeviceManager(@ApplicationContext context: Context): DeviceManager {
        return DeviceManager(context)
    }
}
