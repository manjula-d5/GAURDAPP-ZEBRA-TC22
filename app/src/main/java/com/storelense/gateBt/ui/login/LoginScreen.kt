package com.storelense.gateBt.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val TealPrimary = Color(0xFF0F766E)

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier  = Modifier.fillMaxWidth().padding(24.dp),
            colors    = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp),
            shape     = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier            = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("StoreLense", fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, color = TealPrimary)
                Text(
                    "Security Gate · BT Reader",
                    fontSize = 14.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(40.dp))

                OutlinedTextField(
                    value         = state.username,
                    onValueChange = vm::onUsername,
                    label         = { Text("Username") },
                    leadingIcon   = { Icon(Icons.Default.Person, null, tint = TealPrimary) },
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        focusedLabelColor  = TealPrimary
                    )
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value         = state.password,
                    onValueChange = vm::onPassword,
                    label         = { Text("Password") },
                    leadingIcon   = { Icon(Icons.Default.Lock, null, tint = TealPrimary) },
                    trailingIcon  = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                null, tint = Color(0xFF94A3B8)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine    = true,
                    shape         = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.login(onLoginSuccess) }),
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TealPrimary,
                        focusedLabelColor  = TealPrimary
                    )
                )

                if (state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(state.error!!, color = Color(0xFFDC2626), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick  = { vm.login(onLoginSuccess) },
                    enabled  = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = Color.White)
                    } else {
                        Text("Sign In to Device", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
