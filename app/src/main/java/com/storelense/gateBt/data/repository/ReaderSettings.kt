package com.storelense.gateBt.data.repository

data class ReaderSettings(
    val bluetoothAddress: String? = null,
    val bluetoothName:    String? = null,
    val txPowerDbm:       Int     = 20
)
