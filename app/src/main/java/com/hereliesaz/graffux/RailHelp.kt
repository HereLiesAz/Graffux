package com.hereliesaz.graffux

/**
 * What every rail control actually does, keyed by rail item id — the text AzNavRail's help overlay
 * shows when the `?` item is tapped.
 *
 * The overlay already knows each item's *label*. A label is a name, not an explanation, and this
 * app is full of controls whose name tells you nothing about what they do to your artwork: "Clip to
 * Below" and "Alpha Lock" are Procreate vocabulary, "Distort" and "Warp" are the same grid at two
 * resolutions, and half the rail is conditional — it appears only once something else is true, so a
 * user who has never seen it cannot go looking for it.
 *
 * Written to answer the question the user actually has, which is "what happens if I press this",
 * not "what is this called". Where a control has a precondition, the entry says so, because an item
 * that is missing is harder to understand than one that is present and greyed.
 *
 * Ids that no longer exist are ignored by the library, and items with no entry here still get a card
 * built from their label — so this can be incomplete without breaking, and is deliberately not
 * exhaustive: the obvious ones (Eraser, Undo) would be noise.
 */
internal val RAIL_HELP: Map<String, Any> = mapOf(
    // ── Selection ────────────────────────────────────────────────────────────────────────────────
    "grp.select" to
        "Everything to do with selecting a region. A selection confines every raster tool to it " +
        "until you clear it — if a brush suddenly paints nothing, check whether a selection is " +
        "still active. The Selection button turns amber whenever one is.",
    "tool.select" to
        "Drag to trace a region. Drag again *inside* an existing selection to move the pixels it " +
        "holds. What a drag draws is set by the shape below; what it does to the region you " +
        "already have is set by the mode below that.",
    "selectShape.FREEHAND" to "The next drag traces a lasso, freehand.",
    "selectShape.RECTANGLE" to "The next drag draws a rectangle.",
    "selectShape.ELLIPSE" to "The next drag draws an ellipse.",
    "selectShape.AUTOMATIC" to
        "Tap a colour and the region grows to cover everything touching it that looks the same. " +
        "How forgiving 'the same' is comes from Tolerance, in Tool Options.",
    "selectOp.NEW" to "The next drag replaces whatever is selected.",
    "selectOp.ADD" to "The next drag is added to the selection — draw several shapes into one region.",
    "selectOp.REMOVE" to "The next drag is cut out of the selection.",
    "tool.quick" to
        "The radial menu. Normally opened by holding four fingers on the canvas; this button opens " +
        "it in the middle of the screen for when you would rather not.",
    "tool.selectInvert" to "Swaps inside for outside: everything that was not selected now is.",
    "tool.deselect" to "Clears the selection, so tools reach the whole layer again.",
    "sel.save" to "Remembers this region under a name, so you can bring it back later.",

    // ── Transform ────────────────────────────────────────────────────────────────────────────────
    "adj.transform" to
        "Type exact numbers for the layer's position, scale and rotation. The frame around the " +
        "layer does the same thing by hand: drag its top-right corner to rotate, its bottom-right " +
        "to resize, and anywhere else on the frame — the other corners, or any edge — to move it.",
    "transform.FREEFORM" to "Move, scale and rotate the layer as a rigid rectangle.",
    "transform.DISTORT" to
        "Four corner handles you can drag independently — for putting artwork onto something seen " +
        "at an angle.",
    "transform.WARP" to
        "The same idea at sixteen handles instead of four: bend the layer rather than tilt it.",
    "transform.apply" to "Bakes the bend into the layer's pixels. There is no going back except undo.",

    // ── Painting ─────────────────────────────────────────────────────────────────────────────────
    "adj.brush" to
        "The brush pad. Drag up and down for size, left and right for hardness — soft on the left, " +
        "hard on the right. The circle is the brush at its real size; the number is its diameter.",
    "tool.colorize" to
        "Paints hue over what is already there, keeping its light and shade — for tinting " +
        "something that is already drawn rather than covering it.",
    "tool.liquify" to "Pushes pixels around under the brush, like wet paint. It does not add any.",
    "tool.smudge" to "Drags colour along the stroke, the way a finger drags charcoal.",
    "tool.heal" to
        "Repairs by sampling nearby pixels and blending them into the surrounding tone — for " +
        "removing something without leaving a patch.",
    "tool.clone" to
        "Copies pixels verbatim from somewhere else on the same layer. Aim it first: set the " +
        "source, then paint. The button turns on once it is aimed.",
    "tool.fill" to "Tap to flood the area under your finger with the current colour.",
    "tool.wraparound" to
        "The canvas tiles past its own edges: a stroke that leaves one side comes back on the " +
        "other. For seamless patterns.",

    // ── Layers ───────────────────────────────────────────────────────────────────────────────────
    "grp.layers" to
        "Your layers, topmost first. Drag one to reorder it. Tap one to work on it. Tap and hold " +
        "one to open its own menu, which is where everything that belongs to a single layer lives " +
        "— adjust, blend mode, transform, alpha lock, clipping, styles, and so on. A layer marked " +
        "amber is linked to the one you are on and will move with it.",
    "grp.brushes" to
        "Installed brushes, plus Brush Studio for making your own. The plain round brush is the " +
        "one selected when none of the others are.",
    "brush.studio" to
        "Edits the brush you are holding, or makes a new one if you are on the built-in round brush.",

    // ── Windows and the rest ─────────────────────────────────────────────────────────────────────
    "tool.options" to
        "The dials that belong to whatever tool is in your hand, and only those — stroke " +
        "stabilisation, the magic wand's tolerance, a selection's soft edge, a stamp brush's flow, " +
        "and which axis the symmetry guide mirrors across while it's on. A dial that is missing is " +
        "one the current tool does not have.",
    "tool.animation" to
        "Animation Assist. Each top-level layer becomes one frame; the window plays them, steps " +
        "through them, and draws onion skins of the neighbouring frames. Closing the window turns " +
        "the mode off. Time-lapse recording lives in here too.",
    "tool.model" to
        "Loads a 3D model (.obj) you can paint directly onto, then drop the painted view back onto " +
        "the canvas as a layer.",
    "adj.extensions" to
        "Runs an installed filter or colour LUT over the artwork. Installing and removing them is " +
        "'Extensions' in the menu at the top right.",
    "tool.color" to
        "The current colour — the button *is* the colour. Tap it to change it.",
    "grp.vector" to
        "The pen and everything else that isn't a flat-strip tool: node editing, Heal, Clone, " +
        "Blur, Sharpen, Smudge, Liquify, Dodge, Burn and Colorize. Draw a path with the Pen; then " +
        "Edit Nodes to move its points and their handles, add points, or close the shape.",
    "tool.nodeEdit" to
        "Turns the path's points into draggable handles. Only offered on a layer that holds a " +
        "single path.",

    // ── Gestures, which have no button at all ────────────────────────────────────────────────────
    "help" to
        "Two fingers on the canvas pan, pinch and rotate the view at any time, under any tool. " +
        "Two-finger tap undoes; three-finger tap redoes; hold either to repeat. Four-finger tap " +
        "hides the interface for a clean look at the artwork; four-finger hold opens the radial " +
        "menu. Three fingers rubbed back and forth clear the layer. All of them can be reassigned " +
        "in Settings.",
)
