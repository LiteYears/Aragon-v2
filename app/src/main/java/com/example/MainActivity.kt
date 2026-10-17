package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.agent.ui.AgentScreen
import com.example.agent.ui.AgentViewModel
import com.example.ui.theme.MyApplicationTheme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.agent.ui.components.AragonMonochromeSplashScreen
import com.example.ui.theme.AmoledBackground

class MainActivity : ComponentActivity() {
  private val viewModel: AgentViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        var showSplash by rememberSaveable { mutableStateOf(true) }

        Surface(
          modifier = Modifier.fillMaxSize(),
          color = AmoledBackground
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            AgentScreen(
              viewModel = viewModel,
              onReplaySplash = { showSplash = true }
            )

            AnimatedVisibility(
              visible = showSplash,
              enter = fadeIn(animationSpec = tween(250)),
              exit = fadeOut(animationSpec = tween(400))
            ) {
              AragonMonochromeSplashScreen(
                onDismiss = { showSplash = false }
              )
            }
          }
        }
      }
    }
  }
}

