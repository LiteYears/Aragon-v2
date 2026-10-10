package com.example.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.agent.core.AgentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps the Aragon Agent Kernel alive and executing in the background,
 * even when the app is minimized, screen is locked, or other apps are opened.
 * Holds a partial WakeLock to prevent the CPU from entering deep sleep during tasks.
 */
class AgentExecutionService : Service() {

  private var wakeLock: PowerManager.WakeLock? = null
  private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
  private var monitorJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action

    if (action == ACTION_STOP) {
      stopForegroundExecution()
      stopSelf()
      return START_NOT_STICKY
    }

    val taskGoal = intent?.getStringExtra(EXTRA_TASK_GOAL) ?: "Running autonomous task..."

    acquireWakeLock()
    startForegroundWithNotification(taskGoal)
    monitorAgentProgress()

    return START_NOT_STICKY
  }

  private fun startForegroundWithNotification(taskGoal: String) {
    val notification = buildNotification("Aragon Autonomous Agent", taskGoal, isOngoing = true)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification, serviceType)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (_: Exception) {
      try {
        startForeground(NOTIFICATION_ID, notification)
      } catch (_: Exception) {}
    }
  }

  private fun monitorAgentProgress() {
    monitorJob?.cancel()
    monitorJob = serviceScope.launch {
      val engine = AgentServiceBridge.engine ?: return@launch
      engine.state.collectLatest { state ->
        when (state.status) {
          AgentStatus.THINKING,
          AgentStatus.PLANNING,
          AgentStatus.EXECUTING_TOOL,
          AgentStatus.OBSERVING,
          AgentStatus.VERIFYING -> {
            val content = state.currentAction ?: "Executing autonomous tasks..."
            val updatedNotif = buildNotification(
              title = "Aragon: Executing Task",
              content = content.take(120),
              isOngoing = true
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, updatedNotif)
          }
          AgentStatus.COMPLETED -> {
            val summary = state.completionSummary ?: "Deliverables verified successfully."
            val completedNotif = buildNotification(
              title = "Aragon: Task Completed",
              content = summary.take(120),
              isOngoing = false
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, completedNotif)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
          }
          AgentStatus.FAILED -> {
            val errorMsg = state.error ?: "Task halted"
            val errorNotif = buildNotification(
              title = "Aragon: Task Halted",
              content = errorMsg.take(120),
              isOngoing = false
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, errorNotif)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
          }
          AgentStatus.CANCELLED -> {
            stopForegroundExecution()
            stopSelf()
          }
          AgentStatus.IDLE -> {}
        }
      }
    }
  }

  private fun buildNotification(title: String, content: String, isOngoing: Boolean): Notification {
    val openAppIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
      this,
      0,
      openAppIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val stopIntent = Intent(this, AgentExecutionService::class.java).apply {
      action = ACTION_STOP
    }
    val stopPendingIntent = PendingIntent.getService(
      this,
      1,
      stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle(title)
      .setContentText(content)
      .setStyle(NotificationCompat.BigTextStyle().bigText(content))
      .setContentIntent(openPendingIntent)
      .setOngoing(isOngoing)
      .setAutoCancel(!isOngoing)
      .setPriority(NotificationCompat.PRIORITY_LOW)

    if (isOngoing) {
      builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", stopPendingIntent)
    }

    return builder.build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Agent Task Execution",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Displays background execution progress for autonomous agent tasks"
        setShowBadge(false)
      }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      nm?.createNotificationChannel(channel)
    }
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
      wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aragon::ExecutionWakeLock")?.apply {
        setReferenceCounted(false)
        acquire(15 * 60 * 1000L) // 15 minutes safety ceiling
      }
    }
  }

  private fun releaseWakeLock() {
    try {
      wakeLock?.let {
        if (it.isHeld) it.release()
      }
    } catch (_: Exception) {}
    wakeLock = null
  }

  private fun stopForegroundExecution() {
    monitorJob?.cancel()
    releaseWakeLock()
    try {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } catch (_: Exception) {}
  }

  override fun onDestroy() {
    super.onDestroy()
    stopForegroundExecution()
  }

  companion object {
    const val CHANNEL_ID = "aragon_agent_execution_channel"
    const val NOTIFICATION_ID = 1001
    const val ACTION_START = "com.example.agent.START_TASK"
    const val ACTION_STOP = "com.example.agent.STOP_TASK"
    const val EXTRA_TASK_GOAL = "extra_task_goal"

    fun start(context: Context, taskGoal: String) {
      try {
        val intent = Intent(context, AgentExecutionService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_TASK_GOAL, taskGoal)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (_: Exception) {}
    }

    fun stop(context: Context) {
      try {
        val intent = Intent(context, AgentExecutionService::class.java).apply {
          action = ACTION_STOP
        }
        context.startService(intent)
      } catch (_: Exception) {}
    }
  }
}
