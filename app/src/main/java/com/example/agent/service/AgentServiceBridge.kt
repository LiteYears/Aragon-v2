package com.example.agent.service

import com.example.agent.core.AgentEngine

/**
 * Global bridge allowing AgentExecutionService to observe and synchronize with
 * the active AgentEngine orchestrator.
 */
object AgentServiceBridge {
  @Volatile
  var engine: AgentEngine? = null
}
