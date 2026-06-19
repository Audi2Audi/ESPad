package com.espad32.controller

/**
 * Singleton holding the active TcpClient so other activities
 * (MatrixCanvasActivity, LogViewerActivity) can send commands
 * without needing to manage their own connections.
 *
 * Set in MainActivity.connectToCar() whenever a new connection is made.
 */
object MainTcpHolder {
    var client: TcpClient? = null
    var enqueue: ((String) -> Unit)? = null
    var onNextData: ((String) -> Unit)? = null
}
