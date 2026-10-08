package com.openminis.app.ui.scheduled

import java.util.Locale

/** Kept in this module until the shared string resources are integrated. */
internal fun scheduledText(chinese: String, english: String): String =
    if (Locale.getDefault().language == "zh") chinese else english
