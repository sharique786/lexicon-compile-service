# Lexicon Compile Service

Translates compliance lexicon terms — written in a custom, human-authored
operator language, or supplied as raw regex — into Hyperscan-validated PCRE
patterns, and (via `/compile/bundle`) into a single combined Hyperscan
database ready for the Lexicon Scan Engine to load and scan against
directly.

Base package: `com.db.macs3.ecomms.spectre`
Stack: Spring Boot 4.0.6, Jakarta EE 10, **JDK 21** (virtual threads for
request handling), Intel Hyperscan 5.4.0-2.0.0 via `com.gliwka.hyperscan`,
ICU4J 73.2 for Unicode script detection.

This is the **upstream-most** of three services in this platform — the
Lexicon Scanner Service and Lexicon Scan Engine both consume this service's
output and never feed back into it.

---

## Table of contents

1. [Architecture — the translation pipeline](#architecture--the-translation-pipeline)
2. [The operator language](#the-operator-language)
3. [Character-based vs. word-based lexicon terms](#character-based-vs-word-based-lexicon-terms)
4. [Complex terms: decomposition, not rejection](#complex-terms-decomposition-not-rejection)
5. [Hyperscan flags](#hyperscan-flags)
6. [The expression id scheme (`/compile/bundle`)](#the-expression-id-scheme-compilebundle)
7. [Regex-type terms](#regex-type-terms)
8. [Validation rules and error catalog](#validation-rules-and-error-catalog)
9. [API reference](#api-reference)
10. [Configuration reference](#configuration-reference)
11. [Build & test](#build--test)
12. [Known limitations](#known-limitations)

---

## Architecture — the translation pipeline

```
term description (raw text)
  → Tokenizer               lexical analysis → List<Token>
  → ExpressionParser        recursive-descent parser → Ast (sealed interface,
                             8 node types: Or, And, AndNot, Near, FollowedBy,
                             Word, Phrase, QuotedPhrase)
  → PatternComplexityAnalyzer   estimates Hyperscan compiled-state cost
                                 BEFORE Hyperscan ever sees the pattern
  → PatternDecomposer        (only if over budget) splits into independent
                             leaf patterns rather than rejecting the term
  → PatternCodeGenerator     emits Hyperscan-compatible PCRE, delegating
                             NEAR/FOLLOWEDBY gap construction to
                             MultiLanguagePatternBuilder (script-aware)
  → HyperscanCompiler.validate()   the REAL Hyperscan compiler has final say
```

**Regex-type terms** (`requestType: "Regex"`) skip everything before the
last step — the caller-supplied pattern is validated directly against
Hyperscan, with flags still derived automatically from the pattern's own
script content.

**Design principle, load-bearing throughout this codebase**: no stage ever
hands a caller a pattern the real Hyperscan compiler hasn't validated. A
term is `PASS` only because Hyperscan itself accepted the final pattern
text — never because the AST "looked fine" or a heuristic said so.
`TermSyntaxTranslator` is the class that owns this whole pipeline; every
other translator class is a focused, independently-testable stage it calls.

| Stage | Class | Responsibility |
|---|---|---|
| Lexing | `Tokenizer` | Raw text → token stream; rejects malformed `NEAR{n}`/`FOLLOWEDBY{n}`, unbalanced parens/quotes, meaningless input |
| Parsing | `ExpressionParser` | Tokens → `Ast`; enforces grammar (operator precedence, AND operand ceiling, `NOT` only immediately followed by a parenthesised group and only as a later operand of `AND` — never standalone, though it's fine as an ordinary literal word) |
| Complexity estimate | `PatternComplexityAnalyzer` | Pre-Hyperscan heuristic score deciding whether to attempt decomposition |
| Decomposition | `PatternDecomposer` | Splits an over-budget NEAR/FOLLOWEDBY tree into independent leaves |
| Code generation | `PatternCodeGenerator` | `Ast` → PCRE string(s), delegating proximity gaps to `MultiLanguagePatternBuilder` |
| Script detection | `ScriptDetector` | ICU4J-based Unicode script classification driving gap strategy + Hyperscan flags |
| Gap/flag construction | `MultiLanguagePatternBuilder` | NEAR/FOLLOWEDBY gap patterns, adaptively narrowed against real Hyperscan when needed |
| Validation | `HyperscanCompiler` | Real Hyperscan compile of every candidate pattern; also builds the combined `/compile/bundle` database |
| Combination logic | `HyperscanCombinationHandler` | Decides QUIET/COMBINATION vs. plain-expression handling per term, for `/compile/bundle` only |
| Orchestration | `TermSyntaxTranslator` | Owns the whole pipeline for one term; the only class allowed to trigger decomposition fallback |

---

## The operator language

Every Natural Language term description passes through the pipeline above.

### Operators

| Operator | Meaning | Example |
|---|---|---|
| `OR` | Any operand matches | `price OR spread` |
| `AND` | All operands co-occur anywhere in the message, in any order, unbounded distance | `insider AND announcement AND price` |
| `AND NOT` | Required side matches AND the excluded side(s) do not — see below | `insider AND NOT (disclosed)` or `insider AND (NOT (disclosed))` |
| `NEAR{n}` | Operands within `n` words/characters of each other, **either order** | `(crap OR bad) NEAR{3} (bonus OR comp)` |
| `FOLLOWEDBY{n}` | Left operand, then right operand, within `n` words/characters, **left-to-right order only** | `don't FOLLOWEDBY{3} compliance` |
| `*` (suffix/prefix) | Wildcard — `chimp*` → `chimp\S*`, `*handler` → `\S*handler` | |
| `?` | Always a literal character, never a live regex quantifier — `he?d kill` matches the literal text `he?d kill` | |
| `"..."` | Quoted phrase — content taken literally, including any `*`/`?` inside (never wildcard-expanded) | `"do not share"` |

`n` in `NEAR{n}`/`FOLLOWEDBY{n}` must be a **whole number from 1 to 50**,
written with no leading zero and no comma-separated range, immediately
after the keyword with **no whitespace** before the `{`
(`Tokenizer.validateProximityDistance`, `MAX_PROXIMITY_DISTANCE = 50`).
Every rejected form gets a specific, actionable error — see
[Validation rules](#validation-rules-and-error-catalog).

Reserved keywords (`OR`, `AND`, `NOT`, `NEAR`, `FOLLOWEDBY`) are recognised
**only in exact upper case** — `near` or `Or` are ordinary literal text.

### Bracket resolution and phrase wrapping

Brackets resolve innermost-first, at any nesting depth, including
redundant wrapping (`(((me) OR (cking)))` resolves the same as `me OR cking`)
— this falls out of `ExpressionParser`'s ordinary recursive descent, not a
separate "strip outer parens" pass. A multi-word phrase does **not**
strictly require explicit parentheses — an unwrapped phrase like
`insider trading OR market manipulation` is accepted as an implicit phrase
group (`bomb this place` → one literal `Ast.Phrase`) — but wrapping is
still recommended for clarity beyond the simplest case.

### `AND`: corrected co-occurrence semantics

`A AND B AND C` compiles directly into **one** self-contained pattern
covering every ordering permutation of the operands, joined by an
unbounded gap (`[\s\S]*`) — the same bidirectional-alternation technique
`NEAR` uses for a *bounded* gap, just unbounded. `price AND rigging`
compiles to `(?:price[\s\S]*rigging|rigging[\s\S]*price)`, which matches
"price... rigging" or "rigging... price" anywhere apart, but never matches
a message containing only one of the two. No lookaround (Hyperscan has
none), no post-filter, no scan-time cooperation needed. `requiresExclusionCheck`
is always `false` for a plain `AND` term — it needs no exclusion side.

A single `AND`/`AND NOT`-required level is capped at **5 operands**
(`ParseContext.MAX_AND_OPERANDS`) — `generateAnd` enumerates every
ordering (`N!` permutations), so operand count directly controls pattern
size; beyond 5 that's already 120 permutations, reliably too large for
Hyperscan. Exceeding this is a translation-time rejection, not a
Hyperscan-time surprise.

### `AND NOT`: a two-list contract, not a single regex

Hyperscan supports no negative lookaround, so "A but not B" cannot be one
pattern. `AND NOT` always produces **two** independently Hyperscan-valid
pattern lists:

- `regexPattern` — the required side (A)
- `exclusionRegex` — the excluded side (B), non-null only when
  `requiresExclusionCheck` is `true`

Chained exclusions (`A AND NOT B AND NOT C`) are combined into **one**
excluded side (`B OR C`) before translation — one exclusion check, not a
chain of them.

**What a caller does with the two lists differs by endpoint — read this
carefully, the behavior changed from an earlier design:**

- **`/compile` and `/compile/csv`** — the caller (e.g. the Lexicon Scanner
  Service) compiles every pattern in both lists itself and combines the
  boolean results in application code: matched iff every entry of
  `regexPattern` matches, AND the excluded condition (every entry of
  `exclusionRegex` found — same AND convention as the required side) is
  **not** fully satisfied.
- **`/compile/bundle`** — **every required and excluded pattern compiles
  as its own plain, individually-reportable Hyperscan expression** — never
  QUIET, never a native `HS_FLAG_COMBINATION`. `hyperscanExpressionId` is
  `null` for an AND NOT term; `requiredExpressionIds`/`excludedExpressionIds`
  are populated instead (one id per pattern). The caller must evaluate the
  boolean condition itself, **after the whole scan completes**, from the
  complete matched-id set the scan returns.

  **Why not native `HS_FLAG_COMBINATION` here, given it *is* used for
  plain decomposition?** Confirmed unsafe via Hyperscan's own
  documentation: a combination expression "raises matches at every offset
  where one of its sub-expressions matches and the logical value of the
  whole expression is true" — evaluated **eagerly and progressively**
  during the scan, not once at the end. Hyperscan's changelog documents a
  special end-of-data deferral for *purely negative* combinations only —
  `R&!E` also needs the positive `R`, so it does not qualify. If `R`
  matches before `E` has even been *reached* by the scan (not confirmed
  absent — merely not yet seen), `!E` reads true at that instant and the
  combination fires immediately and incorrectly, before `E` had any chance
  to appear later in the same text. This was an earlier (now-replaced)
  design of this service; the current one avoids it entirely for any term
  where `requiresExclusionCheck` is true, regardless of whether either
  side was decomposed. Pure decomposition (no AND NOT) has no such
  ambiguity — no negation, so it's unaffected and still uses native
  `COMBINATION`. See `HyperscanCombinationHandler` class Javadoc for the
  full history.

**Nested `AND NOT` is rejected, not silently mishandled.** `AND NOT`
grammatically parses anywhere a parenthesised group is legal — e.g.
`(A AND NOT B) NEAR{5} C`, or even `X OR Y AND NOT Z` with no parentheses
at all. `TermSyntaxTranslator.rejectNestedAndNot()` walks the whole AST
and throws a `TranslationException` naming the term for any `AndNot` found
anywhere except the root. This is deliberate, not a missing feature —
auto-hoisting a nested exclusion to the top level would silently change
what the term means (an exclusion the author scoped to one operand of a
`NEAR` would become term-wide instead). Rewrite the term with `AND NOT` at
the outermost level, e.g. `(A NEAR{n} C) AND NOT B`.

---

## `resolvedPatterns`: NEAR/FOLLOWEDBY/AND NOT are no longer compiled into regex

**Read this before the two sections below** — they now describe historical
behavior for one narrow residual case, not the general path.

`NEAR{n}`/`FOLLOWEDBY{n}` used to be compiled into a single Hyperscan
pattern with the gap embedded literally. This was fragile — CJK/Thai/Hangul
terms multiplied the author's distance by a per-script `avgCharsPerWord`
factor, frequently producing a gap Hyperscan couldn't compile ("Pattern is
too large"). **Now, NEAR/FOLLOWEDBY splitting is unconditional** (not a
complexity-triggered fallback), and the gap is never compiled into regex
for the split case — not even as a leaf prefix. Instead, the relationship
is conveyed as literal keyword text, using the author's raw distance, in a
new response field: `resolvedPatterns`.

```
Input: "((bash)) FOLLOWEDBY{30} ((fuck) OR (fck))"
  regexPattern:     ["bash", "(?:fuck|fck)"]
  resolvedPatterns: "bash FOLLOWEDBY{30} (?:fuck|fck)"

Input: "(insider AND NOT ((wordA word B OR wordC* wordD OR wordE* wordF OR wordG)
         FOLLOWEDBY{2} (wordH* OR wordI wordJ* wordK OR wordL* wordM OR wordN)
         FOLLOWEDBY{2} (wordO* OR wordP* wordQ OR wordR* wordS OR wordT)))"
  regexPattern:     ["insider"]
  exclusionRegex:   ["(?:wordA word B|wordC\\S* wordD|wordE\\S* wordF|wordG)",
                      "(?:wordH\\S*|wordI wordJ\\S* wordK|wordL\\S* wordM|wordN)",
                      "(?:wordO\\S*|wordP\\S* wordQ|wordR\\S* wordS|wordT)"]
  resolvedPatterns: "insider AND NOT ((?:wordA word B|wordC\\S* wordD|wordE\\S* wordF|wordG)
                      FOLLOWEDBY{2} (?:wordH\\S*|wordI wordJ\\S* wordK|wordL\\S* wordM|wordN)
                      FOLLOWEDBY{2} (?:wordO\\S*|wordP\\S* wordQ|wordR\\S* wordS|wordT))"

Input: "(ihr Gespräch OR Gespraech OR *reden) NEAR{30} (threema OR threema messenger OR threema IM)"
  regexPattern:     ["(?:ihr Gespräch|Gespraech|\\S*reden)", "(?:threema|threema messenger|threema IM)"]
  resolvedPatterns: "(?:ihr Gespräch|Gespraech|\\S*reden) NEAR{30} (?:threema|threema messenger|threema IM)"
```

Always exactly **one string** per term (never a list, despite the plural
name) — every leaf substring inside it is byte-identical to the
corresponding `regexPattern`/`exclusionRegex` entry, in the same order.
Populated across **all three endpoints** (unlike `hyperscanExpressionId`/
`patternMapping`, which stay `/compile/bundle`-only). `patternMapping` is
unchanged and stays additive alongside it — a caller that only needs
presence/AND-NOT boolean logic keeps using `patternMapping`; a caller that
needs the actual proximity relationship reads `resolvedPatterns`.

A downstream Java-regex-based consumer (Lexicon Scan Engine / Lexicon
Scanner Service — not part of this repo) tokenizes `resolvedPatterns` and
re-applies the proximity/AND-NOT logic itself. See
`src/test/java/.../ResolvedPatternMatcher.java` for a reference
implementation of exactly that technique, and
`ResolvedPatternMatchingIntegrationTest` for it proven end-to-end against
real compile-service output — both are a required, intentional deliverable
of this feature, a blueprint for the other two services, not incidental
test coverage.

**One case is deliberately excluded from unconditional splitting**: a
NEAR/FOLLOWEDBY nested *inside* an `OR` (as one alternative sibling to
others, e.g. `"(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)"` — real,
currently-used functionality) cannot be flattened into a flat AND'd leaf
list without changing what `OR` means. This one case still compiles as a
single gap-embedded pattern exactly as described in the next two sections
— they remain live for it.

`AND` is flattened (not left opaque) when one of its operands contains
NEAR/FOLLOWEDBY structure — lossless, since `AND`'s own "all present, any
order, unbounded distance" semantics is already equivalent to flat
independent presence. A plain `AND` with no nested proximity is completely
unaffected — still one self-contained permutation pattern.

---

## Character-based vs. word-based lexicon terms — now only for OR-nested proximity

The mechanism below still exists and is still correct, but as of
`resolvedPatterns` (above) it is reachable **only** via the one excluded
case — a NEAR/FOLLOWEDBY nested inside a multi-operand `OR`. For every
other NEAR/FOLLOWEDBY, no gap is ever computed at all.

`ScriptDetector` classifies the dominant Unicode script family of each
operand (via ICU4J `UScript`, not Java's built-in `Character.UnicodeScript`,
for broader coverage and correct supplementary-plane/emoji handling). This
drives both the Hyperscan flags (below) and, for `NEAR`/`FOLLOWEDBY`, which
of two fundamentally different gap strategies `MultiLanguagePatternBuilder`
uses:

| Gap strategy | Scripts | Pattern |
|---|---|---|
| **Word-based** | Latin, Arabic, Hebrew, Devanagari (and other space-delimited Indic scripts: Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada, Malayalam, Sinhala, Tibetan) | `(?:\s+\S+){0,n}\s+` — up to `n` intervening whitespace-delimited words |
| **Character-based** | CJK (Chinese/Japanese Kanji), Kana, Hangul (Korean), Thai/Lao/Myanmar | `[\s\S]{0,N}` where `N = n × avgCharsPerWord` (CJK/Kana: ×3, Hangul: ×5, Thai: ×6) |
| **Character-based (forced)** | Any mix that includes a space-free script (CJK/Kana/Hangul/Thai) alongside anything else | `[\s\S]{0,N}`, `N = n × 4` — forced even when only one operand is space-free |
| **Word-based** | Mixed RTL (Arabic/Hebrew) + Latin/Indic | `(?:\s+\S+){0,n}\s+` with UTF8+UCP — both sides use spaces |

Korean gets character-based gap even though *formal* Hangul writing does
use spaces between *eojeol* units — informal chat/SNS text frequently
omits them, so character-based gap handles both cases safely. Arabic and
Hebrew are stored in Unicode **logical order** (typed/read order,
independent of visual rendering), so `A FOLLOWEDBY B` correctly means "A
at a lower byte index than B" for purely RTL text with no special
handling needed. `MultiLanguagePatternBuilder.buildFollowedBy` warns (does
not fail) when operands mix RTL and LTR script, since the "before"
relationship may not match visual reading order in that case.

### The static gap ceiling — and why it alone isn't enough

Hyperscan rejects `[\s\S]{0,N}` (or the word-based equivalent) with
**"Pattern is too large"** once `N` reaches the low-30s, regardless of
script — confirmed empirically by bisection sweep against the real native
library:

| Constant | Class | Calibrated value | What it bounds |
|---|---|---|---|
| `MAX_CHAR_GAP` | `MultiLanguagePatternBuilder` | 30 | `[\s\S]{0,N}` — character-based gap |
| `MAX_WORD_GAP` | `MultiLanguagePatternBuilder` | 29 | `(?:\s+\S+){0,N}\s+` — word-based gap |

This is the **same internal Hyperscan bounded-repeat state-count limit**
in both cases — a property of `{0,N}` itself, not of what's inside the
repeated group. When the raw formula width (`n × avgCharsPerWord + n` for
char-based, or `n` itself for word-based) exceeds the ceiling, the gap is
clamped and a warning is added describing the precision loss (matches
requiring more intervening content than the clamped width will be missed).

**The static ceiling was calibrated against plain two-word operand
pairs — a real lexicon term's operand is very often a wide `OR` group
instead**, e.g. `((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10} ((活动) OR
(记录))`. The extra alternation increases compiled automaton state count
beyond what the two-word calibration covers — `[\s\S]{0,30}` sitting next
to a wide alternation can still be rejected by real Hyperscan even though
30 is safe for a plain pair.

**When this happens, the gap is adaptively narrowed further — by actually
test-compiling the term's real candidate pattern against the real
Hyperscan native library, not just guessing.** Once the static ceiling
would already apply (i.e. only in the regime that's actually at risk —
see the performance note below), `MultiLanguagePatternBuilder` builds the
*real* bidirectional NEAR / directional FOLLOWEDBY / decomposed-leaf shape
at the clamped width, attempts a real Hyperscan compile, and if it fails,
decrements the width and retries — down to a floor of 0 — until a width is
found that compiles. The response's `warnings` entry reflects the actual
final width used, not just the static clamp.

**Performance note**: this adaptive trial-compile only triggers once the
static formula width already exceeds the ceiling — an ordinary,
comfortably-small-distance term (the overwhelming majority) pays for zero
extra Hyperscan calls. Only a term whose raw requested distance is already
large enough to need clamping pays for the extra compile(s), which is
exactly the regime where "Pattern is too large" is actually a risk.

### Language coverage

Genuinely exercised: Korean, Japanese (Kanji + Kana), Chinese (Simplified
+ Traditional), Arabic, Hebrew, Thai, German (umlauts), Turkish, Hindi/
Devanagari, and emoji — including mixed-script terms in one operand (e.g.
English + Korean + emoji). Script detection additionally recognises Greek,
Cyrillic, Armenian, and Georgian (treated as Latin-family for gap
strategy) and the other space-delimited Indic scripts listed above. Script
classification only affects *gap strategy and flag selection* — any
Unicode text (including scripts with no dedicated `ScriptType`) can appear
in a term; unrecognised/symbol/emoji code points are simply excluded from
script voting and the term falls back to Latin (word-based) treatment.

---

## Complex terms: decomposition — now unconditional, not complexity-triggered

`PatternDecomposer` is now the single, always-on path for ANY term
containing NEAR/FOLLOWEDBY structure (except the OR-nested-proximity case
above) — see the `resolvedPatterns` section at the top of this document.
`PatternComplexityAnalyzer` no longer gates anything; it's kept in the
codebase, unused/dormant, since the state-count reasoning below is still
correct history and explains *why* Hyperscan's old gap-embedding approach
was fragile in the first place.

### Why "Pattern is too large" isn't about string length

Hyperscan's "Pattern is too large" is a documented consequence of
*compiled automaton state count*, not raw pattern string length — a
190-character pattern can trigger it while much longer patterns compile
fine. Empirically, the primary driver is **nesting depth**, not OR-branch
width: a single-level `NEAR` over wide OR groups (18 and 8 alternatives,
no nesting) compiled successfully, while a term with two *nested*
`FOLLOWEDBY` operators over much narrower 4-alternative groups was
rejected. One proximity operator whose operand is itself a proximity
operator forces the automaton to track two independent gap-counters
simultaneously — multiplicative, not additive.

### `PatternComplexityAnalyzer` — dormant, no longer called

Used to estimate this risk *before* Hyperscan ever sees the pattern and
decide whether to pre-emptively decompose. As of `resolvedPatterns`
(above), decomposition is unconditional, so nothing calls this class any
more — kept in the repo for its historical reasoning, not deleted:

- **Nesting penalty** — a NEAR/FOLLOWEDBY whose operand is itself a
  NEAR/FOLLOWEDBY multiplies the expression's score by `(nestedDistance + 1)`.
  A single-level proximity operator (neither operand nested) gets no
  penalty.
- **NEAR directionality factor (×2)** — NEAR generates both orderings;
  FOLLOWEDBY only one.
- **Wildcard weight** — a wildcard-containing word/phrase scores 2 instead
  of 1 (its own unbounded internal branching compounds with surrounding
  repetition).
- **Char-gap penalty** — a proximity node under a character-based script
  is additionally multiplied by its own effective gap width (the same
  `effectiveGapWidth` `MultiLanguagePatternBuilder` uses), since a CJK
  `NEAR{10}` compiles to an expensive `[\s\S]{0,N}` repeat that a plain
  Latin `NEAR{10}` (cheap `(?:\s+\S+){0,10}\s+`) does not.

`COMPLEXITY_BUDGET = 700` — calibrated against two known real Hyperscan
outcomes (a PASS at raw score 480, a real FAILURE at 1470 once the nesting
penalty applies). This is a heuristic, not a proof — see the Phase-2
Hyperscan double-check below for how a wrong guess is still caught.

### `PatternDecomposer`

The single, unconditional path for any side containing NEAR/FOLLOWEDBY
structure (except the OR-nested-proximity exception) — splits it into
independent leaf patterns, in the SAME recursive pass that builds
`resolvedPatterns`' literal-keyword text (so the two can never drift out of
sync — see `resolvedPatterns` section above). A leaf is a maximal subtree
that is not itself splittable further: an `Or`, a `Word`/`Phrase`/
`QuotedPhrase`, or an `And` with no nested proximity of its own. `regexPattern`
(or `exclusionRegex`, for the excluded side) then has multiple entries
instead of one — there is no separate "was this split" boolean; a caller
checks `regexPattern.size()`.

**This is a real precision trade-off, always flagged in `warnings`, fully
compensated by `resolvedPatterns`.** Split leaves are combined with pure
boolean AND ("all of these appear somewhere in the message"), losing the
NEAR/FOLLOWEDBY ordering/distance constraint *between* leaves — but unlike
an earlier revision of this codebase, **no gap fragment is baked into any
leaf's own pattern text any more, not even as a prefix**. The full
relationship — including the author's raw, un-clamped distance — lives
entirely in `resolvedPatterns` instead. For `(A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C`,
splitting produces exactly `regexPattern = [A, B, C]` and
`resolvedPatterns = "A FOLLOWEDBY{4} B FOLLOWEDBY{4} C"`.

### Real Hyperscan still has final say

Every leaf `PatternDecomposer` produces is validated against the real
Hyperscan compiler before being accepted — this codebase's own long-standing
principle that no stage ever hands Hyperscan a pattern the real compiler
hasn't validated. A single leaf (no further NEAR/FOLLOWEDBY structure to
split) that Hyperscan itself rejects — for any reason, including "too
large" — is a hard translation failure: there's nothing further
`PatternDecomposer` can do with it. A leaf naming a specific piece of text
in its error message points the term's author at the exact part needing
simplification (fewer OR-alternatives, less wildcard usage, or splitting
into multiple lexicon terms).

---

## Hyperscan flags

Which `ExpressionFlag`s an expression gets is decided by which of **three
mutually exclusive cases** it falls into (plus a fourth for the
combination expression itself) — never by script content alone (content
still narrows UTF8/UCP *within* case 3):

| Case | Method | Flags |
|---|---|---|
| AND NOT — every required/excluded pattern, regardless of leaf count on either side | `HyperscanCompiler.toAndNotExpressionFlags()` | `CASELESS` only |
| A leaf from a term with NEAR/FOLLOWEDBY structure, no AND NOT (feeds a native `COMBINATION`; unconditional now, not complexity-triggered — see `resolvedPatterns` above) | `HyperscanCompiler.toSubExpressionFlags()` | `CASELESS`, `QUIET` only |
| Simple, single-pattern, non-AND-NOT PASS term (also the flag set `HyperscanCompiler.validate()` always uses for validation) | `HyperscanCompiler.toExpressionFlags(bitmask)` | `CASELESS`, `DOTALL`, `SOM_LEFTMOST` always; `UTF8`/`UCP` only when `bitmask` indicates non-Latin content |
| The one combination expression per split (non-AND-NOT) term | `HyperscanCompiler.toCombinationExpressionFlags()` | `COMBINATION` only |

`hyperscanFlags` in the JSON response is a narrower bitmask than the above
— it only ever carries `1`=CASELESS, `32`=UTF8, `64`=UCP (e.g. `1` for a
pure-Latin term, `97` for a CJK/Arabic/Hebrew term). `DOTALL`/`SOM_LEFTMOST`/
`QUIET`/`COMBINATION` are structural — added at expression-construction
time per the table above, never carried in this field.

**UTF8/UCP stay conditional in the simple-term case only, on purpose —
this was tried unconditionally first and reverted after two confirmed
regressions:** (1) Hyperscan rejects `\b` (word boundary) when UCP is
active, breaking any caller-supplied Regex-type term using it; (2) UCP
mode measurably slows Hyperscan compilation even for plain-ASCII patterns
(~15× in this project's own performance test). Without UCP, `\S+` only
matches ASCII non-whitespace and silently skips Arabic/Hebrew/CJK — this
is why any script needing UTF8 also needs UCP, never UTF8 alone. The AND
NOT and pure-decomposition-leaf cases carry **no** conditional bits at all,
even for non-Latin content — deliberate, not an oversight.

**AND NOT deliberately does not get `SOM_LEFTMOST`**, even though it would
be structurally *safe* there (AND NOT patterns are plain, never QUIET) —
the case is scoped to `CASELESS` only regardless, a deliberate narrowing.

**`SOM_LEFTMOST` + `QUIET` is a confirmed-incompatible combination** — hit
as a real Hyperscan compile error early in this project's history. A
plain simple-term expression (never QUIET) always safely gets
`SOM_LEFTMOST`; a QUIET decomposition-leaf expression never does. There
used to be a caller-facing `trackMatchPosition` request field letting a
caller opt out of `SOM_LEFTMOST` — it was removed entirely, since which
flags an expression may safely carry is a structural fact about its kind
(one of the three cases above), never a per-request caller preference.

---

## The expression id scheme (`/compile/bundle`)

A **non-AND-NOT** PASS term's reportable Hyperscan expression id — whether
it split into multiple leaves or not — is **always its own term number**,
parsed from its `termId`'s `::<n>` suffix (`<lexicon_rule_name>::<term_number>`,
e.g. `lexicon_research_1::1`). This is deliberate: a downstream consumer
that already knows a term's number from the lexicon rule definition can
predict its expression id **without reading the JSON response at all** —
the `.hdb` file is self-sufficient for these terms. Note this scheme
required **no code change** for the `resolvedPatterns` work — id allocation
already discriminated purely on `regexPattern.size()`, never on *why*
there was more than one entry, so it "just works" now that NEAR/FOLLOWEDBY
splitting fires unconditionally instead of only when over budget.

An **AND NOT** term has no single reportable id — `hyperscanExpressionId`
is `null`; `requiredExpressionIds`/`excludedExpressionIds` are populated
instead (one id per pattern in `regexPattern`/`exclusionRegex`
respectively). Every QUIET sub-expression a split (non-AND-NOT) term's
combination needs is assigned an id from a separate allocated range
(`HyperscanCombinationHandler.computeIdOffset` = highest term number in
the request + 1, handed out sequentially), which can never collide with a
real term number.

### `patternMapping` — the logical formula, whether or not the `.hdb` itself encodes it

Populated only when a term needed **more than one** Hyperscan expression
id (NEAR/FOLLOWEDBY structure, AND NOT, or both). A boolean formula over
this term's expression ids, using the same `&`/`!` syntax Hyperscan's own
`HS_FLAG_COMBINATION` formulas use. **Unchanged and additive alongside
`resolvedPatterns`** (see above) — not superseded by it:

| Case | `patternMapping` | Encoded natively in the `.hdb`? |
|---|---|---|
| Plain, single pattern, no AND NOT | *(null — nothing to map)* | — |
| Split, no AND NOT | `(R1&R2&...&Rn)` | **Yes** — a real `COMBINATION` expression at `hyperscanExpressionId` (safe, no negation) |
| AND NOT, neither side split | `(R&!E)` | **No** — every pattern is its own plain expression |
| AND NOT, required side split | `(R1&R2&...&Rn&!E)` | **No** |
| AND NOT, excluded side split | `(R&!(E1&E2&...&Em))` | **No** |
| AND NOT, both sides split | `(R1&...&Rn&!(E1&...&Em))` | **No** |

For an AND NOT term, `patternMapping` is the **only** place this formula
is recorded — a consumer that loads just the `.hdb` (no JSON) cannot
derive AND NOT semantics from the database alone; it must read
`patternMapping` from this JSON and apply it itself, after the whole scan
completes, against the complete matched-id set.

Correctness for a decomposed excluded side requires De Morgan's law,
applied explicitly: `NOT(E1 AND E2 AND ... AND Em) = (NOT E1) OR (NOT E2)
OR ... OR (NOT Em)`. Decomposition combines leaves with AND ("all parts
found"), so negating that condition is an OR of negations — otherwise a
message missing only *one* of several decomposed exclusion leaves would
incorrectly be treated as still excluded.

### Term id validation — checked for the whole request before any term compiles

Every `termId` in a `/compile/bundle` request must match `<rule>::<n>` for
a non-negative integer `n`, and every `n` in one request must be unique —
both validated up front (`LexiconCompileBundleService.validateTermIds`)
before any term is compiled. A malformed or duplicate termId throws
`InvalidTermIdException` (HTTP 400) naming every offending id — better
than letting a silent Hyperscan id collision corrupt the combined
database.

---

## Regex-type terms

`TypedCompileRequest.requestType` (`"Natural Language"` or `"Regex"`) is
**request-level**, not per-term — a request can only submit one type of
term; a mixed request must be split into two calls. A `Regex` term's
`termDescription` is compiled **verbatim** — no operator-language
translation runs at all. `NEAR{5}` in a Regex-type term is not
translated; Hyperscan treats `{5}` as a literal PCRE repetition
quantifier on whatever precedes it. Flags are still derived automatically
via `ScriptDetector`, so a raw non-Latin regex gets correct UTF8/UCP
without the caller needing to know Hyperscan's flag bitmask.
`requiresExclusionCheck` is always `false` for Regex-type terms — `AND
NOT` is part of the Natural Language operator language's own syntax; an
arbitrary caller-supplied regex has no such two-pattern exclusion contract
to participate in. `/compile` and `/compile/csv` also accept
`requestType: "Regex"` (CSV terms are always Natural Language, since a CSV
row has no `requestType` column).

---

## Validation rules and error catalog

Validation happens in layers — request shape, then lexical, then
grammatical, then semantic/Hyperscan — and each layer fails with a
specific, actionable message rather than an opaque downstream error.

### 1. Request-shape validation (Jakarta Bean Validation, HTTP 400)

| Field | Rule |
|---|---|
| `request_id` | must not be blank |
| `lexiconRuleName` | must not be blank |
| `requestType` | must be exactly `"Natural Language"` or `"Regex"` |
| `terms` | must not be empty |
| `terms[].termId` | must not be blank |
| `terms[].termDescription` | must not be blank |

A failure here never reaches term translation — see the error response
shape under [API reference](#api-reference).

### 2. Lexical validation (`Tokenizer`) — per term, translation-stage

| Rule | Example rejected input |
|---|---|
| Term must contain at least one letter or digit (rejects pure-symbol input) | `#@$#%$`, `!!!`, `***` |
| Parentheses must be balanced | `(fix NEAR{3} (rate)` / `fix NEAR{3} rate)` |
| `()` empty parentheses not allowed | `()` |
| Quoted phrase must be closed | `"unclosed phrase` |
| `NEAR`/`FOLLOWEDBY` must be immediately followed by `{n}` with **no whitespace** | `NEAR {3}` |
| `NEAR{n}`/`FOLLOWEDBY{n}`: `n` must be a **whole number from 1 to 50** | `NEAR{0}`, `NEAR{-1}`, `NEAR{51}`, `NEAR{05}`, `NEAR{abcd}`, `NEAR{3,6}` all rejected; `NEAR{1}` … `NEAR{50}` all accepted |

### 3. Grammatical validation (`ExpressionParser`) — per term, translation-stage

| Rule | Behavior |
|---|---|
| `NOT` not immediately followed by `(` (e.g. `apple NOT NEAR{10} banana`, `apple AND NOT NEAR{10} banana`, `apple AND NOT banana`) | Rejected — `NOT` must always be followed immediately by a parenthesised group |
| `NOT (...)` with nothing preceding it at the same level (e.g. `NOT (james bond)` alone, or as the sole/first content of a parenthesised group with no other operand) | Rejected — `NOT` always needs a preceding required expression joined by `AND` |
| `NOT (...)` used as an `OR` alternative, or as a `NEAR`/`FOLLOWEDBY` operand | Rejected — `NOT` is only ever valid as a later operand of `AND` |
| `NOT (...)` as a later operand of `AND` (e.g. `bond AND (NOT (james bond))`, or the equivalent glued spelling `bond AND NOT (james bond)`) | **Accepted** — both spellings produce the identical required/excluded shape |
| `NOT` starting a fresh atom, NOT immediately followed by `(` (the very first token of the term, or immediately after `(`, `OR`, `AND`, `AND NOT`, `NEAR{n}`, or `FOLLOWEDBY{n}`) | **Accepted** — treated as ordinary literal text, folded into whatever word/phrase run follows (e.g. `(NOT LAUNCHING)` → the literal phrase "NOT LAUNCHING") — see below |
| AND operand ceiling | More than 5 operands at one `AND`/`AND NOT`-required level rejected |
| Chained `NEAR`/`FOLLOWEDBY` without explicit parentheses (`A FOLLOWEDBY{5} B FOLLOWEDBY{6} C`) | **Accepted**, not rejected — parsed as left-associative nesting, with a warning recorded (kept for backward compatibility with existing lexicon terms) |
| Malformed/unbalanced structure that doesn't match the grammar | Rejected with a generic "could not parse term" error naming the position |

**`NOT` is always a unary prefix on a parenthesised group, and that group
is always a later operand of `AND`** — never a standalone operator, never
directly combinable with a proximity operator, and never usable on its own:

```
✓ bond AND (NOT (james bond))                — valid: NOT-group as an AND operand
✓ apple AND (NOT (apple NEAR{10} banana))     — valid: NOT wraps an arbitrary sub-expression
✓ apple AND NOT (banana)                      — valid: the "glued" spelling, same shape
✗ NOT (james bond)                            — rejected: no preceding required expression
✗ apple NOT NEAR{10} banana                   — rejected: NOT directly before a proximity operator
✗ apple AND NOT NEAR{10} banana               — rejected: NOT not immediately followed by '('
```

**`NOT` as a word vs. `NOT` as an operator** — `NOT` starting a fresh atom
with nothing immediately after it that looks like an operand it could
negate (i.e. NOT immediately followed by `(`) is just literal text, folded
into whatever word/phrase run follows:

```
((disintermediate*) OR (NOT LAUNCHING) OR (NOT TO LAUNCH THE PRODUCT))
  → PASS: (?:disintermediate\S*|NOT LAUNCHING|NOT TO LAUNCH THE PRODUCT)
    ("NOT" is literal in both OR-branches — it starts each phrase)

price AND NOT (rigging OR change)
  → required: price, excluded: (?:rigging|change)
    ("NOT" immediately followed by '(', as a later AND operand — the operator form)
```

### 4. Semantic / Hyperscan-stage validation (`TermSyntaxTranslator`)

| Rule | Behavior |
|---|---|
| Nested `AND NOT` anywhere except the term's AST root | Rejected — rewrite with `AND NOT` at the top level |
| A side over budget with no NEAR/FOLLOWEDBY structure to decompose | Rejected — reduce OR-alternatives/wildcards or split into multiple terms |
| A decomposed leaf still rejected by Hyperscan on its own | Rejected — that specific part needs simplifying |
| A pattern Hyperscan rejects for a non-size reason (genuine syntax/semantic problem) | Rejected — Hyperscan's real error surfaced verbatim; decomposition is never attempted |
| A pattern Hyperscan rejects as "too large" | **Not** rejected — falls back to decomposition automatically |

### 5. `/compile/bundle`-specific validation

| Rule | Behavior |
|---|---|
| Every `termId` must match `<rule>::<n>`, `n` a non-negative integer | `InvalidTermIdException` (HTTP 400) naming every malformed id |
| Every `n` must be unique within the request | `InvalidTermIdException` (HTTP 400) naming the colliding term numbers |

### 6. CSV-specific handling (`/compile/csv`) — lenient, not strict

| Condition | Behavior |
|---|---|
| Header row (first column contains "term id", case-insensitive) | Auto-detected and skipped |
| Blank line | Skipped silently |
| Line starting with `#` | Skipped silently (comment) |
| Row with fewer than 2 columns | Skipped, with a warning logged — **not** a request failure |
| A 3rd+ column (e.g. legacy `Risk Driver Name`) | Ignored |
| Empty uploaded file | HTTP 400, empty body |
| Upload exceeds `lexicon.upload.max-file-size` (default 10MB) | HTTP 413 |

### Error response shapes

**Bean-validation failure** (missing/blank required field):

```json
{
  "status": 400,
  "error": "Validation failed",
  "details": ["terms: terms list must not be empty"],
  "timestamp": "2026-08-26T10:15:00.123Z"
}
```

**Invalid termId(s) on `/compile/bundle`**:

```json
{
  "status": 400,
  "error": "termId must end with '::<n>' where n is a non-negative integer (the platform's term-number convention, e.g. 'lexicon_rule_name::1') — required for /compile/bundle's Hyperscan expression id scheme. Malformed termId(s): [lexicon_research_1::bad]",
  "timestamp": "2026-08-26T10:15:00.123Z"
}
```

**Upload too large**:

```json
{ "status": 413, "error": "Uploaded file exceeds maximum allowed size", "timestamp": "2026-08-26T10:15:00.123Z" }
```

**Unhandled server error**:

```json
{ "status": 500, "error": "Internal server error", "timestamp": "2026-08-26T10:15:00.123Z" }
```

> Two endpoint-specific edge cases return an **empty body** instead of the
> structured shape above (not yet unified with `GlobalExceptionHandler`):
> an empty CSV upload (`400`) and a CSV that fails to parse as valid CSV
> syntax, or a zip-build I/O failure on `/compile/bundle` (`500`).

**A term that fails translation or Hyperscan validation is NOT a request
error** — the HTTP response is still `200 OK`; the failure is reported
per-term via `compilationStatus: "FAILED"` alongside any terms that
passed. See the worked examples below.

---

## API reference

| Endpoint | Input | Output |
|---|---|---|
| `POST /api/lexicon/compile` | JSON `TypedCompileRequest` | JSON `CompileResponse` |
| `POST /api/lexicon/compile/csv` | Multipart CSV upload | JSON `CompileResponse` (same shape) |
| `POST /api/lexicon/compile/bundle` | JSON `TypedCompileRequest` | `application/zip`: JSON results + `.hdb` (or `NO_DATABASE.txt`) |
| `GET /api/lexicon/health` | — | Engine mode, Hyperscan version, supported operators/languages |

Both request body compression (`Content-Encoding: gzip`, decompressed by
`GzipRequestFilter`) and response compression (`Accept-Encoding: gzip`,
handled by Tomcat) are supported. HTTP 200 is returned even when
individual terms fail compilation — only structurally invalid *requests*
get a non-200 status.

### `POST /api/lexicon/compile`

Request:

```json
{
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "lexiconRuleName": "lexicon_research_1",
  "requestType": "Natural Language",
  "terms": [
    { "termId": "lexicon_research_1::1", "termDescription": "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))" },
    { "termId": "lexicon_research_1::2", "termDescription": "tip* AND NOT (disclaimer)" },
    { "termId": "lexicon_research_1::3", "termDescription": "((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10} ((活动) OR (记录))" },
    { "termId": "lexicon_research_1::4", "termDescription": "insider AND NOT (compliance NEAR{5} approved)" }
  ]
}
```

Response — one entry per term, showing a **simple PASS**, a **PASS with
`AND NOT`**, a **PASS that needed decomposition** (note the multi-entry
`regexPattern` and the `warnings` entry), and a **FAILED** term
(nested `AND NOT` — rejected):

```json
{
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "lexiconRuleName": "lexicon_research_1",
  "totalTerms": 4,
  "passCount": 3,
  "failedCount": 1,
  "hasFailures": true,
  "engineMode": "HYPERSCAN_NATIVE",
  "hyperscanVersion": "5.4.0-2.0.0",
  "compiledAt": "2026-08-26T10:15:00.500Z",
  "processingTimeMs": 42,
  "results": [
    {
      "termId": "lexicon_research_1::1",
      "termDescription": "(manipulate*) NEAR{5} ((price) OR (spread) OR (stock))",
      "compilationStatus": "PASS",
      "regexPattern": ["manipulate\\S*", "(?:price|spread|stock)"],
      "hyperscanFlags": 1,
      "requiresExclusionCheck": false,
      "warnings": [
        "This term's term expression contains NEAR/FOLLOWEDBY structure and was split into 2 independent parts (each individually Hyperscan-validated) — see regexPattern in the response. ... see resolvedPatterns ..."
      ],
      "resolvedPatterns": "manipulate\\S* NEAR{5} (?:price|spread|stock)",
      "compiledAt": "2026-08-26T10:15:00.410Z"
    },
    {
      "termId": "lexicon_research_1::2",
      "termDescription": "tip* AND NOT (disclaimer)",
      "compilationStatus": "PASS",
      "regexPattern": ["tip\\S*"],
      "hyperscanFlags": 1,
      "requiresExclusionCheck": true,
      "exclusionRegex": ["(?:disclaimer)"],
      "warnings": [],
      "resolvedPatterns": "tip\\S* AND NOT (disclaimer)",
      "compiledAt": "2026-08-26T10:15:00.420Z"
    },
    {
      "termId": "lexicon_research_1::3",
      "termDescription": "((内幕) OR (正常) OR (的) OR (商业)) FOLLOWEDBY{10} ((活动) OR (记录))",
      "compilationStatus": "PASS",
      "regexPattern": ["(?:内幕|正常|的|商业)", "(?:活动|记录)"],
      "hyperscanFlags": 97,
      "requiresExclusionCheck": false,
      "warnings": [
        "This term's term expression contains NEAR/FOLLOWEDBY structure and was split into 2 independent parts ... see resolvedPatterns ..."
      ],
      "resolvedPatterns": "(?:内幕|正常|的|商业) FOLLOWEDBY{10} (?:活动|记录)",
      "compiledAt": "2026-08-26T10:15:00.430Z"
    },
    {
      "termId": "lexicon_research_1::4",
      "termDescription": "insider AND NOT (compliance NEAR{5} approved)",
      "compilationStatus": "FAILED",
      "translationError": "AND NOT may only appear at the top level of a term, combined with the whole term via OR/AND at most — it cannot be nested inside NEAR, FOLLOWEDBY, AND, OR, or another AND NOT (including inside parentheses) in term: 'insider AND NOT (compliance NEAR{5} approved)'. Rewrite this term with AND NOT at the outermost level instead — e.g. replace '(A AND NOT B) NEAR{n} C' with the equivalent top-level form '(A NEAR{n} C) AND NOT B' if the exclusion is meant to apply to the whole term.",
      "hyperscanFlags": 0,
      "requiresExclusionCheck": false,
      "warnings": [],
      "compiledAt": "2026-08-26T10:15:00.440Z"
    }
  ]
}
```

A term that was structurally valid but rejected by real Hyperscan (rather
than translation) instead carries `errorLog` (not `translationError`) —
the two are mutually exclusive and both are `null`/absent for `PASS`.

A term with NEAR/FOLLOWEDBY structure looks the same shape as above but
with two or more entries in `regexPattern` (never with any gap fragment
baked into any entry — the relationship lives only in `resolvedPatterns`), e.g.:

```json
"regexPattern": [
  "(?:wordA word B|wordC\\S* wordD|wordE\\S* wordF|wordG)",
  "(?:wordH\\S*|wordI wordJ\\S* wordK|wordL\\S* wordM|wordN)",
  "(?:wordO\\S*|wordP\\S* wordQ|wordR\\S* wordS|wordT)"
],
"resolvedPatterns": "(?:wordA word B|wordC\\S* wordD|wordE\\S* wordF|wordG) FOLLOWEDBY{4} (?:wordH\\S*|wordI wordJ\\S* wordK|wordL\\S* wordM|wordN) FOLLOWEDBY{4} (?:wordO\\S*|wordP\\S* wordQ|wordR\\S* wordS|wordT)"
```

### `POST /api/lexicon/compile/csv`

Multipart form field `file` (2-column CSV, optional `ruleName` query/form
param):

```
Term ID,Term Description
lexicon_research_1::1,"(manipulate*) NEAR{5} ((price) OR (spread))"
lexicon_research_1::2,"((""please don't forward"") OR (""do not share""))"
```

Response is the identical `CompileResponse` shape shown above, with
`request_id` auto-generated as a UUID (no `requestType` field — CSV terms
are always Natural Language).

### `POST /api/lexicon/compile/bundle`

Same request shape as `/compile`. Response is `application/zip`
containing:

- `{ruleName}-compile-results.json` — always present, same
  `CompileResponse`/`TermCompilationResult` shape as `/compile` **plus**
  `hyperscanExpressionId` / `requiredExpressionIds` / `excludedExpressionIds`
  / `patternMapping` on PASS terms (`requestType` and `request_id` are
  present here; `hyperscanVersion`/`processingTimeMs` are omitted).
- `{ruleName}.hdb` — the combined, serialised Hyperscan database, present
  when at least one term passed and the combined multi-pattern compile
  itself succeeded.
- `NO_DATABASE.txt` — present **instead of** the `.hdb`, when no database
  could be built (zero PASS terms, or the combined compile failed);
  explains why, and (when identifiable) names the specific term whose
  expression caused the combined compile to fail.

Per-term id fields, for one bundle request with three terms — `::1`
(simple), `::2` (`AND NOT`), `::3` (decomposed, 3 leaves). Auxiliary ids
start at `4` (`highest term number (3) + 1`) and are handed out
sequentially in term order:

```json
{
  "termId": "lexicon_research_1::1",
  "compilationStatus": "PASS",
  "regexPattern": ["manipulate\\S*"],
  "hyperscanFlags": 1,
  "requiresExclusionCheck": false,
  "resolvedPatterns": "manipulate\\S*",
  "hyperscanExpressionId": 1
}
```

```json
{
  "termId": "lexicon_research_1::2",
  "compilationStatus": "PASS",
  "regexPattern": ["tip\\S*"],
  "hyperscanFlags": 1,
  "requiresExclusionCheck": true,
  "exclusionRegex": ["(?:disclaimer)"],
  "resolvedPatterns": "tip\\S* AND NOT (disclaimer)",
  "requiredExpressionIds": [4],
  "excludedExpressionIds": [5],
  "patternMapping": "(4&!5)"
}
```

```json
{
  "termId": "lexicon_research_1::3",
  "compilationStatus": "PASS",
  "regexPattern": ["A", "B", "C"],
  "hyperscanFlags": 1,
  "requiresExclusionCheck": false,
  "resolvedPatterns": "A FOLLOWEDBY{4} B FOLLOWEDBY{4} C",
  "hyperscanExpressionId": 3,
  "patternMapping": "(6&7&8)"
}
```

For term `::1`, `hyperscanExpressionId` is the term's own number (`1`) —
predictable with no JSON lookup. For the decomposed term `::3`, the
`.hdb` genuinely contains a native `COMBINATION` expression at id `3`
(the term's own number, same predictability as the simple case) evaluating
`(6&7&8)`; ids 6–8 are the QUIET leaf expressions, drawn from the
auxiliary range. For the AND NOT term `::2`, ids 4/5 (also from the
auxiliary range — term `::2` has no `hyperscanExpressionId` of its own)
are both **plain** expressions in the `.hdb` (never QUIET, no
combination) — `patternMapping` is the only place `(4&!5)` is recorded;
the `.hdb` itself has no expression that encodes this boolean condition.

### `GET /api/lexicon/health`

```json
{
  "status": "UP",
  "engineMode": "HYPERSCAN_NATIVE",
  "hyperscanLibrary": "com.gliwka.hyperscan",
  "hyperscanVersion": "5.4.0-2.0.0",
  "springBoot": "4.0.6",
  "jdk": "21",
  "compressionMode": "GZIP request + response",
  "supportedOperators": ["OR", "AND", "AND NOT", "NOT", "NEAR{n}", "FOLLOWEDBY{n}"],
  "supportedLanguages": ["English", "Korean", "Japanese", "Chinese", "Mandarin", "Arabic", "Hebrew", "German", "Turkish", "Emoji", "Leet-speak"],
  "timestamp": "2026-08-26T10:15:00.500Z"
}
```

`supportedLanguages` here is a human-readable summary for API consumers —
actual script coverage (via `ScriptDetector`) is broader; see
[Language coverage](#language-coverage) above. `NOT` is listed as an
operator but is only ever valid immediately after `AND`, never standalone.

---

## Configuration reference

Bound from `application.yml` (`local`/`prd`/`test` profiles layered on top):

| Key | Default | Effect |
|---|---|---|
| `server.port` | `8080` (`${PORT}` on Cloud Run) | HTTP port |
| `server.compression.enabled` | `true` | Response GZIP for `application/json`/`text/plain` above `min-response-size` (1024 bytes) |
| `spring.servlet.multipart.max-file-size` | `10MB` | Hard Spring-level cap on CSV upload size |
| `lexicon.upload.max-file-size` | `10MB` | `LexiconProperties` mirror of the above (informational) |
| `lexicon.compiler.max-terms-per-request` | `1000` | **Declared but not currently enforced** — no controller/service reads this value to reject an oversized request; large requests are limited only by JVM memory and per-term processing time |
| `lexicon.hyperscan-version` | `5.4.0-2.0.0` | Reported in `/health` |
| `management.endpoints.web.exposure.include` | `health, info, metrics` | Actuator endpoints exposed (includes a Hyperscan-probe health indicator, `LexiconCompileConfig.hyperscanHealthIndicator`) |

CORS: `/api/**` allows any origin, `GET`/`POST`/`OPTIONS`, exposing
`Content-Encoding`/`Content-Length` — intended for Cloud Run inter-service
calls, not a public-internet-facing configuration.

---

## Build & test

```bash
mvn clean test        # JUnit 5, real Hyperscan native library, JDK 21 virtual threads
mvn clean package      # Spring Boot 4 executable jar
```

Genuinely compiled and run — not merely reviewed — against the real
Hyperscan native library (bundled for linux-x86_64/aarch64, osx-aarch64,
and windows-x86_64). The one file needing full Spring Test infrastructure
(`LexiconCompileControllerTest`, `MockMvc`) is reviewed by hand rather
than exercised by the automated suite, consistent with the scoping
decision made for the equivalent controller test in the other two
services in this platform.

---

## Known limitations

- **`lexicon.compiler.max-terms-per-request` is configured but not
  enforced** — see [Configuration reference](#configuration-reference).
- **Two error paths return an empty HTTP body instead of the standard
  `{status, error, timestamp}` shape**: an empty CSV upload (400) and a
  CSV/zip-build I/O failure (500) — see
  [Error response shapes](#error-response-shapes).
- **`CompileRequest.java` (the pre-`TypedCompileRequest` request model) is
  dead code** — still present in `com.db.macs3.ecomms.spectre.model` but
  referenced nowhere in `src/main`; every endpoint uses
  `TypedCompileRequest` exclusively.
- **Nested Hyperscan combinations are avoided by design, not because they
  are confirmed unsupported** — `HyperscanCombinationHandler` builds at
  most one combination expression per term, referencing only plain
  (non-combination) ids, rather than relying on unverified nested-formula
  support.
- **Adaptive gap-width reduction cannot help a term whose OR-branch width
  alone (independent of gap width) is already too large for Hyperscan** —
  the reduction floors at a zero-width gap and, if still rejected,
  surfaces Hyperscan's real error rather than pretending to have fixed it.
- **Scanner Service and Scan Engine consuming the current
  `regexPattern`/`exclusionRegex`/id-scheme shape is this
  project's own concern only** — both are separate Maven projects with
  their own, independent implementations of the AND-NOT-vs-decomposition
  decision; a change here has no compile-time link to either and requires
  a corresponding check on their side.
