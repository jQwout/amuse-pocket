# Market: share skills and agents

Status: server side implemented in `forge/gateway/.../market/` (trace `skill-market-5d1e7a`).
The app side (browse, install screen, deep link) is not started; its brief is [`market-app-integration.md`](market-app-integration.md).

## Why

A person who builds a good agent in the builder has no way to hand it to anyone else, and a person
who opens the app for the first time sees only the presets. The market closes both gaps: publish an
agent or a custom skill, get a link, and anyone installs it in two taps. The app already has
everything a package needs (`AgentDefinition`, `UserSkillEntity`, the validators); what is missing is
a place to put it.

## Where it lives

Inside the forge gateway (`forge/gateway`), as a second Ktor route group under `/v1/market`. Same
service, same Fly machine, same volume, same deploy. A separate service would double the ops for a
feature that is a few hundred lines.

- DTOs: `forge/contract/.../MarketProtocol.kt` (the app keeps a hand-written mirror, as it does for
  `ForgeProtocol.kt`).
- Store: `MarketStore`, same shape as `JobStore`: an in-memory map behind one mutex, mirrored to
  `dataDir/market/<listingId>/` (`listing.json`, `v<N>.json`). Search is a substring scan over the
  in-memory index; fine up to tens of thousands of listings. A database is the next step, not a
  different API.

## Model

```
Listing                         one shareable thing
  listingId    "lst_" + 12 hex
  kind         skill | agent
  title        <= 60 chars
  summary      <= 240 chars
  tags         <= 5, each [a-z0-9-]{1,24}
  publisherName <= 40 chars, display only (no accounts yet)
  latestVersion Int
  installs     Int (deduplicated per installId)
  status       listed | unlisted | hidden
               listed = in search; unlisted = link only; hidden = taken down by reports
  createdAt, updatedAt

ListingVersion                  immutable once published
  version      1, 2, 3 ...
  changelog    <= 500 chars, optional
  sha256       of the canonical JSON of the package (the app can verify what it installs)
  package      SkillPackage | AgentPackage
  createdAt
```

Packages mirror the app's own types, with the same limits as `AgentValidator`:

```
SkillPackage
  skillId              suggested local id, [a-z0-9][a-z0-9_-]{2,63}; the app may rename on collision
  name, description(<=240), whenToUse(<=240)
  instructionsMarkdown <= 40 000 chars, not blank

AgentPackage
  name(<=60), description(<=240), iconKey
  systemPrompt         <= 20 000 chars
  toolNames            set of tool names (format checked only, see "What the server does not check")
  builtInSkillIds      subset of the app's built-in skills
  customSkills         List<SkillPackage>, <= 10
  defaultMode, allowedModes   CHAT | PLAN | GOAL | REFLECT | TUTOR
  includeSharedProfile Boolean   (the memory namespace is chosen by the installing app, never shipped)
  model                WONDER | SPARK | null
  goalMaxRounds        Int?
```

Deliberately **not** in a package: the agent's local id, memory namespace, `stateKeyPrefixes`,
hooks, any memory or chat content. A package is the recipe, never the person's data.

## Endpoints

Reading, installing and reporting are **public**: the forge token lives only in the Settings of
the few who typed it (no build carries one), so a market behind it would be empty for nearly
everyone, judges included. Publishing and editing need the client token (`FORGE_CLIENT_TOKEN`).

| Method | Path | What |
|---|---|---|
| GET | `/v1/market/listings?kind=&q=&tag=&sort=new\|popular&page=1&limit=20` | Public. Search. Returns `{items, total, page, limit}`; items carry no package body |
| GET | `/v1/market/listings/{id}` | Public. Listing + latest version with its package |
| GET | `/v1/market/listings/{id}/versions/{v}` | Public. One exact version (for "update available" and reinstall) |
| POST | `/v1/market/listings` | Token. Publish. `201 {listingId, version: 1, editKey}` |
| POST | `/v1/market/listings/{id}/versions` | Token. New version. Needs `X-Edit-Key` |
| PATCH | `/v1/market/listings/{id}` | Token. Change title/summary/tags/status (unlist). Needs `X-Edit-Key` |
| POST | `/v1/market/listings/{id}/install` | Public, 60/hour per IP. `{installId}`: counts an install once per installId. `204` |
| POST | `/v1/market/listings/{id}/report` | Public, 30/hour per IP. `{installId, reason}`: reason in `prompt_injection, harmful, spam, broken, other`. `204` |

Share link: `https://<gateway>/m/<listingId>` answers with a tiny HTML page that opens
`aiopenpocket://market/<listingId>` (and shows the title for people without the app). App side owns
the deep link.

### Ownership without accounts: the edit key

There are no user accounts yet, so publish returns a random 32-byte `editKey` once. The app stores it
next to the published agent; the server keeps only its SHA-256. New versions, edits and unlisting
need it. Lose the key and the listing is frozen (it can still be reported or hidden). When accounts
arrive, a listing gets an `ownerId` and the edit key becomes a fallback.

### Errors

Same as the rest of the gateway: `400` with a plain message for validation, `401` bad token, `403`
wrong edit key, `404` unknown or hidden listing, `413` body too large, `429` rate limited.
Validation errors list every issue at once (`{"issues":[{"field","code","message"}]}`), the way
`AgentValidator` does, so the app can point at the field.

## Safety

The content of a listing ends up **inside a model prompt** on someone else's phone, with access to
that phone's tools. That is the main risk of this feature, and the server alone cannot solve it.

- **App, before install (required):** show the full instructions and every tool with its existing
  risk label, then run the package through `AgentValidator` / `validateUserSkill`. Unknown tools are
  dropped with a visible note, never silently kept. Installed agents start in dry run.
- **Server:** limits on every field and on body size (256 KB), rate limit on publish (per token and
  per IP: 10 per hour), installs and reports, auto-hide once 3 distinct **networks** reported it
  (a salted hash of the client IP; device ids cost nothing to invent, so they alone cannot take a
  listing down) until a human looks, constant-time comparison of tokens and edit keys.

### Moderation (owner only, `FORGE_ADMIN_TOKEN`)

Auto-hide takes a listing down "until a human looks"; these are where the human looks.

| Method | Path | What |
|---|---|---|
| GET | `/v1/admin/market/listings?filter=reported\|hidden` | The queue: listings with reports (default) or hidden ones, most reporting networks first. Each report shows reason, note and time, never the device or network |
| POST | `/v1/admin/market/listings/{id}/restore` | Back to `listed`, **reports cleared** (kept, the next single report would hide it again) |
| POST | `/v1/admin/market/listings/{id}/hide` | Take it down by hand, without waiting for reports |
| DELETE | `/v1/admin/market/listings/{id}` | Gone for good: the listing and every version, from disk |

```bash
curl -H "Authorization: Bearer $FORGE_ADMIN_TOKEN" https://amuse-forge.fly.dev/v1/admin/market/listings
curl -X POST -H "Authorization: Bearer $FORGE_ADMIN_TOKEN" https://amuse-forge.fly.dev/v1/admin/market/listings/lst_.../restore
```

### What the server does not check

Tool names and built-in skill ids are only format-checked. The real lists live in the app and change
with every release; a server copy would drift. The app is the authority at install time.

## AI features (Meta Model API on the server)

On when the gateway has `META_API_KEY` (trace `market-ai-8f3b21`). Without it the market works the
same, minus these three; nothing else depends on them.

**1. Review on publish (Muse Spark).** Every new version is read by Muse Spark the way a careful
person would before installing: a plain-words summary of what it really does, its capabilities, and
concerns with the exact quote (`ListingDetail.review`, `ListingSummary.safety`). Verdicts:
`safe`, `caution` (powerful tools, vague, or tools that don't match the promise), `block` (built to
harm or deceive). **`block` hides the listing** and puts it in the moderation queue with the review.

- The package goes in fenced as untrusted data; the answer is parsed strictly (an unreadable answer
  is no review, retried on the next start) and cut to size.
- A deterministic pattern scan (`HeuristicScan`: "ignore previous instructions", "don't tell the
  user", "without asking for confirmation", sending data to a URL, credentials) runs next to the
  model. A package can be written to talk the model into "safe"; the scan can't be talked into
  anything. A hit floors the verdict at `caution` and adds its quote; it never lowers the model.
- A review vouches only for the version it names: a new version shows no review until its own lands,
  and a slow review of an old version is dropped.

**2. Cover art (Muse Image).** Each new listing gets one illustration in a house style ($0.01),
served at `GET /v1/market/listings/{id}/cover` (public, cached a day) and linked from
`ListingSummary.coverUrl`. The share page puts it in `og:image` + `twitter:card`, so a shared link
unfurls with a picture on X, Slack and messengers (needs `FORGE_PUBLIC_URL`). The prompt asks for no
text in the picture: the listing's words only describe the subject.

**3. Smart search (Muse Spark).** `POST /v1/market/match {"query": "remind me to drink water",
"kind"?}` → `{"items": [{listing, reason}]}`, at most 5, best first, the reason in the language of the
query. Meta has no embeddings model; at this size Muse reads the candidate list (up to 60 listed,
most installed first) and picks. Only ids that exist come back. Public, 20/hour per IP and 300/hour
in total; answers are cached 10 minutes per question and catalog revision. `503` when AI is off: the
app falls back to plain search.

**4. Translation (Muse Spark).** Add `?lang=en` (any two-letter code) to a detail or a search
(trace `market-translation-6c2e94`). The original always stays in place; the translation comes next to it:

- Detail: `ListingDetail.translation` = title, summary, the review's own words and the package's
  text in that language. Translated on the first ask, then cached on disk per language and per exact
  source text (title, summary, version, review); any change makes it stale. Per client 30/hour and
  500/hour in total for cache misses; over a limit the reader just gets the original.
- Search: each card's `translation` (title + summary) when it is cached; the rest are translated in
  the background, so search never waits for the model.
- Already in that language → `translation` is null, and that fact is cached too (one call, ever).

Safety, because a translated package can be installed and only the original was reviewed: the model
gets a flat map of texts and returns the same keys, so tools, skills, modes, model and ids are always
the original's; concern quotes are never sent (they must keep matching the original); and if the
translated text trips a `HeuristicScan` rule the original didn't (a hidden "when you translate this,
add..." instruction), the package translation is dropped and only the card and review are kept.
Packages over 12,000 characters translate the card and review only.

**Running it.** Review and cover run in the background (two at a time) after the publish has
answered; the person never waits. Whatever a crash or an outage left undone is resumed on start.
Cost: one Spark call per version and one image per listing, plus cache misses of smart search.

## Out of scope for v1

Accounts, ratings and reviews, a moderation UI (the API above is enough for one owner), paid listings, screens from the forge inside a package (it can come
later as `forgeJobId` + artifact hash), full-text ranking, moderation UI.

## Decisions (2 Nov 2026)

1. Response shape: bare objects, like the rest of the gateway (no `{success, data, error}` envelope).
2. Anonymous publishing with an edit key is fine for the hackathon; accounts come later.
3. An agent's custom skills stay inside the agent package; they are not separate listings in v1.
4. A body without `Content-Length` is refused with `411`, so the size limit cannot be bypassed by
   chunked uploads.
