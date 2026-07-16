package com.example.processor

interface CommandStrategy {
    fun canHandle(command: String): Boolean
    suspend fun execute(command: String, isScheduled: Boolean): CommandResult
}
