package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.ui.AgentViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Agent Kernel", appName)
  }

  @Test
  fun `agent view model instantiates successfully`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val vm = AgentViewModel(application)
    assertNotNull(vm)
    assertNotNull(vm.state.value)
    assertTrue(vm.workspaceDir.exists())
  }
}
