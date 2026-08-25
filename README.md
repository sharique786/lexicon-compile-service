# Lexicon Compile Service

Compiles compliance lexicon terms — written in a custom operator language,
or supplied as raw regex — into Hyperscan-validated PCRE patterns, and
(via `/compile/bundle`) into a single combined Hyperscan database ready for
the Lexicon Scan Engine to load and scan against directly.

Base package: `com.db.macs3.ecomms.spectre`
Stack: Spring Boot 4, Jakarta EE 10, **JDK 21** (JDK 21 virtual threads for
request handling — a separate, newer stack from the Lexicon Scan Engine's
Java 11 + Spring Boot 2.7.18, which is a distinct Maven project).

---

## What changed in this revision

The service previously had two separate request shapes (`CompileRequest`
for `/compile`/`/compile/csv`, `TypedCompileRequest` for `/compile/bundle`)
and, for complex terms, up to four separate boolean/list fields describing
decomposition state. Both were sources of real ambiguity for callers and,
for `/compile/bundle` specifically, meant the produced `.hdb` file wasn't
fully self-sufficient — a consumer needed the JSON response to know which
Hyperscan expression id to watch for a given term.

- **One request type.** `CompileRequest` is gone. `TypedCompileRequest` is
  now used by `/compile`, `/compile/csv`, and `/compile/bundle` alike.
- **`TermCompilationResult.translatedPattern` and `.exclusionPattern` are
  always lists** — one entry for a term simple enough to compile as a
  single pattern, several when it needed decomposition. There is no
  separate "was this decomposed" boolean any more.
- **A term's reportable Hyperscan expression id is always its own term
  number** — see "The expression id scheme" below. This is what actually
  makes a `/compile/bundle` `.hdb` file self-sufficient: a consumer that
  knows a term's number from the lexicon rule definition can predict its
  expression id with no JSON lookup at all.
- **`HyperscanCombinationHandler`** is a new, dedicated class isolating
  every QUIET/COMBINATION decision — separate from `HyperscanCompiler`
  (general Hyperscan validation, no opinion about which terms need a
  combination) and `LexiconCompileBundleService` (request orchestration,
  no opinion about expression construction).

---

## The operator language

Every Natural Language term description is parsed through a three-stage
pipeline: `Tokenizer` → `ExpressionParser` (builds an `Ast`) →
`PatternCodeGenerator` (emits Hyperscan-compatible PCRE). Regex-type terms
(see below) skip this entirely.

### Operators

| Operator | Meaning | Example |
|---|---|---|
| `OR` | Any operand matches | `price OR spread` |
| `AND` | All operands co-occur anywhere in the message, in any order | `insider AND announcement AND price` |
| `AND NOT` | Left side matches AND the right side does not — see "AND NOT" below | `insider AND NOT disclosed` |
| `NEAR{n}` | Operands within `n` words of each other, **either order** | `(crap OR bad) NEAR{3} (bonus OR comp)` |
| `FOLLOWEDBY{n}` | Left operand, then right operand, within `n` words, **left-to-right order only** | `don't FOLLOWEDBY{3} compliance` |
| `*` (suffix/prefix) | Wildcard — `chimp*` → `chimp\S*`, `*handler` → `\S*handler` | |
| `?` | Always a literal character, never a live regex quantifier — `he?d kill` matches the literal text `he?d kill` | |
| `"..."` | Quoted phrase — content taken literally, including any `*` inside (not wildcard-expanded) | `"do not share"` |

### Bracket resolution and phrase wrapping

Brackets resolve innermost-first, at any nesting depth, including
redundant wrapping (`(((me) OR (cking)))` resolves the same as `me OR cking`).
A multi-word phrase does **not** strictly require explicit parentheses —
an unwrapped phrase like `insider trading OR market manipulation` is
accepted as an implicit phrase group — but wrapping is still recommended
for clarity in anything beyond the simplest two-alternative case.

### `AND`: corrected co-occurrence semantics

`A AND B AND C` compiles directly into a single self-contained pattern
covering every ordering permutation of the operands — it needs no
exclusion side and no post-filter metadata. `requiresExclusionCheck` is
always `false` for a plain AND term.

### `AND NOT`: a two-list contract, not a single regex

Hyperscan supports no negative lookaround at all, so "A but not B" cannot
be expressed as one pattern. `AND NOT` compiles into **two** independently
Hyperscan-valid pattern lists:

- `translatedPattern` — the required side (A)
- `exclusionPattern` — the excluded side (B), non-null only when
  `requiresExclusionCheck` is `true`

**What a caller does with these two lists differs by endpoint:**

- **`/compile` and `/compile/csv`** — the caller (e.g. the Lexicon Scanner
  Service) compiles every pattern in both lists itself and combines the
  boolean results in application code: matched iff every entry of
  `translatedPattern` matches, AND the excluded condition (every entry of
  `exclusionPattern` found) is *not* fully satisfied.
- **`/compile/bundle`** — every pattern in both lists is compiled into the
  combined database as QUIET sub-expressions feeding a native Hyperscan
  **logical combination** (`HS_FLAG_COMBINATION`). Hyperscan itself
  evaluates the full boolean condition during the scan; no application-level
  combination step is needed downstream. See `HyperscanCombinationHandler`.

Chained exclusions (`A AND NOT B AND NOT C`) are combined into one excluded
side (`B OR C`) before translation, so they still produce exactly one
combination expression, not a chain of nested ones (see "Why exactly one
combination expression per term" below).

---

## Complex terms: decomposition, not rejection

### Why "Pattern is too large" isn't about string length

Hyperscan's "Pattern is too large" is a documented consequence of
*compiled automaton state count*, not raw pattern string length. A 190-character
pattern can trigger it while much longer patterns compile fine. Empirically,
the primary driver is **nesting depth**, not OR-branch width: a single-level
`NEAR` over wide OR groups (18 and 8 alternatives, no nesting) compiled
successfully, while a term with two *nested* `FOLLOWEDBY` operators over much
narrower 4-alternative groups was rejected — one proximity operator whose
operand is itself a proximity operator forces the automaton to track two
independent gap-counters simultaneously, which is a multiplicative blow-up,
not additive.

### `PatternComplexityAnalyzer`

Estimates this risk *before* Hyperscan ever sees the pattern, applying an
explicit nesting penalty — a NEAR/FOLLOWEDBY whose operand is itself a
NEAR/FOLLOWEDBY multiplies the expression's complexity score by
`(nestedDistance + 1)`. This is a calibrated heuristic (checked against
real Hyperscan pass/fail outcomes), not a guarantee — a term can still be
rejected by Hyperscan itself even after passing this pre-check, or vice
versa; both paths are handled.

### Decomposition

When a term is judged too complex for one pattern, `PatternDecomposer`
breaks it into independent leaf patterns instead of failing the term
outright. `translatedPattern` (or `exclusionPattern`, for a decomposed
excluded side) then has multiple entries — see `TermCompilationResult`
class Javadoc.

**This is a real precision trade-off, always flagged in `warnings`.**
Decomposition discards the NEAR/FOLLOWEDBY distance and ordering
constraint *between* leaf patterns: the leaves are combined with pure
boolean AND ("all of these appear somewhere in the message"), independently
of each other. It does not discard the gap width itself, though — each
leaf after the first still carries its originating NEAR/FOLLOWEDBY node's
own gap fragment as a literal prefix in its own pattern text (so it can
never match with nothing preceding it), just no longer anchored to the
specific leaf that preceded it in the original term. A term originally
meaning "these three things, in this order, this close together" becomes
"these three things, each somewhere in this message, independently of one
another" once decomposed. No caller can silently treat a decomposed match
as a genuine proximity match, because `warnings` always carries an
explicit entry whenever this trade-off applied to either side.

---

## Hyperscan flags

Which `ExpressionFlag`s an expression gets is decided by which of **three
cases** it falls into — never by the term's script content alone any more
(content still narrows UTF8/UCP within case 3 — see below):

| Case | Method | Flags |
|---|---|---|
| AND NOT — every required/excluded pattern (regardless of decomposition on either side) | `HyperscanCompiler.toAndNotExpressionFlags()` | `CASELESS` only |
| Pure decomposition leaf, no AND NOT (feeds a native `COMBINATION`) | `HyperscanCompiler.toSubExpressionFlags()` | `CASELESS`, `QUIET` only |
| Simple, single-pattern, non-AND-NOT PASS term (also the general validation flag set `HyperscanCompiler.validate()` always uses) | `HyperscanCompiler.toExpressionFlags(bitmask)` | `CASELESS`, `DOTALL`, `SOM_LEFTMOST` always, plus `UTF8`/`UCP` when `bitmask` indicates non-Latin content |
| The one combination expression per decomposed (non-AND-NOT) term | `HyperscanCompiler.toCombinationExpressionFlags()` | `COMBINATION` only |

**UTF8/UCP are conditional in the "simple term" case only, deliberately —
this was tried unconditionally and reverted.** Forcing UTF8+UCP onto every
expression regardless of content caused two confirmed regressions: (1)
Hyperscan rejects `\b` (word boundary) when UCP is active ("`\b` unsupported
in UCP mode"), breaking any caller-supplied Regex-type term using it; (2) UCP
mode measurably slows Hyperscan compilation even for plain-ASCII patterns
(~15x in this project's own performance test). **Without UCP, `\S+` only
matches ASCII non-whitespace and silently skips Arabic, Hebrew, CJK, and
other non-Latin characters** — this is why any script needing UTF8 also
needs UCP, not UTF8 alone.

The AND NOT and pure-decomposition-leaf cases carry NO conditional bits at
all (not even for non-Latin content) — this is intentional, not an
oversight; see each method's Javadoc.

### SOM_LEFTMOST: AND NOT no longer gets it, on purpose

Earlier revisions gave every plain (non-QUIET) expression SOM_LEFTMOST,
including AND NOT's required/excluded patterns. AND NOT is now scoped to
`CASELESS` only (see table above) — no SOM_LEFTMOST, even though it would be
structurally SAFE there (AND NOT patterns are plain, never QUIET, so the
QUIET+SOM_LEFTMOST incompatibility below doesn't apply to them). This is a
deliberate narrowing of the AND NOT case, not a safety-driven omission.

### SOM_LEFTMOST and the QUIET incompatibility

**Confirmed by a real Hyperscan compile-time error, and corroborated by
Hyperscan's own documentation:** `HS_FLAG_SOM_LEFTMOST` cannot be combined
with `HS_FLAG_QUIET`. (Hyperscan's docs also list `HS_FLAG_SINGLEMATCH` and
`HS_FLAG_PREFILTER` as incompatible with SOM under "Incompatible features";
QUIET's incompatibility is confirmed directly by the error message itself
rather than by that particular doc passage, which doesn't name QUIET
explicitly.)

- The **simple-term expression** (never QUIET, never COMBINATION) always
  gets SOM_LEFTMOST — safe, since it carries neither of the incompatible flags.
- Every **QUIET sub-expression** (a pure-decomposition leaf) never gets
  SOM_LEFTMOST — `HyperscanCompiler.toSubExpressionFlags` never adds it.
- **AND NOT's required/excluded patterns** never get it either — not because
  it would be unsafe (it wouldn't — see above), but because the AND NOT case
  is scoped to `CASELESS` only.
- A **COMBINATION expression** never gets it either — Hyperscan ignores all
  flags on a combination expression except SINGLEMATCH and QUIET anyway
  (see above), so it would have no effect regardless.

There used to be a `trackMatchPosition` field on `TypedCompileRequest`
letting a caller opt out of SOM_LEFTMOST for an entire request. It has been
**removed entirely** — it was solving the wrong problem. The real issue was
never "does this caller want match positions"; it was that SOM_LEFTMOST was
being applied unconditionally to every expression, including QUIET ones,
which is invalid regardless of any caller's preference. Whether an
expression may safely carry SOM_LEFTMOST — and, independently, whether its
flag case is even scoped to include it — is a structural fact about which
of the three cases above it falls into, not a preference a caller should be
choosing per-request.

### The COMBINATION flag constraint

A Hyperscan expression flagged `COMBINATION` may only additionally carry
`QUIET` and/or `SINGLEMATCH` — never `CASELESS`, `UTF8`, `UCP`, `DOTALL`,
or `SOM_LEFTMOST`, which apply only to the plain sub-expressions being
combined, not to the boolean formula referencing their ids.
`HyperscanCompiler.toCombinationExpressionFlags()` always returns exactly
`{COMBINATION}` and nothing else, so this constraint holds automatically
everywhere a combination expression is built.

### Why exactly one combination expression per term

Hyperscan's documented logical-combination syntax and Intel's own example
(`"(101 & 102 & 103) | (104 & !105)"`) show a combination formula
referencing plain pattern ids directly, with arbitrary boolean structure —
but never one combination expression referencing *another combination
expression's* id. Rather than rely on nested combinations working
(unverified against a live cluster in this project's development
environment), `HyperscanCombinationHandler` builds at most one combination
expression per term, whose formula references only plain (non-combination)
ids: every decomposition leaf, the required pattern if not decomposed, and
the exclusion pattern if not decomposed.

Correctness for a **decomposed excluded side** requires De Morgan's law,
applied explicitly:

```
NOT(E1 AND E2 AND ... AND Em)  =  (NOT E1) OR (NOT E2) OR ... OR (NOT Em)
```

Decomposition combines leaves with AND ("all parts found"), so negating
that condition is an OR of negations, not a naive AND of negations (which
would incorrectly exclude a term even when only *one* decomposed exclusion
leaf was absent, rather than all of them). This is verified in this
project not just structurally but semantically — a real boolean-formula
evaluator confirmed a message missing even one of three decomposed
exclusion leaves still correctly matches, while a message containing all
three correctly does not.

| Case | Combination formula (R = required id(s), E = excluded id(s)) |
|---|---|
| Plain, single pattern, no AND NOT | *(no combination — `id = termNumber` directly)* |
| Decomposed, no AND NOT | `(R1&R2&...&Rn)` |
| AND NOT, neither side decomposed | `(R&!E)` |
| AND NOT, required decomposed | `(R1&R2&...&Rn&!E)` |
| AND NOT, excluded decomposed | `(R&(!E1|!E2|...|!Em))` |
| AND NOT, both decomposed | `(R1&...&Rn&(!E1|...|!Em))` |

---

## The expression id scheme

Every PASS term's reportable Hyperscan expression id — whether it compiles
as one plain pattern or needs a QUIET/COMBINATION structure — is **always
its own term number**, parsed from its `termId`'s `::<n>` suffix (the
platform-wide `<lexicon_rule_name>::<term_number>` convention, e.g.
`lexicon_research_1::1`).

This is deliberate: a downstream consumer that already knows a term's
number (from the lexicon rule definition itself, independent of any
compile response) can predict which expression id to watch for that term
*without reading the JSON response at all* — the `.hdb` file is
self-sufficient. Every QUIET sub-expression a combination needs is
assigned an id from a separate allocated range instead
(`HyperscanCombinationHandler.computeIdOffset` = highest term number in
the request + 1, handed out sequentially by `HyperscanIdAllocator`), which
can never collide with a real term number.

**Every `termId` in a `/compile/bundle` request is validated up front**
(`LexiconCompileBundleService.validateTermIds`) before any term is
compiled: it must match `<rule>::<n>` for a non-negative integer `n`, and
every `n` in one request must be unique. A malformed or duplicate termId
throws `InvalidTermIdException` with a specific, actionable message —
better than letting a silent Hyperscan id collision corrupt the combined
database.

---

## Language support

`ScriptDetector` classifies the dominant Unicode script family of each
text segment (via ICU4J `UScript`, not Java's built-in
`Character.UnicodeScript`, for broader script coverage and correct
supplementary-plane handling), which drives both the Hyperscan flag
selection above and the NEAR/FOLLOWEDBY gap strategy:

| Script family | Gap strategy | Examples |
|---|---|---|
| Latin, Arabic, Hebrew, Devanagari | **Word-based** — `(?:\s+\S+){0,n}\s+` | English, German, French, Spanish, Russian, Arabic, Hebrew, Hindi |
| CJK, Kana, Hangul, Thai | **Character-based** — `[\s\S]{0,N}` where `N = n × avgCharsPerWord` | Chinese, Japanese, Korean, Thai/Lao/Myanmar |
| Mixed CJK/Kana/Hangul/Thai + anything | **Character-based** (forced) | Mixed English + Korean |
| Mixed RTL + Latin | **Word-based** (both sides use spaces) | Mixed Arabic + English |

Korean gets character-based gap even though formal Hangul writing does use
spaces between *eojeol* units — informal chat/SNS text frequently omits
them, so character-based gap handles both cases safely.

Arabic and Hebrew are stored in Unicode **logical order** (the order typed
and read), so `A FOLLOWEDBY B` correctly means "A at a lower byte index
than B" for purely RTL text — the regex engine operates on stored order,
independent of visual rendering.

Genuinely tested language coverage includes Korean, Japanese, Chinese,
Arabic, Hebrew, German (umlauts), Turkish, and emoji, plus mixed-script
terms (e.g. English + Korean + emoji in one term).

---

## Regex-type terms

A `/compile/bundle` request's `requestType` (`NATURAL_LANGUAGE` or `REGEX`) is
request-level, not per-term. A `REGEX` term's `termDescription` is compiled
**verbatim** — no operator-language translation runs at all. `NEAR{5}` in a
Regex-type term is not translated; Hyperscan treats `{5}` as a literal
repetition quantifier on whatever precedes it, which is valid PCRE syntax
on its own. Flags are still derived automatically via `ScriptDetector`, so
a raw non-Latin regex gets correct UTF8/UCP without the caller needing to
know Hyperscan's flag bitmask. `requiresExclusionCheck` is always `false`
for Regex-type terms — `AND NOT` is part of the Natural Language operator
language's own syntax; an arbitrary caller-supplied regex has no such
two-pattern exclusion contract to participate in.

---

## API reference

| Endpoint | Input | Notes |
|---|---|---|
| `POST /api/lexicon/compile` | JSON `TypedCompileRequest` | `request_id` required |
| `POST /api/lexicon/compile/csv` | Multipart CSV upload | 2-column (`Term ID, Term Description`); `request_id` auto-generated per upload |
| `POST /api/lexicon/compile/bundle` | JSON `TypedCompileRequest` | Returns a zip containing the JSON summary and (if ≥1 term passed) a combined `.hdb` file |
| `GET /api/lexicon/health` | — | Engine mode, Hyperscan version, supported operators/languages |

`TypedCompileRequest` is the single request shape for all three compile
endpoints:

```json
{
  "request_id": "<uuid>",
  "lexicon_rule_name": "lexicon_research_1",
  "term_type": "Natural Language",
  "terms": [
    {"term_id": "lexicon_research_1::1", "term_description": "(manipulate*) NEAR{5} ((price) OR (spread))"}
  ]
}
```

Both request body compression (`Content-Encoding: gzip`) and response
compression (`Accept-Encoding: gzip`) are supported.

---

## Build & test

```bash
mvn clean test        # JUnit 5, real Hyperscan native library, JDK 21 virtual threads
mvn clean package      # Spring Boot 4 executable jar
```

---

## Known limitations

- **Not independently executable-verified against a live GCP/Dataproc
  environment** — this project's development sandbox has no live Spark
  cluster or Dataproc connectivity; verification here uses a faithful,
  extensively cross-checked stub of the Hyperscan JNI wrapper plus real
  boolean-formula evaluation for combination semantics, not a live
  Hyperscan native library.
- **Nested Hyperscan combinations are avoided by design, not because they
  are confirmed unsupported** — see "Why exactly one combination
  expression per term" above.
- **Scanner Service and Scan Engine updates to consume the new
  `translatedPattern`/`exclusionPattern` list shape and id scheme are a
  separate, not-yet-started follow-up.**
