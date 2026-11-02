# Market: what the app has to build

For the Android side. The server is done (`forge/gateway/.../market/`, trace `skill-market-5d1e7a`);
the reasons behind its shape are in [`market-api.md`](market-api.md). This file is only what you
need to wire the app to it: the wire format, how a package maps onto the app's own types, and the
rules that are the app's job because the server cannot enforce them.

## 1. Reuse what the forge already has

The market is served by the same gateway as cloud-forged screens, so do not add a second config:

- **Address and token**: `ForgeGatewayConfig` from `ScreenForgeClient.kt` (Settings already has the
  fields; the debug default `10.0.2.2:8080` works for the market too). Browsing must work with an
  empty token: only "Publish" asks for one.
- **DTO mirror**: the app can't depend on `forge/contract` (separate Gradle build, Kotlin 2.0.21),
  so mirror `MarketProtocol.kt` by hand next to `agent/core/forge/ForgeProtocol.kt`, the same way.
  Enum values travel as the lowercase strings shown below, except `MarketAgentMode` (uppercase,
  same names as the app's `AgentMode`).
- **Client**: a `MarketClient` shaped like `ScreenForgeClient` (`call { cfg -> ... }`,
  `ForgeResult`, bearer header, `tr(...)` failure texts).

## 2. Endpoints

Search, open, versions, install and report need **no token** (most installs have none); publish,
new version and edit need `Authorization: Bearer <forge token>`. With no gateway address in
Settings, use the default `https://amuse-forge.fly.dev` for the public calls. Bodies are JSON; every
`POST`/`PATCH` must send `Content-Length` (Ktor does when you `setBody` a serializable object).

| Call | Request | Success |
|---|---|---|
| Search | `GET /v1/market/listings?kind=skill\|agent&q=&tag=&sort=new\|popular&page=1&limit=20` | `200 ListingPage` |
| Open | `GET /v1/market/listings/{id}` | `200 ListingDetail` (latest version) |
| One version | `GET /v1/market/listings/{id}/versions/{n}` | `200 MarketVersionView` |
| Publish | `POST /v1/market/listings` + `PublishListingRequest` | `201 PublishListingResponse` |
| New version | `POST /v1/market/listings/{id}/versions` + `X-Edit-Key` + `PublishVersionRequest` | `201 ListingDetail` |
| Edit / unlist | `PATCH /v1/market/listings/{id}` + `X-Edit-Key` + `UpdateListingRequest` | `200 ListingDetail` |
| Count install | `POST /v1/market/listings/{id}/install` + `{"installId"}` | `204` |
| Report | `POST /v1/market/listings/{id}/report` + `{"installId","reason","note?"}` | `204` |
| Share page | `GET /m/{id}` (no token) | `200` HTML |

`limit` is capped at 50. Search returns only `listed` listings; `unlisted` ones open by id/link.

### Examples

Publish an agent:

```json
POST /v1/market/listings
{
  "kind": "agent",
  "title": "Hydration buddy",
  "summary": "An agent that keeps you hydrated",
  "tags": ["health", "habits"],
  "publisherName": "Uros",
  "changelog": null,
  "agent": {
    "name": "Hydration buddy",
    "description": "Keeps me drinking water",
    "iconKey": "water",
    "systemPrompt": "You help the person drink 8 glasses a day.",
    "toolNames": ["memory_tool", "notifications"],
    "builtInSkillIds": ["fitness"],
    "customSkills": [{
      "skillId": "water-coach",
      "name": "Water coach",
      "description": "Tracks glasses of water",
      "whenToUse": "When the person mentions drinking",
      "instructionsMarkdown": "# Water coach\n..."
    }],
    "defaultMode": "CHAT",
    "allowedModes": ["CHAT", "PLAN"],
    "includeSharedProfile": false,
    "model": "SPARK",
    "goalMaxRounds": 3
  }
}
```

```json
201
{ "listingId": "lst_54b6a169976f", "version": 1, "editKey": "f7rsQ7DPHUtBmkyn0L1D8Ah7fYAUvMHfket-eemSSvc" }
```

A skill listing is the same with `"kind": "skill"` and a `"skill": { ...SkillPackage }` instead of
`"agent"`. Exactly one of the two, matching `kind`.

Open a listing:

```json
GET /v1/market/listings/lst_54b6a169976f
{
  "listing": {
    "listingId": "lst_54b6a169976f", "kind": "agent", "title": "Hydration buddy",
    "summary": "...", "tags": ["habits", "health"], "publisherName": "Uros",
    "latestVersion": 1, "installs": 0, "status": "listed",
    "createdAt": 1793610628532, "updatedAt": 1793610628532
  },
  "version": {
    "version": 1, "changelog": null,
    "sha256": "9c1e...",
    "skill": null,
    "agent": { ...AgentPackage, lists sorted },
    "createdAt": 1793610628532
  }
}
```

The server sorts and dedupes every list in a package (and trims strings) before storing, so what
you get back may be ordered differently from what you sent.

## 3. Errors

| Status | Body | Show the person |
|---|---|---|
| `400` | `{"issues":[{"field","code","message"}]}` | Point at the fields; `field` is a path like `agent.customSkills[0].instructionsMarkdown` |
| `401` | text | Only on publish/edit: no token or a wrong one; send the person to Settings |
| `403` | text | Only on edit calls: this phone doesn't hold the edit key |
| `404` | text | The listing was removed or never existed (hidden listings are 404 too) |
| `411` / `413` | text | Package too big (limit 256 KB) |
| `429` | text | "Try again later" (publish: 10/hour, install: 60/hour, report: 30/hour per IP) |

`code` values you may want to map to your own text: `blank`, `too_long`, `bad_id`, `bad_tag`,
`too_many_tags`, `package_kind_mismatch`, `bad_tool_name`, `too_many`, `too_many_skills`,
`duplicate_skill_id`, `default_mode_not_allowed`, `unknown_model`, `out_of_range`,
`status_not_allowed`, `bad_query`. Limits are the app's own (`AgentValidator`): if the app's validator
passes, the server will too.

## 4. Installing: package → app types

Model it on `UserAgentRepository.clonePreset`: the installed agent is a brand new USER agent with
fresh ids, exactly like a fork.

**AgentPackage → AgentDefinition**

| Package | AgentDefinition | Note |
|---|---|---|
| — | `id` | New: `<slug of name>-<8 random>` |
| — | `source` | `USER` |
| — | `status` | `DRAFT` until the person confirms (section 5) |
| — | `configVersion` | `0` |
| `name`, `description`, `iconKey`, `systemPrompt` | same | |
| `toolNames` | `toolNames` | **Drop any name not in `AgentValidator.KNOWN_TOOL_NAMES`** and tell the person which |
| `builtInSkillIds` | part of `skillIds` | Drop any not in `KNOWN_SKILL_IDS` |
| `customSkills` | the rest of `skillIds` + rows | See below |
| `defaultMode`, `allowedModes` | same (`AgentMode.valueOf`) | |
| `includeSharedProfile` | `memoryPolicy.includeSharedProfile` | |
| — | `memoryPolicy.namespace` | `"agent:$newId:"`, never from the package |
| `model` | `model` | Unknown value → `null` |
| `goalMaxRounds` | `goalMaxRounds` | |
| — | `hooks`, `stateKeyPrefixes`, `allowedWidgetTemplates` | Empty / the builder's defaults |

**SkillPackage → UserSkillEntity**: `id` = `skillId`, plus `-<same 8 random>` if that id is taken or
collides with a built-in; `agentId` = the new agent id; `name`, `description`, `whenToUse`,
`instructionsMarkdown` as is; `createdAt`/`updatedAt` = now. Put the final ids in the agent's
`skillIds`.

A **skill listing** on its own installs into an agent the person picks (it has to belong to one:
`UserSkillEntity.agentId`). The simplest UI is "Add to agent…" with the person's agents.

Then save it all through `saveDraft` / `publish`, so the app's own validator runs on it exactly as
on a hand-built agent.

**Publishing (the reverse)** is the same table read right to left; skip the rows marked "—".
Store the returned `editKey` next to the agent (it is the only way to publish version 2), together
with the `listingId`. Losing it freezes the listing; it is a secret, so keep it with the same care
as the forge token.

**Hash check (optional, recommended):** encode the received package with
`Json { encodeDefaults = true; explicitNulls = true }` compact, SHA-256 it, compare with
`version.sha256`. Field order must match `MarketProtocol.kt`.

## 4b. AI fields (when the server has them)

- `listing.coverUrl` (relative to the gateway): show it as the card image; no cover yet → the
  agent's icon. `listing.safety`: a small badge (`safe` green, `caution` amber; `block` never reaches
  you, those are hidden). Null = "not reviewed yet", say so, don't show it as safe.
- `detail.review` on the install screen, **above** the raw prompt: `summary` as the first line people
  read, `capabilities` as a list, each concern with its `quote` highlighted in the prompt below. It
  helps the person read; it does not replace showing everything (section 5).
- Translation: send `?lang=<UI language>` on search and detail. Show `listing.translation` /
  `detail.translation` when present, with a small "Translated · Show original" toggle; never hide
  that it is a machine translation. On the install screen let the person pick which one to install
  (`translation.agent` / `.skill` when not null; it was built from the original's structure). The
  `version.sha256` check applies to the original only.
- Smart search: a search box that sends `POST /v1/market/match {"query", "kind"?}` and shows each
  item's `reason` under the card. On `503`/`429`/error fall back to `GET /v1/market/listings?q=`.

## 5. Rules that are the app's job

The server only checks shapes and sizes. What follows has to happen in the app, or the market is a
way to put a stranger's prompt in charge of someone's phone.

1. **Before install, show everything**: the full `systemPrompt`, every custom skill's
   `instructionsMarkdown`, and every tool with its existing risk label (the builder already shows
   them). No "install" from the list view; only from this screen.
2. **Validate**: drop unknown tools/skills (with a visible note), then run `AgentValidator` and
   `validateUserSkill`. Any error → don't install, show it.
3. **Start safe**: the installed agent opens in dry run, so the first actions are simulated, and
   anything destructive or external still waits for a yes (as for every agent).
4. **Report button** on the detail and install screens (`prompt_injection` first in the list).
5. **installId**: one random id per install of the app (`UUID`, stored in DataStore), sent with
   install and report. It is not tied to the person; don't derive it from anything that is.

## 6. Deep link

`aiopenpocket://market/{listingId}` (the share page links to it). Add an intent filter for scheme
`aiopenpocket`, host `market`, and route it to the listing detail. The id format is
`lst_` + 12 hex; ignore anything else.

## 7. Checklist

- [ ] `MarketProtocol` mirror + `MarketClient`
- [ ] Market screen: search, kind filter, list, detail
- [ ] Install screen (section 5) and package → `AgentDefinition`/`UserSkillEntity` mapping
- [ ] "Publish to market" on a published user agent; keep `listingId` + `editKey`
- [ ] "Publish new version" when the agent changed since its listing
- [ ] Deep link
- [ ] installId
- [ ] Tests: the mapping (both directions, unknown tools dropped, id collisions renamed) and the
      client against `MockEngine`
