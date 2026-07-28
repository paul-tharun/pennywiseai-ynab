# PennyWise → YNAB — UI Redesign Design

**Date:** 2026-07-29
**Status:** Approved (design), pending implementation plan
**Scope:** Visual + interaction redesign of the existing Compose UI. No changes to data, capture pipeline, or YNAB client.

## Context

The app captures bank-transaction SMS and posts them to YNAB. The functional UI (Plan 6) works but is unstyled Material 3 defaults: no visual hierarchy, status is invisible (POSTED / FAILED / UNROUTED all render as identical grey text), Settings is a single undifferentiated scroll, there are no empty/loading states, and the landing screen leads with dead vertical space before any content.

This redesign is **polish within Material 3** for a **personal tool** — "clean and pleasant is enough." No brand identity, no custom palette beyond what's needed for status semantics. Dynamic color (Material You) is retained.

## Framing decisions (from brainstorming)

- **Ambition:** private tool; clean and pleasant, not publish-grade branding.
- **Usage:** occasional check-ins; the app mostly runs invisibly.
- **Failures/unrouted already notify** the user out-of-band, so the home screen can be **calm** — it confirms health at a glance rather than shouting.
- **Density:** the user prefers compact layouts. The route-editor/onboarding compact scale is the target rhythm; other screens harmonize toward it.

## Design principles

1. **Answer "is everything flowing?" in one glance** — the top of Home carries meaningful status, never dead space.
2. **Color means "there is a problem."** Neutral/grey is the default; green/amber/red are reserved for status semantics, not decoration.
3. **One calm surface per screen** — prefer flat lists with thin dividers over stacks of filled cards.
4. **Problems are one tap from their fix** — tapping a problem count filters to it; each problem row carries its own resolve action.
5. **Compact but breathable** — tight spacing rhythm, but consistent.

## Cross-cutting systems

### Status color semantics

Five real statuses (`MessageStatus`) map to four semantic colors:

| Status | Semantic | Color | On Home? |
|---|---|---|---|
| `POSTED` | success | green | yes |
| `SKIPPED_UNROUTED` | warning (actionable) | amber | yes — "Unrouted" |
| `SKIPPED_CURRENCY_MISMATCH` | warning (actionable) | amber | yes (in list) |
| `FAILED` | error | red | yes — "Failed" |
| `SKIPPED_NON_TRANSACTION` | neutral/noise | grey | hidden by default; reachable via filter |

**Implementation note:** M3's baseline/dynamic scheme has `error` but no `success`/`warning` role. Define semantic colors (success, warning + their containers) as theme extensions that are **legible against both dynamic light and dynamic dark schemes** — do not hardcode the hex values used in the mockups (those were illustrative). A small `StatusColors` holder provided via the theme is the expected shape.

### Navigation

Three bottom-nav tabs retained, first tab renamed **History → Home**: `Home · Import · Settings`. The rule editor remains a pushed screen (existing hand-rolled two-level stack). No new nav dependency.

### Empty & loading states

Every list/async surface gets a real state instead of blank space:
- **Home, no messages yet:** friendly "No transactions yet" empty state.
- **Token validating:** inline spinner + "Validating…" (replaces bare text).
- **Import running:** in-app progress (see Import).

## Per-screen designs

Mockups are archived under `.superpowers/brainstorm/` (gitignored). Filenames noted per screen.

### 1. Home (`home-final-v2.html`)

Replaces the flat History list.

- **Header (replaces the "PennyWise" wordmark):** a small ₹ tile + **"Last transaction · 2 minutes ago"** with a secondary "Today at 9:12 AM". Tracks time since the most recent *processed* message (reflects spending activity, not a sync cycle — the app is event-driven, so there is no "sync"). A ⟳ button triggers an on-demand inbox re-scan.
- **Stat strip:** three tappable tiles — **Posted / Failed / Unrouted**. Numbers sized modestly. Tapping a tile filters the list below to that status; an active tile shows an outline, the list header switches to e.g. "Unrouted · 1" with a "Show all ✕" clear. (Failed/Unrouted tiles use error/warning container colors.)
- **Recent list:** rows of `Bank ·last4` / time / amount / status pill. Filtered rows surface their inline fix action (e.g. "Map this card →" for unrouted).
- App health lives in the tiles, not in the header.

### 2. Settings (`settings-a-v2.html`)

Replaces the single-scroll junk drawer. One calm surface, tidy titled sections:

- **YNAB:** when a token is valid, a quiet **"Connected · 2 budgets · 8 accounts"** row (green check) with a single **⋮ overflow** holding Refresh / Replace token / Disconnect. The password field is **not** shown by default — it appears only on "Replace token". (Onboarding still shows the field directly.)
- **Routes** (+ Add): one flat list, **neutral grey avatars**, thin dividers. `Bank ·last4 → Budget / Account`. A broken route shows a small red hint line ("Target account was deleted · tap to fix") instead of a loud badge.
- **Needs routing:** an in-list subheader (not a separate card) listing unrouted cards with a "Map →" action.

### 3. Route editor (`route-editor-v2.html`)

Replaces text-fields + chip-grids. Compact scale.

- Top app bar: `← New route / Edit route` with a **Save** action, disabled until valid.
- **CARD** section: Bank name field, Last 4 field, helper "Blank = match any card from this bank."
- **SEND TO** section: **Budget** and **Account** dropdowns (scale better than chip grids). Picking a budget repopulates accounts. Currency is inferred from the budget — no separate field.
- **Live preview** card: `SBI ·7756 → Personal / Everyday (₹)`.
- **Retro-import dialog** (unchanged behavior): after saving a route for a previously-unrouted card, prompt "Import past transactions?" → Import / Not now.

### 4. Onboarding (`onboarding-single-v2.html`)

Replaces the three stacked text steps. **Single dense screen**, no wizard:

- Header "Set up PennyWise" + one-line intro.
- A **three-item checklist** in a fixed-height scrolling viewport (thin scrollbar when it overflows); items fill in checkmarks as completed:
  1. **Allow reading bank texts** (SMS + notifications; states the on-device reassurance).
  2. **Connect YNAB** — token field, inline validation ("✓ Valid · N budgets, N accounts"), "Where do I find this?" link.
  3. **Map your first card** — marked **OPTIONAL**; compact Bank / Last 4 / Send-to.
- **"Start capturing"** CTA **pinned** at the bottom; enabled once YNAB is connected. Step 3 is skippable (suggestions catch new cards later).

### 5. Import (`import.html`)

Replaces the bare `DateRangePicker`.

- Title + one-line explanation ("Only messages matching a route are imported").
- **HOW FAR BACK:** quick-range chips — **7 / 30 / 90 days / Custom…** ("Custom…" opens the existing calendar). Selected range shown in a summary card with a "Change" affordance.
- Reassurance: "Already-imported transactions are skipped, so running this twice is safe."
- CTA self-labels: "Import 30 days".
- **Running state (in-app, not just a notification):** progress bar + live count ("148 of ~240 · 31 posted, 2 need routing"), **Cancel import**, and a note that it keeps running if you leave.
  - **Fallback:** if the backfill worker can't cheaply produce a total, use an **indeterminate** bar with a running tally ("31 posted so far") instead of "N of ~M".

## Non-goals / YAGNI

- No custom brand palette, logo, or illustration set.
- No `androidx.navigation` dependency.
- No changes to the capture pipeline, Room schema, or YNAB client.
- No charts/analytics/spending insights — this app confirms routing, it is not a budgeting dashboard.

## Open implementation notes

- Semantic status colors must be derived to work against dynamic light/dark; mockup hexes are illustrative only.
- Currency-mismatch shares the amber "warning" treatment in the list but is **not** its own stat tile (rare edge case); confirm this is acceptable or promote it during implementation.
- Import determinacy depends on the worker's ability to report a total (fallback specified above).
- Density: the compact route-editor/onboarding scale is the reference; Home/Settings should be nudged toward it during implementation for a consistent rhythm.
