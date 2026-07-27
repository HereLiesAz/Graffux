// Graffux Import — the Figma half of Graffux's "push to Figma".
//
// Figma's REST API cannot create design content, so the only way artwork gets INTO a Figma file is
// from a plugin running inside Figma. The app writes a bundle (see core/data/.../FigmaBundle.kt);
// the user moves it to their computer however they like; this reads it and rebuilds the layers.
//
// Runs on the plugin's main thread, which has no DOM and no fetch — the UI iframe (ui.html) does the
// file reading and posts the parsed bundle over.

figma.showUI(__html__, { width: 320, height: 240 });

figma.ui.onmessage = async (msg) => {
  if (msg.type !== 'import-bundle') return;

  try {
    await importBundle(msg.bundle);
  } catch (e) {
    figma.ui.postMessage({ type: 'error', message: String((e && e.message) || e) });
  }
};

async function importBundle(bundle) {
  if (!bundle || !Array.isArray(bundle.layers) || bundle.layers.length === 0) {
    throw new Error("That file has no layers in it.");
  }

  const width = Math.max(1, Math.round(bundle.documentWidth || 0));
  const height = Math.max(1, Math.round(bundle.documentHeight || 0));

  const frame = figma.createFrame();
  frame.name = bundle.name || 'Graffux';
  frame.resize(width, height);
  // The artwork supplies its own background; a white frame fill would sit under every layer and
  // turn a transparent export opaque.
  frame.fills = [];

  // Bottom-up, matching the app's own layer order (index 0 paints first). appendChild puts each new
  // node on top of the previous, so iterating in order reproduces the stack.
  for (const layer of bundle.layers) {
    const bytes = base64ToBytes(layer.pngBase64);
    const image = figma.createImage(bytes);

    // Every layer was composited at full document size by the app, so it's a full-bleed fill at the
    // origin — no per-layer geometry to reconstruct here.
    const rect = figma.createRectangle();
    rect.name = layer.name || 'Layer';
    rect.resize(width, height);
    rect.x = 0;
    rect.y = 0;
    rect.fills = [{ type: 'IMAGE', scaleMode: 'FILL', imageHash: image.hash }];

    // Opacity and blend arrive as live Figma properties rather than baked into the pixels, so they
    // stay editable here.
    if (typeof layer.opacity === 'number') rect.opacity = clamp01(layer.opacity);
    if (layer.blendMode) rect.blendMode = layer.blendMode;
    if (layer.visible === false) rect.visible = false;

    frame.appendChild(rect);
  }

  frame.x = figma.viewport.center.x - width / 2;
  frame.y = figma.viewport.center.y - height / 2;

  figma.currentPage.selection = [frame];
  figma.viewport.scrollAndZoomIntoView([frame]);

  figma.ui.postMessage({ type: 'done', count: bundle.layers.length });
  figma.notify(`Imported ${bundle.layers.length} layer${bundle.layers.length === 1 ? '' : 's'} from Graffux`);
}

function clamp01(v) {
  return Math.min(1, Math.max(0, v));
}

// The plugin sandbox has no atob, so base64 is decoded by hand. Whitespace is tolerated because
// some transfer paths (email, chat clients) re-wrap long lines.
function base64ToBytes(base64) {
  if (typeof base64 !== 'string' || base64.length === 0) {
    throw new Error('A layer in that file has no image data.');
  }
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const clean = base64.replace(/[\s]/g, '').replace(/=+$/, '');
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4));

  let bits = 0;
  let accumulator = 0;
  let written = 0;
  for (let i = 0; i < clean.length; i++) {
    const value = chars.indexOf(clean[i]);
    if (value === -1) throw new Error('That file contains malformed image data.');
    accumulator = (accumulator << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[written++] = (accumulator >> bits) & 0xff;
    }
  }
  return out.subarray(0, written);
}
