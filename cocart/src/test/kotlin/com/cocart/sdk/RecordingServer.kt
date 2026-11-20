package com.cocart.sdk

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/** A request seen by a [RecordingServer]. */
data class SeenRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String?>,
    val body: String
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun json(): Map<String, Any?> = JSONObject(body).let { obj -> obj.keys().asSequence().associateWith { obj.get(it) } }
}

/**
 * A MockWebServer that answers with the queued responses (then `{}`) and records every
 * request, so tests can check the exact method, path, query and body sent.
 */
class RecordingServer {
    val server = MockWebServer()
    private val queue = ConcurrentLinkedQueue<MockResponse>()
    val requests = ConcurrentLinkedQueue<SeenRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                requests.add(
                    SeenRequest(
                        method = request.method!!,
                        path = url.encodedPath,
                        query = url.encodedQuery,
                        headers = request.headers.names().associate { it.lowercase() to request.getHeader(it) },
                        body = request.body.readUtf8()
                    )
                )
                return queue.poll() ?: MockResponse().setBody("{}")
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    fun enqueue(status: Int, body: String, vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(status).setBody(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        queue.add(response)
    }

    fun last(): SeenRequest = requests.last()

    fun shutdown() = server.shutdown()
}
