package com.cocart.sdk.storage

class MemoryStorage : CoCartStorage {
    private val store = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = store[key]
    override suspend fun set(key: String, value: String) { store[key] = value }
    override suspend fun delete(key: String) { store.remove(key) }
}
