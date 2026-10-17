package com.example

import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

  @Test
  fun testViewModelInstantiationDoesNotCrash() {
    val app = org.robolectric.RuntimeEnvironment.getApplication()
    val vm = com.example.agent.ui.AgentViewModel(app)
    assert(vm.nvidiaModels.value.isNotEmpty())
    assert(vm.activeTab.value == com.example.agent.ui.UiTab.EXECUTION_FEED)
  }

  @Test
  fun testMainActivityLaunchesWithoutCrash() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
    val activity = controller.setup().get()
    assert(activity != null)
  }
}
