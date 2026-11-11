package com.example.pushbridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.pushbridge.ui.main.MainScreen

@Composable
fun MainNavigation(
    onRequestSmsPermission: () -> Unit = {},
    permissionTrigger: Int = 0
) {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onRequestSmsPermission = onRequestSmsPermission,
            permissionTrigger = permissionTrigger,
            modifier = Modifier
              .fillMaxSize()
              .safeDrawingPadding()
          )
        }
      },
  )
}
