package com.labsage.app.data.auth

interface AuthSessionStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
    fun clear()
    fun saveIfCurrent(expected: AuthSession, session: AuthSession): Boolean
    fun clearIfCurrent(expected: AuthSession): Boolean
}
