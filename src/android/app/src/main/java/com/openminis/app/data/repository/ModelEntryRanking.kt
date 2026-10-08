package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.provider.ModelReleaseIndex

/** Newest/capable models first; compute each rank once, outside the comparator. */
object ModelEntryRanking {
    private val releaseRankOrder = Comparator<Pair<ModelEntry, ModelReleaseIndex.Rank>> { a, b ->
        val byRank = ModelReleaseIndex.comparator.compare(a.second, b.second)
        if (byRank != 0) byRank else a.first.baseModel.id.compareTo(b.first.baseModel.id)
    }

    fun sortedByReleaseRank(entries: List<ModelEntry>): List<ModelEntry> {
        if (entries.size < 2) return entries
        return entries.map {
            it to ModelReleaseIndex.rank(it.baseModel.id, it.baseModel.displayName, it.baseModel.contextWindow)
        }.sortedWith(releaseRankOrder).map { it.first }
    }
}
