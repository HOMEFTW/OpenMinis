package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry

internal const val MODEL_ABSENCE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

/** A partial provider catalogue must not immediately erase local overrides. */
internal fun retainAbsentModels(entries: List<ModelEntry>, now: Long): List<ModelEntry> =
    entries.mapNotNull { entry ->
        val since = entry.absentSince ?: now
        if (now - since > MODEL_ABSENCE_GRACE_MS) null else entry.copy(absentSince = since)
    }
