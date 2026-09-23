# CLAUDE.md — Lexicon Compile Service

Guidance for Claude (or any future maintainer) working in this repository.
This file is oriented toward *context you need before touching code* —
especially the parts that look arbitrary but encode a real, already-paid-for
bug fix, and the parts two other services depend on precisely.

---

## What this project is

Translates compliance lexicon terms — a custom, human-authored operator
language, or raw regular expressions — into Hyperscan-validated PCRE
patterns, and (via one endpoint) into a combined, ready-to-scan Hyperscan
database. This is the **upstream-most** of three services in this
platform; the other two consume its output, never the reverse.

**Stack:** JDK 21 · Spring Boot 4.0.6 · Intel Hyperscan 5.4.0-2.0.0 (via
`com.gliwka.hyperscan-java`) · ICU4J 73.2 (script detection).

```
Lexicon Compile Service   ←  THIS PROJECT — produces .hdb + compile-results.json
Lexicon Scanner Service   ←  consumes /compile, /compile/csv (JSON only)
Lexicon Scan Engine       ←  consumes /compile/bundle (.hdb + JSON, both)
```

Three separate Maven projects, no shared code. See "Relationship with the
other two services" below — it's the section most likely to save you from
re-introducing a bug this platform has already paid to fix twice.

---

## Endpoints

| Endpoint | Produces | Consumed by |
|---|---|---|
| `POST /compile` | JSON only, per-term PASS/FAILED | Lexicon Scanner Service |
| `POST /compile/csv` | Same JSON shape, CSV input | Lexicon Scanner Service |
| `POST /compile/bundle` | ZIP: `<feature>.hdb` + `<feature>-compile-results.json` | Lexicon Scan Engine |
| `GET /health` | Liveness + supported-feature discovery | — |

All three compilation endpoints share one request type
(`TypedCompileRequest`) and one per-term result shape
(`TermCompilationResult`) — see "The cross-service JSON contract" below for
exactly which fields matter and why.

---

## Architecture: the translation pipeline

```
term description (raw text)
  → Tokenizer               lexical analysis
  → ExpressionParser        builds an Ast (sealed interface, 9 node types —
                             Or, And, AndNot, Not, Near, FollowedBy, Word,
                             Phrase, QuotedPhrase — Not is parser-internal
                             only, always folded into AndNot or rejected
                             before parsing returns; see "Standalone NOT")
  → PatternDecomposer        UNCONDITIONALLY splits at every NEAR/FOLLOWEDBY
                             boundary and builds resolvedText (literal keyword
                             rendering) in the SAME pass — see "resolvedPatterns"
                             below. Always runs; its LEAVES are only sometimes
                             what regexPattern ends up as (see next step).
  → TermSyntaxTranslator     resolveSide() DECIDES, per side, between ONE
       .resolveSide()        self-contained gap-embedded pattern (built via
                             PatternCodeGenerator directly — preferred whenever
                             safe) and PatternDecomposer's already-computed
                             leaves (the fallback, used when
                             PatternComplexityAnalyzer predicts the single
                             pattern too large, or real Hyperscan rejects it)
  → PatternCodeGenerator     emits Hyperscan-compatible PCRE — gap-embedded
                             for the single-pattern attempt above (and for the
                             OR-nested-proximity exception, which never has a
                             fallback leaf list to begin with — see below)
  → HyperscanCompiler.validate()   the REAL Hyperscan compiler has final say
```

Regex-type terms skip everything before the last step — the caller-supplied
pattern is validated directly, with flags still derived automatically from
the pattern's own script content.

**Design principle, load-bearing**: no stage ever hands Hyperscan a pattern
the real Hyperscan compiler hasn't validated. A term is PASS only because
Hyperscan itself accepted the final pattern text — not because the AST
"looked fine."

### `resolvedPatterns`: literal proximity/AND-NOT text, populated regardless of how regexPattern compiled

**Read this section, and "Simple, straightforward proximity terms compile to
ONE pattern again" below it, before touching `PatternDecomposer`,
`TermSyntaxTranslator`, or anything proximity-related — this area has been
through two major architecture swings; know which one is CURRENT.**

`NEAR{n}`/`FOLLOWEDBY{n}` compile into a single Hyperscan pattern with the
gap embedded literally (`A(?:\s+\S+){0,n}\s+B` for word-based scripts,
`A[\s\S]{0,N}B` for char-based scripts, `N = n × avgCharsPerWord`) whenever
that's safe to compile — see the next section for exactly when. This can
still, in principle, produce a pattern too large for Hyperscan (CJK/Thai/
Hangul terms multiply the author's distance by `avgCharsPerWord`; deep
NEAR-of-NEAR nesting multiplies compiled state count), so a fallback exists:
splitting into independent, gap-less leaves, combined with pure boolean AND
(natively via Hyperscan `COMBINATION` for `/compile/bundle`, or by the
caller for `/compile`/`/compile/csv`) — this discards the NEAR/FOLLOWEDBY
distance/order constraint BETWEEN the split leaves specifically.

**Whichever path `regexPattern`/`exclusionRegex` actually took,
`resolvedPatterns` is ALWAYS populated with the literal proximity/AND-NOT
structure — this is what makes the fallback's information loss recoverable.**
`PatternDecomposer.decompose(Ast, ParseContext)` runs UNCONDITIONALLY for
every side containing NEAR/FOLLOWEDBY structure — regardless of whether its
own leaves end up being used — building a `Result(List<String> leaves,
String resolvedText, String formulaTemplate)` in ONE unified recursive pass,
so the fallback leaves and `resolvedPatterns` (the SAME tree rendered with
literal `NEAR{n}`/`FOLLOWEDBY{n}`/`AND NOT` keyword text standing in for the
gap, using the author's raw, un-clamped distance) can never drift out of
sync — they come from the same walk, not two independently-maintained ones.
`TermSyntaxTranslator#resolveSide` then separately decides whether
`regexPattern` itself is this decomposition's leaves, or a single
self-contained gap-embedded pattern instead — see the next section.

```
Input: "((bash)) FOLLOWEDBY{30} ((fuck) OR (fck))"      — simple/safe: merges
  regexPattern:     ["bash(?:\s+\S+){0,30}\s+(?:fuck|fck)"]
  resolvedPatterns: "bash FOLLOWEDBY{30} (?:fuck|fck)"    (populated regardless)

Input: "(insider AND NOT ((wordA...) FOLLOWEDBY{2} (wordH...) FOLLOWEDBY{2} (wordO...)))"
  (the nested FOLLOWEDBY chain over wide OR groups is over the complexity
  budget — falls back to decomposition)
  regexPattern:     ["insider"]
  exclusionRegex:   ["(?:wordA...)", "(?:wordH...)", "(?:wordO...)"]
  resolvedPatterns: "insider AND NOT ((?:wordA...) FOLLOWEDBY{2} (?:wordH...) FOLLOWEDBY{2} (?:wordO...))"
```

A downstream Java-regex-based consumer (Lexicon Scan Engine / Lexicon
Scanner Service — **not part of this repo**) that needs to handle BOTH
shapes reads `resolvedPatterns` and re-applies the actual proximity/AND-NOT
logic itself WHENEVER `regexPattern`/`exclusionRegex` has more than one
entry (the fallback case — for the common, single-entry case, Hyperscan
already enforced the relationship natively, so `resolvedPatterns` is there
for a caller that wants to read it directly regardless, but re-deriving it
isn't necessary for correctness). See
`src/test/java/.../ResolvedPatternMatcher.java` (test tree) for a full
reference implementation of exactly that downstream technique, and
`ResolvedPatternMatchingIntegrationTest` for it exercised end-to-end
against real `TermSyntaxTranslator` output — **these two classes are a
required deliverable, explicitly requested as a blueprint for the other two
services' own eventual implementations, not incidental test coverage.**

**One case is deliberately excluded from ever having a fallback leaf list at
all — NEAR/FOLLOWEDBY nested inside `OR`** (not as `OR`'s content — as one
alternative sibling to others, e.g.
`"(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)"`, real, currently-used
functionality). This genuinely cannot be flattened into a flat AND'd leaf
list without changing what `OR` means (that would need `regexPattern` to
become a nested/tree structure, not a flat list — a materially larger
change, deliberately out of scope). This one case always compiles as a
single gap-embedded pattern — `PatternDecomposer`'s `default` arm treats a
multi-operand `Or` as one opaque leaf, whose own `generate()` call recurses
into any NEAR/FOLLOWEDBY nested inside it — there is no decomposed
alternative to fall back to for this shape, ever (unlike the general
NEAR/FOLLOWEDBY case, where decomposition is the FALLBACK, not the only
option).

`AND` is flattened (not left opaque) when one of its operands contains
NEAR/FOLLOWEDBY, for `PatternDecomposer`'s own (unconditional) leaf/text
computation — this is lossless, since `AND`'s own semantics ("all operands
co-occur anywhere, any order, unbounded distance") is already exactly
equivalent to "these leaves are all independently present somewhere." A
plain `AND` with NO nested proximity anywhere is completely unaffected —
still one self-contained permutation pattern, exactly as before. Whether
`regexPattern` actually uses this flattened leaf list, or a single merged
pattern instead, is `resolveSide`'s decision, same as any other side — see
below.

### Simple, straightforward proximity terms compile to ONE pattern again — decomposition is the fallback

**This area has been through two full architecture swings — know which one
is current before changing anything here.** Originally, decomposition into
independent leaves was a complexity-driven FALLBACK: a term compiled to one
gap-embedded pattern whenever that was safe, and only split when a heuristic
or a real Hyperscan rejection said the single pattern would be too large. A
later revision made splitting UNCONDITIONAL for any NEAR/FOLLOWEDBY
structure, specifically so `resolvedPatterns` could exist as a reliable,
always-available text representation. **That went too far**: it meant even
the overwhelming majority of simple, everyday proximity terms — a single
NEAR/FOLLOWEDBY over a couple of small OR groups, comfortably compilable as
one real Hyperscan pattern — lost Hyperscan's own native distance/order
enforcement and degraded to "these parts are all present somewhere,
independently," for no actual size-related reason. **Fixed, back to the
original design, with `resolvedPatterns` kept as a genuine addition on top**:
`TermSyntaxTranslator#resolveSide` now attempts the single gap-embedded
pattern FIRST for any side `PatternDecomposer` split into more than one leaf,
and only falls back to those leaves when it isn't safe:

1. **Cheap pre-check**: `PatternComplexityAnalyzer.isOverBudget(sideAst)` —
   skips the attempt entirely for a structure already predicted too large
   (avoids paying for a doomed Hyperscan trial-compile). This is the SAME
   analyzer, SAME `COMPLEXITY_BUDGET`, SAME nesting-depth-not-branch-width
   calibration described in "Complexity, decomposition" below — it went
   dormant for one release and is live again now.
2. **Real Hyperscan has final say beyond that**: the single pattern is
   generated (`PatternCodeGenerator.generate`, which routes NEAR/FOLLOWEDBY
   through `MultiLanguagePatternBuilder`'s script-aware gap — see the next
   section) and validated against real Hyperscan. A rejection for ANY reason
   (not just "too large" specifically) falls back to `PatternDecomposer`'s
   already-computed leaves rather than failing the term.

`resolvedPatterns` is NOT affected by which path wins — `PatternDecomposer.decompose`
still runs unconditionally regardless (see previous section), so it's always
there, whether a caller needs it (the fallback case) or not (the common,
merged case, where it's available but redundant with what Hyperscan already
enforced natively). `patternMapping`/`hyperscanExpressionId` follow directly
from `regexPattern.size()` exactly as `HyperscanCombinationHandler` already
decided (see its own class Javadoc) — a merged single pattern needs no
`COMBINATION`/`patternMapping` at all, back to being the common case again,
not the exception.

**This does NOT fix a term whose NEAR/FOLLOWEDBY structure can never match
its evident target regardless of leaf-splitting policy** — e.g. a reported
term shaped `(F) FOLLOWEDBY{1} (cking)`, evidently intended to catch the
single word "Fucking" split at the letter level. NEAR/FOLLOWEDBY are
WORD-distance operators by design (see "Character-based vs. token-based
proximity gaps" below): the gap fragment `(?:\s+\S+){0,n}\s+` always requires
at least one whitespace character, so two fragments landing inside the SAME
word can never satisfy any distance, merged into one pattern or not — this
is a term-authoring problem (the term needs to be a literal word/phrase, or
a Regex-type infix/wildcard pattern, not a proximity chain), not something
this pipeline change addresses. `ExpressionParser.rejectSandwichedProximity`'s
sibling check, `warnPossibleIntraWordSplit` (see `ExpressionParser` class
Javadoc), flags the visible signature of this specific mistake (a
single-character operand at distance 1) with a non-fatal warning.

**Confirmed-fixed regression: a NEAR/FOLLOWEDBY nested as the RIGHT operand
of another NEAR/FOLLOWEDBY used to lose its authored tree shape entirely.**
`"(manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock)"` used
to produce the fully flat `resolvedPatterns`
`"(?:manipulate|front run) NEAR{5} (?:price|spread) NEAR{5} stock"` —
indistinguishable from the LEFT-associative chain a naive left-to-right
reading would reconstruct from that same text, silently discarding which
pair the author actually grouped, even though `regexPattern` itself was
already fully correct. Fixed: `PatternDecomposer.decomposeProximity` now
wraps the RIGHT side's own rendering in one extra pair of parentheses
whenever that side is itself a `Ast.Near`/`Ast.FollowedBy` node —
`resolvedPatterns` becomes
`"(?:manipulate|front run) NEAR{5} ((?:price|spread) NEAR{5} stock)"`.
**The LEFT side is never wrapped, on purpose** — a flat, left-to-right
rendering is already exactly what `ExpressionParser`'s own implicit
left-associative chaining produces with no parentheses at all (see
"Chained NEAR/FOLLOWEDBY" below), so a chain like
`(A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C` is completely unaffected — still the
flat `"A FOLLOWEDBY{4} B FOLLOWEDBY{4} C"`. `patternMapping`/the native
`COMBINATION` formula follow the identical rule via a new, internal-only
`PatternDecomposer.Result#formulaTemplate()` (`{i}`-placeholder string,
substituted with real leaf ids by `HyperscanCombinationHandler` at
`/compile/bundle` time — see that class): for the term above,
`patternMapping` is `"(54&(55&56))"`, not the flat `"(54&55&56)"`.
`formulaTemplate` is never part of the JSON response (`@JsonIgnore` on
`TermCompilationResult`) — it exists purely to let id-assignment time
reconstruct the same grouping decomposition-time already worked out.
`ResolvedPatternMatcher` (test tree) was extended to parse a fully
parenthesised segment containing a further top-level NEAR/FOLLOWEDBY
keyword as a nested `ChainElement.Group` (recursing into its own chain)
rather than one flat leaf, and evaluates a group's occurrence as a
`{min,max}` word-index SPAN so an enclosing chain's own gap arithmetic
measures from whichever edge is nearest — see that class's own Javadoc.

### Character-based vs. token-based proximity gaps

`NEAR{n}`/`FOLLOWEDBY{n}` mean "within n words" — but "word" has no single
universal definition across scripts. `ScriptDetector` (ICU4J-based, chosen
over `java.lang.Character.UnicodeScript` for broader coverage and correct
supplementary-plane/emoji handling) classifies each operand and picks:

- **Word-based gap** (`(?:\s+\S+){0,n}\s+`) for scripts with reliable
  inter-word whitespace: Latin, Arabic, Hebrew, Devanagari.
- **Character-based gap** (`[\s\S]{0,N}`, `N = n × avgCharsPerWord`) for
  CJK/Kana/Hangul/Thai, which don't reliably use whitespace between words
  — `ScriptType.MIXED_CJK` forces this even when only one operand is
  space-free.

Don't "simplify" this to one universal strategy — it's handling a real
linguistic difference, not redundant complexity. **`MultiLanguagePatternBuilder`
is reachable from TWO call sites now**: the OR-nested-proximity exception
above (always, no fallback), and — the common case —
`TermSyntaxTranslator#resolveSide`'s single-gap-embedded-pattern attempt for
any other NEAR/FOLLOWEDBY structure, via `PatternCodeGenerator.generateNear`/
`generateFollowedBy` (see "Simple, straightforward proximity terms compile
to ONE pattern again" above). Both its static clamp (`MAX_CHAR_GAP`/
`MAX_WORD_GAP`) and its adaptive real-Hyperscan retry stay load-bearing for
both call sites — this is the actual mechanism that keeps a wide-but-safe
proximity term compiling as one pattern instead of needlessly falling back
to decomposition.

### Complexity, decomposition, and the fallback boundary between them

Hyperscan's "Pattern is too large" is driven by compiled **state count**,
not string length — and empirically, by **nesting depth** more than
OR-branch width (a NEAR whose operand is itself a NEAR/FOLLOWEDBY forces
tracking two simultaneous gap-counters — multiplicative, not additive; see
`PatternComplexityAnalyzer`'s explicit nesting-depth penalty,
`COMPLEXITY_BUDGET = 700`, calibrated against two known real Hyperscan
outcomes — a heuristic, not a proof). **`PatternComplexityAnalyzer.isOverBudget`
is `TermSyntaxTranslator#resolveSide`'s pre-check gate again** (see "Simple,
straightforward proximity terms compile to ONE pattern again" above) — it
went dormant for one release (when decomposition was unconditional) and is
live again now, exactly restoring the original two-layer decision: a cheap
heuristic pre-check, with the real Hyperscan compiler still having final say
beyond it.

**Decomposition NEVER bakes a NEAR/FOLLOWEDBY node's gap into a leaf, even
when it's the path actually used for `regexPattern`** — confirmed-fixed
regression, still true today. An earlier revision of `PatternDecomposer`
(`collectLeaves`) discarded each node's gap *entirely* — e.g.
`(A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C` decomposed to three totally
independent leaves `A`, `B`, `C`, with no trace of either `{4}` left
anywhere. A LATER fix had `PatternDecomposer.decompose()` bake each node's
own gap fragment into the START of the leaf that immediately followed it —
`[A, "(?:\s+\S+){0,4}\s+"+B, "(?:\s+\S+){0,4}\s+"+C]` — as a "safe
strengthening." **Both are gone, on purpose**: leaves are pure, gap-less
fragments, full stop — but this is NOT a silent repeat of the original
regression, because the lost information is now fully and explicitly
recovered via `resolvedPatterns`' literal keyword text (unconditionally
populated whether or not decomposition's leaves are what `regexPattern`
actually used — see above), which a downstream consumer reading the
fallback case is expected to read and apply. `warnings` always carries an
explicit entry whenever a side's single pattern wasn't safe and it fell back
to decomposition, pointing at `resolvedPatterns`.

### Wildcards in a bare (unquoted) word: `*` is zero-or-more, `?` is exactly-one

`PatternCodeGenerator.encodeWord` treats a bare word's `*` as `\S*` (zero or
more non-whitespace characters) and its `?` as `\S` (EXACTLY one
non-whitespace character) — the same glob-style convention (`?` = "any one
character") most lexicon-authoring users already expect. **Confirmed-fixed
regression**: an earlier revision escaped `?` to a literal `\?` instead,
so an author writing `I?ll kill you` — clearly intending `?` to stand in for
one substituted character (an apostrophe, a comma, a redacted character,
etc.) — got a pattern that could only ever match the literal three
characters `I?ll`, never real text like `I'll`. `?` is never a live PCRE
"optional" quantifier either way (that would need explicit escaping to use
safely, so treating it as literal was never protecting a legitimate
regex-quantifier use case). **Only for a BARE word — a double-quoted phrase
still escapes both `*` and `?` as ordinary literal characters** (unchanged):
quotes mean "match this exactly," so `"he?d"` still matches only the literal
text `he?d`, never a wildcard. Don't restore literal-`?` escaping for bare
words as a "safety" cleanup — that is the bug this section describes fixing.

### Literals match as WHOLE WORDS (`\b…\b`) — confirmed-fixed substring-match bug

**Confirmed-fixed regression**: `PatternCodeGenerator` used to emit every word,
phrase and quoted phrase as a bare literal, i.e. a plain substring search.
`(righteous babe) OR (pd)` therefore matched "pd" inside "There are following
**up**dates". `PatternCodeGenerator.withWordBoundaries` now wraps each
`Word`/`Phrase`/`QuotedPhrase` leaf in `\b` on an edge **only when that edge's
source character is an ASCII word character** (`[A-Za-z0-9_]`):

- **A wildcard edge is the author's explicit substring opt-in** — `pd*` →
  `\bpd\S*` (prefix), `*pd*` → `\S*pd\S*` (anywhere). A non-word edge
  (`$100`, `u.s.`) also gets no `\b`, since `\b` between two non-word characters
  would demand a word character that isn't there. Don't "fix" `bomb` no longer
  matching "bombing" — that is the intended, requested behavior; `bomb*` is
  how an author asks for it.
- **Terms containing ANY non-ASCII text get no boundaries at all, plus a
  non-fatal warning** (`TermSyntaxTranslator.translate`, decided once up front
  via `PatternCodeGenerator.containsNonAscii`, carried as
  `ParseContext.isWordBoundaries()`): non-ASCII text turns on UCP, and
  Hyperscan rejects `\b` in UCP mode — the same constraint the
  `toExpressionFlags` section below documents. Decided from the AST *before*
  generation because a `\b` already emitted into one leaf can't be taken back
  when a later leaf turns out to need UCP. A pure-CJK term has no ASCII edge,
  so nothing is skipped and no warning is added.
- **A TRAILING `\b` is rejected by Hyperscan inside a native `COMBINATION`**
  ("Have unordered match in sub-expressions" — verified for `\b`, `$` and
  `(?:\W|$)` alike; a LEADING `\b` is fine). So the decomposition-FALLBACK
  leaves of a non-AND-NOT term — the only leaves that become COMBINATION
  sub-expressions, here *and* in the Scanner Service — are generated with
  `ParseContext.isTrailingBoundaries() == false`: start-of-word only, with a
  warning saying so. Everything else (a single merged pattern, both sides of an
  AND NOT) is a plain expression and gets both boundaries. `translate()` runs
  `PatternDecomposer.decompose` twice for a non-AND-NOT term that splits: once
  with full boundaries (supplies `resolvedPatterns` when the merged pattern is
  used) and once combination-safe (the fallback leaves, and `resolvedPatterns`
  when they're used — keeping the leaf byte-identity guarantee).

**The adaptive gap-width trial compile must not use UCP for a `\b` pattern** — confirmed-fixed regression.
`MultiLanguagePatternBuilder.compilesUnderHyperscan` trial-compiles candidate gap widths (only once a
distance exceeds the static cap: word gap > 29, char gap > 30). It used to always trial under `UTF8+UCP`;
a whole-word ASCII pattern contains `\b`, which Hyperscan rejects under UCP, so EVERY width "failed", the gap
collapsed to `{0,0}` (an invalid repeat) and `NEAR{30}`..`NEAR{50}` silently fell back to decomposition. A pattern
containing `\b` is now trialled under the non-UCP flag set it will really compile with
(`TRIAL_COMPILE_FLAGS_ASCII`). Covered by `WholeWordMatchingTest.longProximityDistance_staysOneWholeWordPattern`.

**A term containing U+FFFD is rejected** (`TermSyntaxTranslator.translate`): the replacement character means the
term was mis-encoded before it arrived, and it would otherwise compile to a PASS pattern that can never match.

**Behaviours worth knowing (documented in README "Known limitations")**: translation warnings are logged, never
returned (`LexiconCompileService#compileTerm`); `/compile` and `/compile/csv` ignore `requestType` (only
`/compile/bundle` honours `"Regex"`); framework request-parsing failures (malformed JSON, bad `requestType`,
wrong content type) are swallowed by `GlobalExceptionHandler`'s catch-all and surface as HTTP 500.

**Cross-service impact**: `regexPattern`/`exclusionRegex`/`resolvedPatterns`
now contain `\b`. Both downstream services compile these with their own
Hyperscan/Java-regex, where `\b` is valid, but the Scanner Service must keep
its own COMBINATION leaves free of trailing assertions if it ever generates
leaves itself. `bomb` → `bombing` and similar substring hits stop matching for
every existing lexicon term — a one-time behavior change to announce.

---

## The two confirmed Hyperscan bugs this codebase has already paid for

Both are the kind of thing that looks like it could be "cleaned up" by
someone unfamiliar with the history. Don't.

### 1. `HS_FLAG_QUIET` + `HS_FLAG_SOM_LEFTMOST` is a real, confirmed-incompatible combination

Hit as a real production Hyperscan compile error early in this project's
history. `HyperscanCompiler.toExpressionFlags()` (the simple, single-pattern,
non-AND-NOT PASS-term case) always adds `SOM_LEFTMOST`; `toSubExpressionFlags()`
(pure-decomposition-leaf QUIET expressions) never does — structurally, per
expression kind, not via any caller-supplied toggle (an earlier
`trackMatchPosition` request field existed for this and was removed, because
the two settings were never actually independent
choices).

### 2. Native `HS_FLAG_COMBINATION` is unsafe for AND NOT — confirmed via Hyperscan's own documentation

**This is the single most important piece of history in this codebase.**
An earlier design compiled every AND NOT term (`A AND NOT B`) as one
native combination expression, `(R&!E)`. Confirmed broken:

- Hyperscan's own docs state a combination expression "will raise matches
  at every offset where one of its sub-expressions matches and the
  logical value of the whole expression is true" — evaluated **eagerly and
  progressively**, not once after the whole scan completes.
- Hyperscan's changelog separately documents that only **purely negative**
  combinations (satisfiable by nothing having matched at all) get deferred
  to end-of-data. `R&!E` isn't purely negative — it also needs the
  positive `R` — so it doesn't qualify. If `R` matched before `E` had even
  been reached by the scan, the combination could fire immediately,
  incorrectly, before `E` had any chance to appear later in the same text.

**The fix, current state**: `HyperscanCombinationHandler` branches strictly
on `requiresExclusionCheck`, never on decomposition:

- **AND NOT (any term, regardless of decomposition on either side)** → NO
  combination at all. Every required and excluded pattern compiles as its
  own plain, individually-reportable expression (never `QUIET`). The
  caller evaluates the boolean condition itself, after the whole scan
  completes, from the complete matched-id set.
- **Pure decomposition, no AND NOT** → **unaffected, still safe, still
  uses native `COMBINATION`** — no negation means no "not yet reached"
  ambiguity. Don't conflate these two cases; they look similar
  (both "complex terms") but only one of them was ever broken.

### 3. Nested AND NOT — a second, independent bug found by *looking* for more, not by another report

`parseParenGroup()` recurses all the way back to `parseOr()`, so AND NOT is
grammatically legal anywhere a parenthesized group is legal — e.g.
`(A AND NOT B) NEAR{5} C`, or even `X OR Y AND NOT Z` with **no
parentheses at all** (ordinary operator precedence produces the same
nested-`AndNot` AST shape). Before the fix, `translate()` only
special-cased AND NOT at the AST **root** — a nested case silently
compiled successfully, with the exclusion **completely discarded** — no
error, no warning, PASS status. `rejectNestedAndNot()` (in
`TermSyntaxTranslator`) walks the whole tree and throws a clear
`TranslationException` for any `AndNot` found anywhere except the root
(including nested within another top-level `AndNot`'s own required/excluded
sides). **This is a deliberate rejection, not a missing feature** — see
that method's Javadoc for why auto-hoisting the exclusion to the top level
was rejected as a "fix" (it would silently change what the term means).

### 4. Standalone NOT — a real unary operator now, always a group, always paired with AND

`NOT` is a genuine unary prefix operator, but with two hard constraints,
both enforced at PARSE time (`ExpressionParser`), not left to a downstream
semantic check: it must always be immediately followed by a parenthesised
group, and that group must always be a LATER operand of `AND` (there must
be at least one other, non-`NOT` operand at the same `AND` level):

```
✓ bond AND (NOT (james bond))                — NOT-group as an AND operand
✓ apple AND (NOT (apple NEAR{10} banana))    — NOT wraps an arbitrary sub-expression
✓ apple AND NOT (banana)                      — the "glued" spelling; identical shape
✗ NOT (james bond)                            — no preceding required expression
✗ apple NOT NEAR{10} banana                   — NOT directly before a proximity operator
✗ apple AND NOT NEAR{10} banana               — NOT not immediately followed by '('
```

Both spellings (`X AND (NOT (Y))` and `X AND NOT (Y)`) parse to the exact
same `Ast.AndNot(required=X, excluded=[Y])` shape `ExpressionParser` already
produced for the older `AND NOT` syntax — **no new AST node survives
parsing**, and nothing downstream (`PatternCodeGenerator`,
`HyperscanCombinationHandler`, `TermCompilationResult`, `patternMapping`,
`rejectNestedAndNot`) needed to change. `ExpressionParser.parseAtom()`
recognises `NOT '('` as an internal `Ast.Not` atom; `parseAnd()` immediately
folds every `Ast.Not` operand it collects into one `Ast.AndNot` (mirroring
the loop `parseAndNot()` already used for the glued spelling), or rejects
the whole level if that would leave no required operand. A `NOT`-group
wrapped in its own extra parentheses (`bond AND (NOT (james bond))`) defers
the fold one level up rather than rejecting immediately — see
`ExpressionParser.foldNotOperands` Javadoc for why, and
`ExpressionParser.rejectBareNot` for the explicit checks (OR alternative,
NEAR/FOLLOWEDBY operand, AND NOT's own required side, or the whole term's
root) that catch a `NOT`-group that never found an enclosing `AND` to fold
into. **This is a breaking syntax change from an earlier version**: `AND
NOT` used to accept ANY expression after it (`price AND NOT legitimate`,
no parens required) — it now REQUIRES the excluded side to be an explicit
parenthesised group (`price AND NOT (legitimate)`). Existing lexicon terms
using the old bare-word form need that one parenthesis added; the
underlying two-pattern AND NOT semantics are completely unchanged.

`NOT` starting a fresh atom that is NOT immediately followed by `(` is
still ordinary literal text, exactly as before (e.g. `(NOT LAUNCHING)`
still compiles as the literal phrase "NOT LAUNCHING") — this only changes
behavior for a bare `NOT` immediately followed by `(`, which used to be
literal text too (folded into `parseWordOrPhrase()`) and is now the
NOT-group operator instead.

### 5. NEAR/FOLLOWEDBY sandwiched between OR alternatives — rejected, not silently mismatched

A NEAR/FOLLOWEDBY operator sitting as one `OR` alternative with OTHER `OR`
alternatives on BOTH sides of it parses successfully today (each
`proximityExpr` already scopes correctly between `OR` boundaries — no
grammar ambiguity), but the RESULT relates only that one operator's own two
immediate operands to each other; every other `OR` alternative on both
sides is simply ignored by that relationship, silently:

```
"(I) OR (you) OR (they) OR ... OR (them) FOLLOWEDBY{3} (sin bin) OR (penalty box) OR sinbin"
```

only ever relates `(them)` to `(sin bin)` — `(I)` through `(her)` and
`(penalty box)`/`sinbin` are unrelated OR alternatives, almost certainly not
what an author intends when writing a long OR list with one proximity
operator buried in the middle. `ExpressionParser.parseOr()` now calls
`rejectSandwichedProximity()` once an actual `OR` has combined more than one
alternative: any alternative (other than the FIRST or LAST) that is itself
an `Ast.Near`/`Ast.FollowedBy` node throws a `TranslationException` naming
the ambiguity and suggesting two fixes — wrap every alternative meant to
share the proximity operator's scope into one explicit group on each side
(`((...) OR (...)) NEAR{n} ((...) OR (...))`), or move the proximity clause
to an EDGE of its `OR` list if it really is meant to be independent.

**This is a hard rejection, unlike chained-proximity's warning-only
treatment (`ExpressionParser`'s own "Chained NEAR/FOLLOWEDBY" section) —
the two situations only look similar.** A chained proximity operator's flat
rendering is EXACTLY what left-associative nesting already looks like (no
information lost either way, so warning-and-keep-compiling was the right
call for backward compatibility). A sandwiched proximity alternative
silently discards a relationship a flat/warned rendering can't recover —
there is no legacy-compatible middle ground, so this is rejected outright.

**A NEAR/FOLLOWEDBY alternative at either EDGE of the OR list (nothing
before it, or nothing after it) is deliberately NOT rejected** — e.g.
`"(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)"`, the existing,
documented, currently-used OR-nested-proximity exception (see
`resolvedPatterns` section above). With no OTHER alternative left over on
the side the proximity clause sits, there is nothing ambiguous to reject.

---

## Hyperscan `ExpressionFlag` scheme: three fixed cases, not per-content

Which `ExpressionFlag`s an expression gets is decided by which of **three
mutually exclusive cases** it falls into — matching the three branches
already in `HyperscanCombinationHandler.addExpressions()`:

| Case | Method | Flags |
|---|---|---|
| AND NOT — every required/excluded pattern, regardless of leaf count on either side | `toAndNotExpressionFlags(bitmask)` | `CASELESS` always; `UTF8`/`UCP` when `bitmask` indicates non-Latin content |
| A leaf from a term containing NEAR/FOLLOWEDBY structure, no AND NOT (unconditional now, not complexity-triggered — see "resolvedPatterns" above) | `toSubExpressionFlags(bitmask)` | `CASELESS`, `QUIET` always; `UTF8`/`UCP` when `bitmask` indicates non-Latin content |
| Simple, single-pattern, non-AND-NOT, error-free PASS term (also the flag set `HyperscanCompiler.validate()` always uses for the "too large" pre-check) | `toExpressionFlags(bitmask)` | `CASELESS`, `DOTALL`, `SOM_LEFTMOST` always; `UTF8`/`UCP` only when `bitmask` indicates non-Latin content |

**AND NOT deliberately does not get `SOM_LEFTMOST`** any more, even though
it would be structurally *safe* there (AND NOT patterns are plain, never
QUIET, so the QUIET+SOM_LEFTMOST incompatibility above doesn't apply to
them) — the AND NOT case is scoped to `CASELESS` (+ conditional UTF8/UCP)
regardless. Don't "restore" SOM_LEFTMOST there as a safety-motivated
cleanup; it was removed on purpose.

**UTF8/UCP are conditional on `bitmask` in ALL THREE cases, not just the
simple-term one — confirmed-fixed regression, worth knowing the shape of.**
`toAndNotExpressionFlags`/`toSubExpressionFlags` used to be fixed,
unconditional sets (`CASELESS` only / `CASELESS`+`QUIET` only) — "no
conditional bits at all, not even for non-Latin content, by design" was
this file's own previous wording for that choice. It was wrong: an AND NOT
side or a decomposed leaf containing an emoji (or any codepoint above
`0xFF`) is `\x{XXXX}`-encoded by `PatternCodeGenerator` exactly like a
simple term's would be, and that encoding needs Hyperscan's UTF8 mode to
compile at all. Because `TermSyntaxTranslator.translate()`'s own validation
step goes through `toExpressionFlags` (already UTF8-conditional) while the
real `/compile/bundle` combined-database build went through these two
methods' old fixed sets, an emoji-containing AND NOT or decomposed term
could translate to a `PASS` result — validated successfully — and then
fail combined-database compilation with `CompileErrorException: Hexadecimal
value is greater than \xFF at index 0`. Fixed by making both methods take
the same `bitmask` `toExpressionFlags` does and add `UTF8`/`UCP`
conditionally, identically. This does NOT reopen the `\b`/UCP-compile-time
regressions below — those were about `toExpressionFlags()`, which is only
ever used for a term that skipped this whole translator (Regex-type) or
compiled as one simple pattern; AND NOT sides and decomposition leaves are
always translator-generated text that never contains `\b`.

**UTF8/UCP unconditional in `toExpressionFlags()` specifically was tried
and reverted after two confirmed regressions** — this constraint is scoped
to that one method, not to whether UTF8/UCP may ever be conditional
elsewhere: (1) Hyperscan rejects `\b` (word boundary) when UCP is active
("`\b` unsupported in UCP mode"), breaking any caller-supplied Regex-type
term using it; (2) UCP mode measurably slows Hyperscan compilation even for
plain-ASCII patterns (~15x in this project's own performance test,
`LexiconCompileServiceTest.performance`). Do not make UTF8/UCP
unconditional in `toExpressionFlags()` without re-checking both of those.

---

## The cross-service JSON contract — read before changing `TermCompilationResult`

`resolvedPatterns` (added for the `resolvedPatterns` change, see above):
this term (or, for AND NOT, both sides joined by the literal keyword)
rendered with `NEAR{n}`/`FOLLOWEDBY{n}`/`AND NOT` keyword text standing in
for any gap regex. Always exactly ONE `String` (never a list, despite the
plural field name), populated for every PASS Natural-Language term across
**all three endpoints** (unlike `hyperscanExpressionId`/`patternMapping`/
`requiredExpressionIds`/`excludedExpressionIds`, which stay
`/compile/bundle`-only) — null for a FAILED term and for a Regex-type term
(which never goes through the AST this field is built from). **Byte-identity
with `regexPattern`/`exclusionRegex` entries holds ONLY when a side actually
fell back to decomposition** (`regexPattern.size() > 1`) — every leaf
substring within `resolvedPatterns` is then byte-identical to the
corresponding entry, in the same order, letting a consumer correlate a
leaf's own Hyperscan-match presence with its exact position inside this
string. **For the common case — a single merged `regexPattern` entry — there
is no such correspondence**: `resolvedPatterns` still renders the full
proximity/AND-NOT structure as text, but that text was never split into
pieces matching a leaf list, because there IS no leaf list; Hyperscan
already enforced the relationship natively inside that one pattern. A
consumer must check `regexPattern.size()` (or `patternMapping == null`)
before assuming leaf/substring correlation applies. `patternMapping` (below)
is **unchanged and additive** alongside this field, not superseded by it.

`requiresExclusionCheck: false` (simple term, or one containing
NEAR/FOLLOWEDBY structure with no AND NOT): `hyperscanExpressionId`
populated, always the term's own number (`termId`'s `::<n>` suffix) —
regardless of whether that number's expression is a single merged pattern
or a native `COMBINATION` over several decomposed leaves.
`requiredExpressionIds`/`excludedExpressionIds` null.

`requiresExclusionCheck: true` (AND NOT): `hyperscanExpressionId` **null**.
`requiredExpressionIds`/`excludedExpressionIds` populated instead — one
allocated id per pattern, none of which is the term number.

`patternMapping` (added for the Lexicon Scan Engine): a boolean formula
string over this term's expression id(s), `&`/`!`-joined the same way
Hyperscan's own `HS_FLAG_COMBINATION` formulas are — e.g. `"(5&6&7)"` for a
decomposed-fallback term, `"(8&!(9&10&11))"` for AND NOT. **Null for a
simple, single-expression term** (nothing to map) — with decomposition back
to being a fallback (see "Simple, straightforward proximity terms compile to
ONE pattern again" above), this is now the COMMON case for a NEAR/FOLLOWEDBY
term, not the exception: `patternMapping` is populated only when a side
actually needed more than one Hyperscan expression id (decomposition was
used, an AND NOT term, or both). **For a decomposed-fallback term this
mirrors a formula the `.hdb` ALSO encodes natively** (safe — no negation).
**For an AND NOT term this is the ONLY place the formula exists** — the
`.hdb` never encodes it (confirmed unsafe via eager COMBINATION evaluation,
see the AND NOT section above), so a consumer reading only the `.hdb` cannot
derive it; the Lexicon Scan Engine must read `patternMapping` from this JSON
and evaluate it itself, after the whole scan completes, against the
complete matched-id set. See `TermCompilationResult` class Javadoc
"patternMapping" and `HyperscanCombinationHandler.buildAndNotFormula`/
`sideFormula`.

**Both downstream services parse this shape directly and depend on it
being exactly this.** The Lexicon Scan Engine's `TermExpressionMetadata`
and the Lexicon Scanner Service's own AND NOT evaluation logic both broke,
independently, the last two times this shape or its underlying semantics
changed — not because either project did anything wrong, but because this
project's scheme changed and they didn't know. **If you change this
shape, or the AND-NOT-vs-decomposition boundary, both other services need
a corresponding check, not just this one.** There is no compile-time link
across the three projects; a mismatch fails silently (wrong `term_id` in
BigQuery, or an incorrect hit/no-hit decision), not loudly. **`resolvedPatterns`
and the fact that decomposition is a FALLBACK again (not the unconditional
default it briefly was) are both instances of exactly this risk** — neither
downstream service reads `resolvedPatterns` yet (it's relatively new), but
once one does, this project's `NEAR`/`FOLLOWEDBY`/`AND NOT` keyword text
format and the byte-identity guarantee (conditional now — see above) with
`regexPattern`/`exclusionRegex` become another un-linked cross-project
contract; see `ResolvedPatternMatcher` (`src/test/java/...`) for the
reference parser/evaluator this format was designed against. A downstream
service that had adapted to the brief unconditional-decomposition window
(expecting EVERY proximity term to be multi-leaf with a `patternMapping`)
needs to know decomposition is a fallback again — a simple term's
`hyperscanExpressionId` is once more likely to point at a single, complete,
natively-enforced pattern.

**`databaseError` (top-level `CompileResponse` field, `/compile/bundle`
only)**: set when EVERY term individually reached PASS normally but the
combined multi-pattern Hyperscan database build/serialisation itself then
failed (e.g. a flag/state-count interaction only visible once every PASS
expression is compiled together — not catchable by any individual term's own
validation). Null whenever the database built successfully, or whenever it
was never even ATTEMPTED — see "A single FAILED term blocks the whole
combined database" immediately below. **When this is non-null, the response
is NOT a 200 zip** — the controller returns HTTP 500 with
`Content-Type: application/json` and this same `CompileResponse` JSON
shape as the body (no `.hdb`, no zip) — see
`LexiconCompileController#compileBundle` and
`LexiconCompileBundleService.CompileBundleResult#databaseBuildFailed`. A
consumer must not infer bundle success from per-term `compilationStatus`
alone; `databaseError`/the HTTP status is the actual signal that a usable
`.hdb` exists.

**A single FAILED term blocks the whole combined database — confirmed-fixed
regression.** An earlier revision built the combined `.hdb` from whatever
subset of terms reached PASS, silently dropping any FAILED term's coverage
from the database while that term's own JSON `compilationStatus` still
correctly read FAILED — a consumer that inspected the zip's `.hdb` file
without separately cross-checking every term's `compilationStatus` could
receive a database missing an intended term's protection with no obvious
signal in the zip itself. **Fixed: `/compile/bundle` now builds a combined
database ONLY when EVERY term in the request reaches PASS.** If even ONE
term is FAILED — regardless of how many others passed — `NO_DATABASE.txt`
replaces the `.hdb` in the zip, with a note naming every failed `termId`,
via the same HTTP 200 shape the pre-existing "zero PASS terms" case already
used (this is NOT the `databaseError`/HTTP 500 case above — the combined
build is never even attempted, since it's already fully explained by the
failed term's own `compilationStatus`). See
`LexiconCompileBundleService#buildDatabasePortion`.

---

## Relationship with the other two services

**Lexicon Scanner Service** consumes `/compile`/`/compile/csv` — JSON
only, never `.hdb`. It performs its **own, independent** Hyperscan
compilation from the `regexPattern`/`exclusionRegex` lists it
receives, with its **own** copy of the AND-NOT-vs-decomposition decision
logic (not shared code — a second, parallel implementation). It hit
**the identical eager-COMBINATION bug**, independently, because it had
mirrored this project's *then-believed-correct* design — not because it
shares a codebase. If you fix another Hyperscan flag/COMBINATION issue
here, check whether the Scanner Service's own `HyperscanScanService`
needs the same fix; there's no automatic propagation.

**Lexicon Scan Engine** consumes `/compile/bundle` — both `.hdb` and JSON.
An architecture question was explicitly evaluated (see
`docs/ARCHITECTURE_DECISION_hdb_vs_runtime_compile.md` if present in this
platform's docs) — read JSON only and compile at runtime, per Spark
partition, instead — and **rejected**: it would move Hyperscan's
compilation cost from happening once, here, to happening redundantly on
every executor, and would require the Scan Engine to reimplement
`HyperscanCombinationHandler`'s decision logic a *third* time. The current
two-artifact design (compile once, centrally; downstream services
deserialize/parse cheaply) is intentional, not incidental — Hyperscan's
own documentation explicitly recommends exactly this "compile once,
distribute serialized databases to many scanning hosts" pattern.

---

## Term ID scheme

Every `termId` follows `<lexicon_rule_name>::<term_number>` platform-wide.
For `/compile/bundle` specifically, every `termId` is validated (shape +
uniqueness of the parsed number, across the whole request) *before* any
term is compiled — `LexiconCompileBundleService.validateTermIds()`,
throwing `InvalidTermIdException` naming every offending id, rather than
letting a malformed id silently collide with another term's Hyperscan
expression id.

---

## Testing

Genuinely compiled and tested — not merely reviewed — against a hand-built
but functionally faithful stub environment (real Hyperscan `Scanner`/
`Database` simulation with genuine `COMBINATION`/`QUIET` evaluation, real
JSON parsing, a real parameterized-test runner for `@ParameterizedTest`/
`@ValueSource`). 486 tests passing (real Hyperscan, including whole-word scans). The one
file needing full Spring Test infrastructure
(`LexiconCompileControllerTest`, `MockMvc`) is out of this stub
environment's scope — reviewed by hand, not compiled, consistent with the
scoping decision made for the equivalent controller test in both other
services.

`mvn clean test` / `mvn clean package` are the real, supported build
commands. The stub environment described above is session-local
verification scaffolding, not a repository fixture.

---

## Conventions

- **New/heavily-modified code uses JDK 21 idioms** — records (`Ast`'s 8
  node types, `TermCompilationResult`, `HyperscanCombinationHandler`'s
  `ExpressionAssignment`), sealed interfaces, pattern-matching `switch`.
- **`requiresExclusionCheck` is the only thing that should ever decide
  QUIET+COMBINATION vs. plain expressions** — never decomposition state
  alone. Decomposition and AND NOT are orthogonal; only one of the two
  combinations of that orthogonality (AND NOT) is unsafe.
- **A term's own Hyperscan reportable id is always its term number, except
  for AND NOT terms, which have none** — don't reintroduce a single
  `hyperscanExpressionId` for AND NOT "for consistency"; that's the bug
  this file's second section describes fixing.
- **Never assume a caller-facing flag toggle is the right fix for a
  Hyperscan flag-compatibility issue** — `trackMatchPosition`'s removal is
  the precedent: the correct answer was structural (decided by expression
  kind), not a preference exposed to the request.

---

## Version history (condensed)

1. **Original build**: translation pipeline, three endpoints, script-aware
   flag/gap selection, complexity analysis + decomposition, term ID scheme.
2. **QUIET+SOM_LEFTMOST fix**: confirmed real Hyperscan error; resolved
   structurally, `trackMatchPosition` request field removed.
3. **AND NOT via QUIET+COMBINATION**: first (believed-correct) design for
   AND NOT and complex terms.
4. **Eager-COMBINATION-evaluation fix**: confirmed broken via Hyperscan's
   own docs + a real reproduction; native COMBINATION removed for AND NOT
   entirely, `requiredExpressionIds`/`excludedExpressionIds` added. Pure
   decomposition (no AND NOT) confirmed unaffected, left on COMBINATION.
5. **Nested AND NOT fix**: found while auditing for "other complex terms"
   after (4); `rejectNestedAndNot()` added.
6. Both (4) and (5)'s shape were independently mirrored as fixes in the
   Lexicon Scanner Service, and (4)'s id-scheme change was found to have
   silently broken the Lexicon Scan Engine's own assumptions, fixed there
   via `TermExpressionMetadata`/`TermMetadataLoader`.
7. **Decomposition gap-preservation fix**: `PatternDecomposer` previously
   discarded every NEAR/FOLLOWEDBY node's gap entirely on decomposition, not
   just the cross-leaf relationship — a real regression from the
   non-decomposed pattern's own semantics. Fixed by baking each node's own
   gap fragment into the leaf that followed it in the original term text.
8. **`ExpressionFlag` scheme fix**: flags were previously derived per-term
   from script content across every case. Replaced with the three fixed
   cases above (AND NOT / decomposition leaf / simple term). UTF8/UCP were
   briefly made unconditional in the simple-term case too, then reverted
   after confirming it broke `\b`-based Regex-type terms and cost ~15x
   compile time even for pure-ASCII patterns — see that section above.
9. **`patternMapping` added**: `TermCompilationResult` gained a
   `patternMapping` field so the Lexicon Scan Engine has a place to read an
   AND NOT term's combination formula, since (per (4) above) the `.hdb`
   itself deliberately never encodes one. Also populated for
   pure-decomposition terms, mirroring the formula already written into the
   `.hdb`'s native `COMBINATION` expression there.
10. **AND NOT / decomposition-leaf UTF8 fix**: an emoji (or any codepoint
    above `0xFF`) in an AND NOT side or a decomposed leaf compiled to a
    `PASS` result — per-term validation used `toExpressionFlags`, which is
    UTF8-conditional — but then failed real `/compile/bundle` combined
    database compilation with `CompileErrorException: Hexadecimal value is
    greater than \xFF at index 0`, because `toAndNotExpressionFlags()`/
    `toSubExpressionFlags()` were fixed, unconditional flag sets that never
    added UTF8/UCP, "not even for non-Latin content, by design" — that
    "design" was the bug. Fixed by giving both methods the same `bitmask`
    parameter `toExpressionFlags` already has, adding UTF8/UCP
    conditionally, identically — see the `ExpressionFlag` scheme section
    above.
11. **`/compile/bundle` database-build-failure signalling added**: previously,
    if every term individually reached PASS/FAILED normally but the combined
    Hyperscan database build itself then failed (e.g. exactly the case (10)
    could produce before its fix, or any other combined-compile-only
    failure), the endpoint still returned HTTP 200 with a zip — JSON results
    all reading per-term PASS, a `NO_DATABASE.txt` note easy to miss next to
    them, no `.hdb`. `CompileResponse` gained a `databaseError` field, and
    `/compile/bundle` now returns HTTP 500 with the JSON (no zip) when this
    happens — see "The cross-service JSON contract" above and
    `LexiconCompileBundleService.CompileBundleResult#databaseBuildFailed`.
12. **Standalone NOT support added**: `NOT` is now a real unary prefix
    operator (`NOT` immediately followed by a parenthesised group), usable
    as a later operand of `AND` — see "Standalone NOT" above. A breaking
    syntax change: the excluded side of `AND NOT` must now always be an
    explicit parenthesised group (`price AND NOT (legitimate)`), where it
    previously accepted any bare expression (`price AND NOT legitimate`).
    Folds into the exact same `Ast.AndNot` shape at parse time — no
    downstream class changed.
13. **`resolvedPatterns` added; NEAR/FOLLOWEDBY splitting made unconditional**:
    the single biggest architectural change in this project's history — see
    "`resolvedPatterns`: NEAR/FOLLOWEDBY/AND NOT are no longer compiled into
    regex" above for the full account. `PatternDecomposer` becomes the
    unconditional, always-on path for any NEAR/FOLLOWEDBY structure
    (`PatternComplexityAnalyzer` no longer gates anything, kept dormant); no
    gap is ever baked into a leaf any more (reverting the (7) fix above, this
    time with `resolvedPatterns` fully compensating); `AND` gained lossless
    flattening for a nested-proximity operand; one case — NEAR/FOLLOWEDBY
    nested inside a multi-operand `OR` — is deliberately excluded and keeps
    the old gap-embedded behavior (`MultiLanguagePatternBuilder` stays
    connected for that one residual path, not disconnected). `TermCompilationResult`/
    `TranslationResult.Success` gained `resolvedPatterns`/`resolvedPattern`,
    populated across all three endpoints, additive alongside the unchanged
    `patternMapping`. `HyperscanCombinationHandler`'s id-allocation logic
    needed NO code change (it already discriminated purely on
    `regexPattern.size()`, never on why). A reference downstream matcher
    (`ResolvedPatternMatcher` + `ResolvedPatternMatchingIntegrationTest`,
    test tree) was added as a required deliverable proving the new field is
    actually sufficient for a Java-regex-based consumer to reconstruct
    correct match decisions — see "Relationship with the other two
    services."
14. **Nested-proximity tree shape preserved; sandwiched-OR-proximity
    rejected**: two follow-on fixes to (13). First, a NEAR/FOLLOWEDBY nested
    as the RIGHT operand of another NEAR/FOLLOWEDBY previously flattened
    into `resolvedPatterns`/`patternMapping` text indistinguishable from a
    left-associative chain, silently losing the author's actual grouping —
    see "resolvedPatterns" above (the `"(manipulate OR front run) NEAR{5}
    ((price OR spread) NEAR{5} stock)"` example). Fixed via
    `PatternDecomposer.decomposeProximity`'s right-side-wraps-in-parentheses
    rule and a new internal `PatternDecomposer.Result#formulaTemplate()` /
    `TermCompilationResult#patternFormulaTemplate()`/`#exclusionFormulaTemplate()`
    (never part of the JSON response) that lets `HyperscanCombinationHandler`
    build a `patternMapping`/native-combination formula matching that same
    tree shape at id-assignment time. `ResolvedPatternMatcher` (test tree)
    gained `ChainElement`/nested-`Group` parsing and span-based gap
    arithmetic to stay a correct reference implementation of the new format.
    Second, `ExpressionParser.parseOr()` now rejects (not merely warns about)
    a NEAR/FOLLOWEDBY alternative SANDWICHED between other `OR` alternatives
    on both sides — see "NEAR/FOLLOWEDBY sandwiched between OR alternatives"
    above — while leaving the existing, documented OR-nested-proximity
    EDGE-position exception fully intact.
15. **A single FAILED term now blocks the whole `/compile/bundle` combined
    database; `?` is now a single-character wildcard, not literal**: two
    independent fixes. First — see "A single FAILED term blocks the whole
    combined database" above — `LexiconCompileBundleService#buildDatabasePortion`
    no longer builds a `.hdb` from whatever subset of terms reached PASS
    when at least one other term FAILED; it now requires EVERY term in the
    request to PASS before attempting the combined build at all, returning
    `NO_DATABASE.txt` (HTTP 200, not `databaseError`/HTTP 500) otherwise.
    Second — see "Wildcards in a bare (unquoted) word" above —
    `PatternCodeGenerator.encodeWord` now expands a bare `?` to `\S` (exactly
    one non-whitespace character), mirroring `*`'s own `\S*` expansion,
    instead of escaping it to a literal `\?`; a quoted phrase's `?` is
    unaffected and still literal.
16. **Decomposition reverted from unconditional back to a complexity-gated
    fallback — a second full swing of the (13) architecture, this time
    keeping `resolvedPatterns` as a genuine addition rather than the reason
    splitting was unconditional.** (13) made every NEAR/FOLLOWEDBY term split
    into independent leaves regardless of complexity, specifically so
    `resolvedPatterns` would always exist — but that meant even the
    overwhelming majority of simple, safely-compilable proximity terms lost
    Hyperscan's own native distance/order enforcement for no size-related
    reason, degrading to decomposition's "these parts are all present
    somewhere, independently" for terms that never needed it. Reported via a
    real worked example: `"(F) FOLLOWEDBY{1} (((me) OR (cking) OR (d) OR
    (ing) OR (up)))"` decomposed to `regexPattern: ["F", "(?:me|cking|d|ing|up)"]`
    when it should have stayed the single merged pattern
    `"F(?:\s+\S+){0,1}\s+(?:me|cking|d|ing|up)"`. Fixed: `TermSyntaxTranslator#resolveSide`
    now attempts the single gap-embedded pattern FIRST for any side
    `PatternDecomposer` split into more than one leaf — gated by the same
    two-layer check as the original, pre-(13) design (`PatternComplexityAnalyzer.isOverBudget`
    as a cheap pre-check, live again after going dormant for one release;
    real Hyperscan validation has final say beyond that) — and falls back to
    `PatternDecomposer`'s already-computed leaves only when the single
    pattern isn't safe. `resolvedPatterns` is UNCHANGED by this — `PatternDecomposer.decompose`
    still runs unconditionally to supply it (and the fallback leaves)
    regardless of which path `resolveSide` picks; see "Simple,
    straightforward proximity terms compile to ONE pattern again" above for
    the full account. `patternMapping`/`COMBINATION` go back to being
    populated only for the fallback case, not every proximity term.
    **Does NOT fix a term whose structure can never match its evident target
    regardless of leaf-splitting policy** — e.g. `(F) FOLLOWEDBY{1} (cking)`
    intending to catch the single word "Fucking" split at the letter level:
    NEAR/FOLLOWEDBY are word-distance operators by design, so two fragments
    inside the SAME word can never satisfy any distance, merged or not — a
    term-authoring problem, not something this reversion addresses. A new,
    narrower warning (`ExpressionParser.warnPossibleIntraWordSplit`) flags
    the visible signature of that specific mistake (a single-character
    operand at distance 1) without rejecting the term.
