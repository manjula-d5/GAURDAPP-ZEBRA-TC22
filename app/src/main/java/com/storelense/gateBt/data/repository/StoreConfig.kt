package com.storelense.gateBt.data.repository

data class StoreConfig(
    val cameraEnabled:      Boolean = false,
    val rfidVerifyEnabled:  Boolean = true,
    val strictMode:         Boolean = false,
    val manualEntryEnabled: Boolean = true
) {
    companion object {
        val DEFAULTS = StoreConfig()
    }
}
