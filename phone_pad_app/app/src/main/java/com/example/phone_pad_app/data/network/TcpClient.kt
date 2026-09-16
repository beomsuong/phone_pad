package com.example.phone_pad_app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TcpClient @Inject constructor() {

    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        val s = Socket(host, port)
        socket = s
        writer = PrintWriter(s.getOutputStream(), true)
    }

    suspend fun send(json: String) = withContext(Dispatchers.IO) {
        checkNotNull(writer) { "Not connected" }.println(json)
    }

    fun disconnect() {
        writer?.close()
        socket?.close()
        writer = null
        socket = null
    }

    val isConnected: Boolean
        get() = socket?.let { !it.isClosed && it.isConnected } ?: false
}
