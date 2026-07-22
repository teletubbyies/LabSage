package com.labsage.app.data.auth

interface AuthSessionStore {
    fun load(): AuthSession?
    fun save(session: AuthSession)
    fun clear()
}
