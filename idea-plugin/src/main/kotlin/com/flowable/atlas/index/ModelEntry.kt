package com.flowable.atlas.index

import com.flowable.atlas.model.ModelType
import com.flowable.atlas.parsing.ModelMembers
import com.intellij.openapi.vfs.VirtualFile

/**
 * One indexed model: its key, display name, type, the file it was found in, and its members.
 *
 * The navigable IDE wrapper around the pure `:core` [com.flowable.atlas.parsing.RawModel] — it is
 * the only extraction type that carries a [VirtualFile], which is why it stays in the plugin while
 * the value types ([ModelMembers] et al.) live in `:core`.
 */
data class ModelEntry(
    val key: String,
    val name: String,
    val type: ModelType,
    val file: VirtualFile,
    val members: ModelMembers = ModelMembers.EMPTY,
)
