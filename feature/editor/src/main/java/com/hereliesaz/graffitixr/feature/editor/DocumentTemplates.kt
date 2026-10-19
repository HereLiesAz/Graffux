// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DocumentTemplates.kt
package com.hereliesaz.graffitixr.feature.editor

/** A named canvas size: pixels at the resolution it is normally made for. */
data class DocumentTemplate(val name: String, val widthPx: Int, val heightPx: Int, val dpi: Int)

/** A kind of document (paper, a social platform…) and the templates offered for it. */
data class DocumentCategory(val name: String, val templates: List<DocumentTemplate>)

private const val PRINT_DPI = 300
private const val SCREEN_DPI = 72

private fun print(name: String, w: Int, h: Int) = DocumentTemplate(name, w, h, PRINT_DPI)
private fun screen(name: String, w: Int, h: Int) = DocumentTemplate(name, w, h, SCREEN_DPI)

/**
 * Document types the new-project dialog offers. "Screen" (this device's own size) and "Custom"
 * (typed sizes, no templates) are added by the dialog itself, so they are not listed here.
 */
@Suppress("MagicNumber") // A catalogue of published pixel sizes.
val DOCUMENT_CATEGORIES: List<DocumentCategory> = listOf(
    DocumentCategory(
        "Paper",
        listOf(
            print("US Letter", 2550, 3300),
            print("US Legal", 2550, 4200),
            print("Tabloid", 3300, 5100),
            print("A5", 1748, 2480),
            print("A4", 2480, 3508),
            print("A3", 3508, 4961),
            print("Postcard 4×6 in", 1200, 1800),
            print("Business card", 1050, 600),
            print("Poster 18×24 in", 5400, 7200),
        ),
    ),
    DocumentCategory(
        "Instagram",
        listOf(
            screen("Post, square", 1080, 1080),
            screen("Post, portrait", 1080, 1350),
            screen("Post, landscape", 1080, 566),
            screen("Story", 1080, 1920),
            screen("Reel", 1080, 1920),
            screen("Profile photo", 320, 320),
        ),
    ),
    DocumentCategory(
        "Facebook",
        listOf(
            screen("Post", 1200, 630),
            screen("Cover banner", 851, 315),
            screen("Event cover", 1920, 1005),
            screen("Story", 1080, 1920),
            screen("Reel", 1080, 1920),
            screen("Profile photo", 320, 320),
        ),
    ),
    DocumentCategory(
        "X (Twitter)",
        listOf(
            screen("Post", 1600, 900),
            screen("Header banner", 1500, 500),
            screen("Profile photo", 400, 400),
        ),
    ),
    DocumentCategory(
        "YouTube",
        listOf(
            screen("Thumbnail", 1280, 720),
            screen("Channel banner", 2560, 1440),
            screen("Short", 1080, 1920),
            screen("Profile photo", 800, 800),
        ),
    ),
    DocumentCategory(
        "LinkedIn",
        listOf(
            screen("Post", 1200, 627),
            screen("Cover banner", 1584, 396),
            screen("Story", 1080, 1920),
            screen("Profile photo", 400, 400),
        ),
    ),
    DocumentCategory(
        "TikTok",
        listOf(
            screen("Video", 1080, 1920),
            screen("Profile photo", 200, 200),
        ),
    ),
    DocumentCategory(
        "Pinterest",
        listOf(
            screen("Pin", 1000, 1500),
            screen("Square pin", 1000, 1000),
            screen("Profile photo", 165, 165),
        ),
    ),
)

/** The dialog's two built-in category names. */
const val CATEGORY_SCREEN = "Screen"
const val CATEGORY_CUSTOM = "Custom"
