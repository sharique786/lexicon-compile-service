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
                             boundary (see "resolvedPatterns" below) — the
                             one exception is a NEAR/FOLLOWEDBY nested inside
                             a multi-operand OR, which still routes through:
  → PatternCodeGenerator     emits Hyperscan-compatible PCRE (gap-embedded,
                             for that one OR-nested-proximity exception only)
  → HyperscanCompiler.validate()   the REAL Hyperscan compiler has final say
```

Regex-type terms skip everything before the last step — the caller-supplied
pattern is validated directly, with flags still derived automatically from
the pattern's own script content.

**Design principle, load-bearing**: no stage ever hands Hyperscan a pattern
the real Hyperscan compiler hasn't validated. A term is PASS only because
Hyperscan itself accepted the final pattern text — not because the AST
"looked fine."

### `resolvedPatterns`: NEAR/FOLLOWEDBY/AND NOT are no longer compiled into regex

**This is the single biggest architectural change in this codebase's
history — read this section before touching `PatternDecomposer`,
`TermSyntaxTranslator`, or anything proximity-related.**

`NEAR{n}`/`FOLLOWEDBY{n}` used to be compiled into a single Hyperscan
pattern with the gap embedded literally (`A(?:\s+\S+){0,n}\s+B` for
word-based scripts, `A[\s\S]{0,N}B` for char-based scripts, `N = n ×
avgCharsPerWord`). This was fragile: CJK/Thai/Hangul terms multiplied the
author's distance by `avgCharsPerWord`, frequently exceeding what Hyperscan
could compile ("Pattern is too large"), and the fallback (splitting into
independent leaves — see the old "Complexity, decomposition" history below)
only fired when a complexity heuristic or a real Hyperscan rejection
triggered it.

**Now: NEAR/FOLLOWEDBY splitting is unconditional, and the gap is NEVER
compiled into regex for the split case — not even as a leaf prefix.**
`PatternDecomposer.decompose(Ast, ParseContext)` is the single, always-on
path for any side containing NEAR/FOLLOWEDBY structure; it returns a
`Result(List<String> leaves, String resolvedText)` built in ONE unified
recursive pass, so `regexPattern`/`exclusionRegex` (the leaves — pure,
gap-less, individually Hyperscan-validated fragments) and
`resolvedPatterns` (the SAME tree rendered with literal `NEAR{n}`/
`FOLLOWEDBY{n}`/`AND NOT` keyword text standing in for the gap, using the
author's raw, un-clamped distance) can never drift out of sync — they come
from the same walk, not two independently-maintained ones. `PatternComplexityAnalyzer`
no longer gates anything (kept in the repo, unused/dormant — see its own
class Javadoc).

```
Input: "((bash)) FOLLOWEDBY{30} ((fuck) OR (fck))"
  regexPattern:     ["bash", "(?:fuck|fck)"]
  resolvedPatterns: "bash FOLLOWEDBY{30} (?:fuck|fck)"

Input: "(insider AND NOT ((wordA...) FOLLOWEDBY{2} (wordH...) FOLLOWEDBY{2} (wordO...)))"
  regexPattern:     ["insider"]
  exclusionRegex:   ["(?:wordA...)", "(?:wordH...)", "(?:wordO...)"]
  resolvedPatterns: "insider AND NOT ((?:wordA...) FOLLOWEDBY{2} (?:wordH...) FOLLOWEDBY{2} (?:wordO...))"
```

A downstream Java-regex-based consumer (Lexicon Scan Engine / Lexicon
Scanner Service — **not part of this repo**) tokenizes `resolvedPatterns`
and re-applies the actual proximity/AND-NOT logic itself. See
`src/test/java/.../ResolvedPatternMatcher.java` (test tree) for a full
reference implementation of exactly that downstream technique, and
`ResolvedPatternMatchingIntegrationTest` for it exercised end-to-end
against real `TermSyntaxTranslator` output — **these two classes are a
required deliverable of this change, explicitly requested as a blueprint
for the other two services' own eventual implementations, not incidental
test coverage.**

**One case is deliberately excluded — NEAR/FOLLOWEDBY nested inside `OR`**
(not as `OR`'s content — as one alternative sibling to others, e.g.
`"(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)"`, real, currently-used
functionality). This genuinely cannot be flattened into a flat AND'd leaf
list without changing what `OR` means (that would need `regexPattern` to
become a nested/tree structure, not a flat list — a materially larger
change, deliberately out of scope). This one case still compiles as a
single gap-embedded pattern exactly as before —
`PatternCodeGenerator.generateNear`/`generateFollowedBy` and
`MultiLanguagePatternBuilder` (including its static clamp AND its adaptive
real-Hyperscan retry — see "Character-based vs. token-based proximity
gaps, historical" below) stay **connected**, not disconnected, reachable
ONLY via this one residual path (`PatternDecomposer`'s `default` arm
treating a multi-operand `Or` as one opaque leaf, whose own `generate()`
call recurses into any NEAR/FOLLOWEDBY nested inside it, exactly as
before). Do not remove the clamp/retry there — it is the only remaining
safety net against "Pattern is too large" for this narrower case.

`AND` is flattened (not left opaque) when one of its operands contains
NEAR/FOLLOWEDBY — this is lossless, since `AND`'s own semantics ("all
operands co-occur anywhere, any order, unbounded distance") is already
exactly equivalent to "these leaves are all independently present
somewhere." A plain `AND` with NO nested proximity anywhere is completely
unaffected — still one self-contained permutation pattern, exactly as
before.

### Character-based vs. token-based proximity gaps — now historical, except for OR-nested proximity

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
linguistic difference, not redundant complexity. **This whole mechanism
(`MultiLanguagePatternBuilder`) is only reachable now via the
OR-nested-proximity exception above** — for the unconditional-splitting
path, no gap is ever computed at all, so this script-detection/gap-strategy
logic has nothing to do there.

### Complexity, decomposition, and why it used to matter — historical

Hyperscan's "Pattern is too large" is driven by compiled **state count**,
not string length — and empirically, by **nesting depth** more than
OR-branch width (a NEAR whose operand is itself a NEAR/FOLLOWEDBY forces
tracking two simultaneous gap-counters — multiplicative, not additive; see
`PatternComplexityAnalyzer`'s explicit nesting-depth penalty,
`COMPLEXITY_BUDGET = 700`, calibrated against two known real Hyperscan
outcomes — a heuristic, not a proof). **This class no longer gates
anything** — decomposition is unconditional now (see "resolvedPatterns"
above) — but it's kept in the repo, unused/dormant, since the underlying
state-count reasoning is still correct history.

**Confirmed-fixed regression this project has now deliberately re-opened,
on purpose, with `resolvedPatterns` compensating**: an earlier revision of
`PatternDecomposer` (`collectLeaves`) discarded each NEAR/FOLLOWEDBY node's
gap *entirely* — e.g. `(A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C` decomposed to
three totally independent leaves `A`, `B`, `C`, with no trace of either
`{4}` left anywhere. A LATER fix (this section, before the
`resolvedPatterns` change) had `PatternDecomposer.decompose()` bake each
NEAR/FOLLOWEDBY node's own gap fragment into the START of the leaf that
immediately followed it — `[A, "(?:\s+\S+){0,4}\s+"+B, "(?:\s+\S+){0,4}\s+"+C]`
— as a "safe strengthening." **The `resolvedPatterns` change removes this
gap-baking again** — leaves are now pure, gap-less fragments, full stop —
but this is NOT a silent repeat of the original regression: the lost
information is now fully and explicitly recovered via `resolvedPatterns`'
literal keyword text, which a downstream consumer is expected to read and
apply (unlike the original regression, where the `{4}` was simply gone with
no replacement anywhere). `warnings` always carries an explicit entry
whenever a side split into more than one leaf, pointing at
`resolvedPatterns`.

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
(which never goes through the AST this field is built from). Every leaf
substring within it is byte-identical to the corresponding
`regexPattern`/`exclusionRegex` entry, in the same order — this is what
lets a consumer correlate a leaf's own Hyperscan-match presence with its
exact position inside this string. `patternMapping` (below) is **unchanged
and additive** alongside this field, not superseded by it — a consumer that
only needs presence/AND-NOT boolean logic can keep using `patternMapping`
exactly as before; a consumer that needs the actual proximity relationship
reads `resolvedPatterns` instead.

`requiresExclusionCheck: false` (simple term, or one containing
NEAR/FOLLOWEDBY structure with no AND NOT — the id scheme is unchanged by
splitting now firing unconditionally instead of only when over budget, see
above): `hyperscanExpressionId` populated, always the term's own number
(`termId`'s `::<n>` suffix). `requiredExpressionIds`/`excludedExpressionIds`
null.

`requiresExclusionCheck: true` (AND NOT): `hyperscanExpressionId` **null**.
`requiredExpressionIds`/`excludedExpressionIds` populated instead — one
allocated id per pattern, none of which is the term number.

`patternMapping` (added for the Lexicon Scan Engine): a boolean formula
string over this term's expression id(s), `&`/`!`-joined the same way
Hyperscan's own `HS_FLAG_COMBINATION` formulas are — e.g. `"(5&6&7)"` for a
pure-decomposition term, `"(8&!(9&10&11))"` for AND NOT. Null for a simple,
single-expression term (nothing to map). **For a pure-decomposition term
this mirrors a formula the `.hdb` ALSO encodes natively** (safe — no
negation). **For an AND NOT term this is the ONLY place the formula
exists** — the `.hdb` never encodes it (confirmed unsafe via eager
COMBINATION evaluation, see the AND NOT section above), so a consumer
reading only the `.hdb` cannot derive it; the Lexicon Scan Engine must read
`patternMapping` from this JSON and evaluate it itself, after the whole
scan completes, against the complete matched-id set. See
`TermCompilationResult` class Javadoc "patternMapping" and
`HyperscanCombinationHandler.buildAndNotFormula`/`joinFormula`.

**Both downstream services parse this shape directly and depend on it
being exactly this.** The Lexicon Scan Engine's `TermExpressionMetadata`
and the Lexicon Scanner Service's own AND NOT evaluation logic both broke,
independently, the last two times this shape or its underlying semantics
changed — not because either project did anything wrong, but because this
project's scheme changed and they didn't know. **If you change this
shape, or the AND-NOT-vs-decomposition boundary, both other services need
a corresponding check, not just this one.** There is no compile-time link
across the three projects; a mismatch fails silently (wrong `term_id` in
BigQuery, or an incorrect hit/no-hit decision), not loudly. **The
`resolvedPatterns` field is a NEW instance of exactly this risk** — neither
downstream service reads it yet (it's brand new), but once one does, this
project's `NEAR`/`FOLLOWEDBY`/`AND NOT` keyword text format and the
byte-identity guarantee with `regexPattern`/`exclusionRegex` become another
un-linked cross-project contract; see `ResolvedPatternMatcher`
(`src/test/java/...`) for the reference parser/evaluator this format was
designed against.

**`databaseError` (top-level `CompileResponse` field, `/compile/bundle`
only)**: set when every term individually reached PASS/FAILED normally but
the combined multi-pattern Hyperscan database build/serialisation itself
then failed (e.g. a flag/state-count interaction only visible once every
PASS expression is compiled together — not catchable by any individual
term's own validation). Null whenever the database built successfully, or
whenever it was never expected to (e.g. zero PASS terms — already fully
explained by each term's own `compilationStatus`). **When this is non-null,
the response is NOT a 200 zip** — the controller returns HTTP 500 with
`Content-Type: application/json` and this same `CompileResponse` JSON
shape as the body (no `.hdb`, no zip) — see
`LexiconCompileController#compileBundle` and
`LexiconCompileBundleService.CompileBundleResult#databaseBuildFailed`. A
consumer must not infer bundle success from per-term `compilationStatus`
alone; `databaseError`/the HTTP status is the actual signal that a usable
`.hdb` exists.

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
`@ValueSource`). 449+ tests passing as of the `resolvedPatterns` change. The one
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
