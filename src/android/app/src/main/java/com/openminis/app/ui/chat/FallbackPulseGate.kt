package com.openminis.app.ui.chat

/** Re-entering a cached session does not replay a previous model switch. */
internal fun shouldPulse(current: Int, baseline: Int): Boolean = current > baseline
