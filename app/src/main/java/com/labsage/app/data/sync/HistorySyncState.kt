package com.labsage.app.data.sync

data class HistorySyncState(
    val isSyncing: Boolean = false,
    val errorMessage: String? = null
)
