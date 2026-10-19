package com.openminis.app.ui.sandbox

import java.io.File
import java.net.URI
import java.util.Locale

internal sealed interface PreviewLinkTarget {
    data class LocalFile(val file: File) : PreviewLinkTarget
    data class Web(val url: String) : PreviewLinkTarget
    data class External(val url: String) : PreviewLinkTarget
    data class Anchor(val fragment: String) : PreviewLinkTarget
    data object Unsupported : PreviewLinkTarget
}

/** Resolve paths against the document, without treating '+' as a space or '#' as a filename. */
internal fun resolvePreviewLink(document: File, link: String): PreviewLinkTarget {
    val raw = link.trim()
    if (raw.isEmpty()) return PreviewLinkTarget.Unsupported
    return runCatching {
        val uri = URI((if (raw.startsWith("//")) "https:$raw" else raw).replace(" ", "%20"))
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> if (uri.rawAuthority.isNullOrBlank()) PreviewLinkTarget.Unsupported
                else PreviewLinkTarget.Web(uri.toASCIIString())
            "file" -> if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") PreviewLinkTarget.Unsupported
                else PreviewLinkTarget.LocalFile(File(URI("file", null, uri.path, null, null)).canonicalFile)
            null -> {
                val path = uri.path.orEmpty()
                if (path.isEmpty()) PreviewLinkTarget.Anchor(uri.fragment.orEmpty())
                else {
                    val file = File(path)
                    PreviewLinkTarget.LocalFile(
                        (if (file.isAbsolute) file else File(document.absoluteFile.parentFile, path)).canonicalFile,
                    )
                }
            }
            "javascript", "data", "content" -> PreviewLinkTarget.Unsupported
            else -> PreviewLinkTarget.External(uri.toASCIIString())
        }
    }.getOrDefault(PreviewLinkTarget.Unsupported)
}
