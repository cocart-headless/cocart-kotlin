package com.cocart.sdk.storage

interface CoCartStorage {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun delete(key: String)
}
