package com.openminis.app.data.storage

/** Preserve parents even when their row was lost. Cycles/query failures invalidate the snapshot. */
internal object SessionStorageOwners {
    suspend fun collect(
        sessionIds: Collection<String>,
        parentOf: suspend (String) -> String?,
        workspaceOwner: (String) -> String,
    ): Set<String> {
        val retained = linkedSetOf<String>()
        val visiting = mutableSetOf<String>()
        suspend fun visit(id: String, depth: Int = 0) {
            require(SessionStorageReferences.isIdentifier(id)) { "Invalid storage owner" }
            require(depth < 256 && id !in visiting) { "Session storage ownership cycle" }
            if (id in retained) return
            visiting += id
            retained += id
            parentOf(id)?.let { visit(it, depth + 1) }
            val owner = workspaceOwner(id)
            if (owner != id) visit(owner, depth + 1)
            visiting -= id
        }
        sessionIds.forEach { visit(it) }
        return retained
    }
}
