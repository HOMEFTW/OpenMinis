package com.openminis.app.tools

import kotlinx.coroutines.Dispatchers

/** Shared across chats: file calls must not occupy the entire general IO pool. */
internal object FileToolDispatcher {
    val io = Dispatchers.IO.limitedParallelism(4)
}
