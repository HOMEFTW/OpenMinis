package com.openminis.app.backup

import kotlinx.coroutines.sync.Mutex

/** Import and export touch the same rows and files; they must share one lock. */
internal val backupActivityLock = Mutex()
