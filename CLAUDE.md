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
  → ExpressionParser        builds an Ast (sealed interface, 8 node types —
                             Or, And, AndNot, Near, FollowedBy, Word, Phrase,
                             QuotedPhrase)
  → PatternComplexityAnalyzer   estimates Hyperscan compiled-state cost
                                 BEFORE Hyperscan ever sees the pattern
  → PatternDecomposer        (only if over budget) splits into independent
                             leaf patterns rather than rejecting the term
  → PatternCodeGenerator     emits Hyperscan-compatible PCRE
  → HyperscanCompiler.validate()   the REAL Hyperscan compiler has final say
```

Regex-type terms skip everything before the last step — the caller-supplied
pattern is validated directly, with flags still derived automatically from
the pattern's own script content.

**Design principle, load-bearing**: no stage ever hands Hyperscan a pattern
the real Hyperscan compiler hasn't validated. A term is PASS only because
Hyperscan itself accepted the final pattern text — not because the AST
"looked fine."

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
linguistic difference, not redundant complexity.

### Complexity, decomposition, and why it's not a full solution

Hyperscan's "Pattern is too large" is driven by compiled **state count**,
not string length — and empirically, by **nesting depth** more than
OR-branch width (a NEAR whose operand is itself a NEAR/FOLLOWEDBY forces
tracking two simultaneous gap-counters — multiplicative, not additive; see
`PatternComplexityAnalyzer`'s explicit nesting-depth penalty,
`COMPLEXITY_BUDGET = 700`, calibrated against two known real Hyperscan
outcomes — a heuristic, not a proof). When a term is judged too complex,
`PatternDecomposer` breaks it into independent leaves rather than
rejecting it — but **this discards the original proximity/ordering
constraint entirely**: decomposed leaves are combined with pure boolean
AND ("all these appear somewhere"), not "in this order, within this
distance." `warnings` always carries an explicit entry when this
trade-off applies — never silently discard that field downstream.

---

## The two confirmed Hyperscan bugs this codebase has already paid for

Both are the kind of thing that looks like it could be "cleaned up" by
someone unfamiliar with the history. Don't.

### 1. `HS_FLAG_QUIET` + `HS_FLAG_SOM_LEFTMOST` is a real, confirmed-incompatible combination

Hit as a real production Hyperscan compile error early in this project's
history. `HyperscanCompiler.toExpressionFlags()` (plain expressions) always
adds `SOM_LEFTMOST`; `toSubExpressionFlags()` (QUIET expressions) never
does — structurally, per expression kind, not via any caller-supplied
toggle (an earlier `trackMatchPosition` request field existed for this and
was removed, because the two settings were never actually independent
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

---

## The cross-service JSON contract — read before changing `TermCompilationResult`

`requiresExclusionCheck: false` (simple or purely-decomposed term):
`hyperscanExpressionId` populated, always the term's own number
(`termId`'s `::<n>` suffix). `requiredExpressionIds`/`excludedExpressionIds`
null.

`requiresExclusionCheck: true` (AND NOT): `hyperscanExpressionId` **null**.
`requiredExpressionIds`/`excludedExpressionIds` populated instead — one
allocated id per pattern, none of which is the term number.

**Both downstream services parse this shape directly and depend on it
being exactly this.** The Lexicon Scan Engine's `TermExpressionMetadata`
and the Lexicon Scanner Service's own AND NOT evaluation logic both broke,
independently, the last two times this shape or its underlying semantics
changed — not because either project did anything wrong, but because this
project's scheme changed and they didn't know. **If you change this
shape, or the AND-NOT-vs-decomposition boundary, both other services need
a corresponding check, not just this one.** There is no compile-time link
across the three projects; a mismatch fails silently (wrong `term_id` in
BigQuery, or an incorrect hit/no-hit decision), not loudly.

---

## Relationship with the other two services

**Lexicon Scanner Service** consumes `/compile`/`/compile/csv` — JSON
only, never `.hdb`. It performs its **own, independent** Hyperscan
compilation from the `translatedPattern`/`exclusionPattern` lists it
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
`@ValueSource`). 290 tests passing as of the nested-AND-NOT fix. The one
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
