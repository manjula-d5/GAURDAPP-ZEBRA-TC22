package com.storelense.gateBt.di

import com.storelense.gateBt.BuildConfig
import com.storelense.gateBt.rfid.AutoRfidReader
import com.storelense.gateBt.rfid.MockRfidReader
import com.storelense.gateBt.rfid.RfidReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RfidModule {

    /**
     * Selects mock for mock builds; hardware builds prefer integrated Zebra RFID,
     * then use the paired Bluetooth reader when the built-in reader is unavailable.
     *  - mock flavor / debug local.properties  → [MockRfidReader] (no hardware needed)
     */
    @Provides @Singleton
    fun provideRfidReader(
        autoReader:      AutoRfidReader,
        mockReader:      MockRfidReader
    ): RfidReader = if (BuildConfig.READER_TYPE == "MOCK" || BuildConfig.USE_MOCK_RFID) mockReader else autoReader
}
