# Remote config

What the app can change without a release: the Meta model catalog, kill switches, a banner, and
"please update". Served by the forge gateway (`forge/gateway/.../config/`, trace
`remote-config-2403cd`). The server side is done; the app side is section 3.

## 1. Why it is public

`GET /v1/config` needs no token. The forge token is typed by the person in Settings and no build
carries one (T4), so a token-gated config would reach almost nobody, judges included. Nothing in it
is secret. Writing needs a third token, `FORGE_ADMIN_TOKEN`, that only the owner holds.

## 2. For the owner: changing it

```bash
fly secrets set FORGE_ADMIN_TOKEN=$(openssl rand -hex 32)    # once

curl -X PUT https://amuse-forge.fly.dev/v1/admin/config \
  -H "Authorization: Bearer $FORGE_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @config.json
```

`config.json` (every field optional; what you leave out keeps the app's own default):

```json
{
  "configVersion": 2,
  "minAppVersionCode": 1,
  "latestAppVersionCode": 1,
  "updateUrl": "https://github.com/jQwout/amuse-pocket/releases",
  "models": [
    { "name": "muse-spark-1.3", "priority": 1, "maxTokens": 16000, "reasoning": true,
      "supportsTools": true, "inputModalities": ["text", "image", "video", "audio", "file"],
      "price": { "input": 1.25, "cachedInput": 0.15, "output": 4.25 } },
    { "name": "muse-wonder-1.0", "priority": 2, "maxTokens": 16000, "reasoning": true,
      "supportsTools": true, "inputModalities": ["text", "image", "video", "audio", "file"],
      "price": { "input": 4.00, "cachedInput": 0.20, "output": 20.00 } }
  ],
  "features": { "market": true, "forge": true, "web_search": true },
  "notice": { "id": "judging-2026", "level": "info",
              "text": { "en": "Thanks for trying AI Open Pocket!", "ru": "<the same, in Russian>" } }
}
```

Rules the server enforces (a bad config reaches every install within minutes, so they are strict):

- `configVersion` must be **above** the current one, or `409`. Two people editing at once cannot
  silently undo each other; re-read with `GET /v1/config` and bump.
- `models`: `null` keeps the bundled catalog; an empty list is refused. No `-contributor` model, no
  duplicate names or priorities, `maxTokens` 1..128000, never `temperature` and `topP` together
  (Meta answers 400), a price on every model (the cost card needs it).
- Links are `https` only. A notice needs English text (the fallback), 280 characters max per language.
- Feature keys are `snake_case`. Validation errors come back as `400 {"issues":[...]}`, all at once.

The config lives on the volume (`/data/config/remote-config.json`). If that file is ever unreadable,
the gateway serves the default (logged as an error), so the apps fall back to their assets.

## 3. For the app

**Fetch** `GET /v1/config` (no token) on start and when the app returns to the foreground, at most
every 5 minutes (the response says `Cache-Control: public, max-age=300`). Send `If-None-Match` with
the last `ETag`; `304` means nothing changed. Use the gateway address from Settings, or the default
`https://amuse-forge.fly.dev` when it is empty, since this call needs no token.

**Cache** the last good body in DataStore with its `configVersion`. Ignore a config whose
`configVersion` is lower than the cached one. On any error (offline, 5xx, bad JSON) keep the cache,
or the assets if there is none. **A missing or broken config must never block the app.**

**Apply:**

| Field | What the app does |
|---|---|
| `models` | Not null: build `ModelCatalog` from it instead of `models_meta.json` (`maxTokens` → `maxTokens`, `inputModalities`, `supportsTools`, etc.; the asset is snake_case, the wire is camelCase). Still drop anything `trainsOnUserData`. Use `price` in `metaListPriceUsd` for a model missing from `META_PRICES`. |
| `features` | `false` hides the entry point (market screen, forge section in the agent card, `web_search` tool). A missing key = on. |
| `minAppVersionCode` | `BuildConfig.VERSION_CODE` below it → a blocking "Please update" with `updateUrl`. |
| `latestAppVersionCode` | Below it → a dismissible "Update available". |
| `notice` | A banner on Home in the UI language (fall back to `en`). Remember closed `notice.id`s so it doesn't come back. |

**Test**: the mapping (models → catalog, unknown key ignored, contributor dropped), the version rule
(older config ignored), and that a failed fetch leaves the app on assets.
