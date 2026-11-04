# October 3 testing downloads

By Luke Steuber.

The [download page](https://dr.eamer.dev/downloads/apps/signal-station/) now offers Android **0.7.0-local-dev (20)** and the standalone Pebble **1.8.1** preview. The corresponding Android source is `fe5a79ad`, embedded in the exact APK, and the watch source is `12d06fae`. Later companion commits add documentation, a watch descriptor and compatibility tests; they do not change build 20's production code.

| Artifact | SHA-256 |
| --- | --- |
| Android APK | `5a2258e38ab2f5204ab4b21118e09543ec196a78f697f1557b63b54063ea2d76` |
| Pebble PBW | `e5a65e155ad66ae21ab8fce230bac1e9dfaebd81442eaf7733e80d2094ee1024` |

The debug APK retains certificate `e1faa235dbdd132ac86856639b60b12ad9f9b658fc921fd8160db84ed8dfa28d`, matching the previous public build. The hosted APK, PBW, source archives, guides, checksums, app page and catalog bytes were verified over HTTPS. Both public domains serve the updated page. Desktop and 390-pixel mobile layouts passed inspection. The public source scan contains the same 11 reviewed upstream/test findings as the previously distributed source; all flagged lines match that prior revision.

Pebble release `ae1e506e1bcf485c8555a549` is **Draft 1.8.1** under the existing Signal Station listing. The Store-served PBW matches the hash above. Existing visibility, media, companion registration and published releases were preserved; **1.7.1 remains Published**. The sanitized receipt is `download-preview-2026-10-03.json`.

The release offers reviewed questions, one-use Home confirmation and explicitly selected local answer models. Nano needs the app visible; Gemma's optional download and full inference still need acceptance. The watch timestamp correction preserves unknown measurement times. See [local model checks](local-models.md) and [the exact watch preview](observation-preview-2026-10-03.md).

This delivery changes hosted previews and Store staging. It performs no new phone/watch installation and does not establish physical 2 SE acceptance. The installation hold in the separate watch descriptor and the existing stock pairing remain intact.
