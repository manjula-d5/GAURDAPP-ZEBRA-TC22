package com.storelense.gateBt.ui.gate

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** DataWedge profile and trigger helpers for the TC22's built-in imager. */
internal object ZebraDataWedge {
    const val SCAN_ACTION = "com.storelense.gateBt.action.BARCODE_SCAN"
    const val DATA_STRING = "com.symbol.datawedge.data_string"

    fun configure(context: Context) {
        val barcodeParams = Bundle().apply {
            putString("scanner_selection_by_identifier", "INTERNAL_IMAGER")
            putString("scanner_input_enabled", "true")
            // Enable the TC22's physical scan trigger for this app's active profile.
            putString("barcode_trigger_mode", "1")
            // Assign both built-in side scan keys to the internal imager.
            putStringArray("scanner_trigger_resource", arrayOf("LEFT", "RIGHT"))
        }
        val barcodePlugin = Bundle().apply {
            putString("PLUGIN_NAME", "BARCODE")
            putString("RESET_CONFIG", "false")
            putBundle("PARAM_LIST", barcodeParams)
        }

        val intentParams = Bundle().apply {
            putString("intent_output_enabled", "true")
            putString("intent_action", SCAN_ACTION)
            putString("intent_category", "android.intent.category.DEFAULT")
            putInt("intent_delivery", 2) // Broadcast
        }
        val intentPlugin = Bundle().apply {
            putString("PLUGIN_NAME", "INTENT")
            putString("RESET_CONFIG", "false")
            putBundle("PARAM_LIST", intentParams)
        }

        val keystrokePlugin = Bundle().apply {
            putString("PLUGIN_NAME", "KEYSTROKE")
            putString("RESET_CONFIG", "false")
            putBundle("PARAM_LIST", Bundle().apply {
                putString("keystroke_output_enabled", "false")
            })
        }

        val appAssociation = Bundle().apply {
            putString("PACKAGE_NAME", context.packageName)
            putStringArray("ACTIVITY_LIST", arrayOf("*"))
        }
        val profile = Bundle().apply {
            putString("PROFILE_NAME", "StoreLenseGateTC22")
            putString("PROFILE_ENABLED", "true")
            // Reapply trigger settings if an older version already created the profile.
            putString("CONFIG_MODE", "OVERWRITE")
            putParcelableArray("APP_LIST", arrayOf(appAssociation))
            putParcelableArrayList("PLUGIN_CONFIG", arrayListOf(barcodePlugin, intentPlugin, keystrokePlugin))
        }

        context.sendBroadcast(
            Intent("com.symbol.datawedge.api.ACTION")
                .setPackage("com.symbol.datawedge")
                .putExtra("com.symbol.datawedge.api.SET_CONFIG", profile)
        )
    }

    fun startScan(context: Context) {
        context.sendBroadcast(
            Intent("com.symbol.datawedge.api.ACTION_SOFTSCANTRIGGER")
                .setPackage("com.symbol.datawedge")
                .putExtra("com.symbol.datawedge.api.EXTRA_PARAMETER", "START_SCANNING")
        )
    }
}
