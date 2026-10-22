package com.storelense.gateBt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.storelense.gateBt.data.remote.TokenManager
import com.storelense.gateBt.ui.navigation.AppNavigation
import com.storelense.gateBt.ui.theme.GateBtTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GateBtTheme {
                AppNavigation(startLoggedIn = tokenManager.isLoggedIn)
            }
        }
    }
}
