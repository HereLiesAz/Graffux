package com.hereliesaz.graffitixr.feature.editor

/** How loud an [EditorNotice] is. Maps one-to-one onto the rail popup's NOTICE / WARNING kinds. */
enum class NoticeKind { NOTICE, WARNING }

/** One message for the user, emitted on [EditorViewModel.notices] and shown by the host. */
data class EditorNotice(val kind: NoticeKind, val text: String)
