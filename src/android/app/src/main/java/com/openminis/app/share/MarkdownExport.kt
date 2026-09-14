package com.openminis.app.share

/** Titles are headings, not executable Markdown/HTML supplied by conversation content. */
internal fun markdownHeading(value: String): String = value.replace('\n', ' ').replace('\r', ' ')
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("#", "\\#")
