# Graffux Import — Figma plugin

Brings a Graffux document into Figma with its layers intact.

## Why a plugin

Figma's REST API can read a file but cannot create design content in one. Artwork only gets *into* a
Figma file from code running as a plugin, so the app and this plugin split the job:

1. **Graffux** (Android) — *Menu → Export for Figma* writes a `.graffux-figma.json` bundle to Downloads.
2. **You** move that file to the computer running Figma, by whatever means you already use.
3. **This plugin** reads it and rebuilds the document as a frame, one layer per Graffux layer.

## Installing

No build step — it's plain JavaScript, no dependencies.

1. In the Figma desktop app: **Plugins → Development → Import plugin from manifest…**
2. Pick `figma-plugin/manifest.json` from this repository.

It then appears under **Plugins → Development → Graffux Import**.

## What comes across

Each layer is composited by the app at full document size with its opacity, blend mode, and clipping
neutralised. That bakes the layer's *geometry* into its image — so the plugin can place every layer
full-bleed at the frame origin and reproduce the composition exactly — while opacity and blend arrive
as live Figma properties you can keep editing rather than pixels you can't.

| Graffux | Figma |
| --- | --- |
| Layer name | Layer name |
| Layer order | Child order within the frame |
| Opacity | Node opacity |
| Blend mode | Node blend mode |
| Hidden layer | Hidden node |
| Document size | Frame size |

**Blend modes:** the separable and non-separable modes map one-to-one. Graffux also exposes the
Porter-Duff compositing operators (`SrcIn`, `DstOut`, and so on), which have no Figma counterpart —
those layers arrive with the right pixels but blend as `NORMAL`, so rearranging them in Figma won't
re-composite the way it did in the app.

**Vectors** are rasterised. Layers are images in Figma, not editable paths.

## File format

A single JSON file with base64-inlined PNGs — chosen over a zip so the plugin needs nothing but
`JSON.parse` and a base64 decode, with no archive library inside Figma's sandbox. The roughly 33%
size cost buys a plugin with no build step and no dependencies.

```jsonc
{
  "version": 1,
  "name": "My Project",
  "documentWidth": 1080,
  "documentHeight": 1080,
  "layers": [
    {
      "name": "Background",
      "pngBase64": "iVBORw0KGgo…",   // document-sized PNG
      "opacity": 1.0,
      "blendMode": "NORMAL",          // a Figma BlendMode string
      "visible": true
    }
  ]
}
```

Layers are listed bottom-first, matching the app's own order (index 0 paints first, underneath
everything).

The canonical definition lives in
[`core/data/src/main/java/com/hereliesaz/graffitixr/data/figma/FigmaBundle.kt`](../core/data/src/main/java/com/hereliesaz/graffitixr/data/figma/FigmaBundle.kt).

## Privacy

The plugin makes no network requests — `manifest.json` declares no allowed domains. It only reads the
file you hand it.
