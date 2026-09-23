# Lexicon Compile Service

Translates compliance lexicon terms — written in a custom, human-authored
operator language, or supplied as raw regular expressions — into
Hyperscan-validated PCRE patterns, and (through one endpoint) into a single
combined, serialised Hyperscan database that the Lexicon Scan Engine loads and
scans against directly.

|                |                                                                                      |
|----------------|--------------------------------------------------------------------------------------|
| Base package   | `com.db.macs3.ecomms.spectre`                                                        |
| Stack          | JDK 21 (virtual threads), Spring Boot 4 (parent `4.1.0` in `pom.xml`), Jakarta EE     |
| Regex engine   | Intel Hyperscan via `com.gliwka.hyperscan` **5.4.0-2.0.0** (native library bundled)   |
| Script support | ICU4J 73.2 for Unicode script detection and NFC normalisation                          |
| Deployed on    | GCP Cloud Run (`Dockerfile`, profile `cloud-run`)                                     |

This is the **upstream-most** of three services. The Lexicon Scanner Service
(consumes `/compile` and `/compile/csv`, JSON only) and the Lexicon Scan Engine
(consumes `/compile/bundle`, both the `.hdb` and the JSON) read this service's
output and never feed back into it. The three are separate Maven projects with
no shared code, so a change to the response shape or to how terms are split
needs a matching check in both — see [Consuming the output](#consuming-the-output).

---

## Table of contents

1. [Quick start](#quick-start)
2. [Endpoints at a glance](#endpoints-at-a-glance)
3. [Request format](#request-format)
4. [The operator language](#the-operator-language)
5. [How a term is compiled](#how-a-term-is-compiled)
6. [Character-based vs. token-based (word-based) terms](#character-based-vs-token-based-word-based-terms)
7. [Language and text handling](#language-and-text-handling)
8. [Hyperscan flags](#hyperscan-flags)
9. [Response format](#response-format)
10. [Consuming the output](#consuming-the-output)
11. [Endpoint reference](#endpoint-reference)
12. [Regex-type terms](#regex-type-terms)
13. [Validation rules and error catalog](#validation-rules-and-error-catalog)
14. [Warnings (log-only)](#warnings-log-only)
15. [Configuration](#configuration)
16. [Build, test and run](#build-test-and-run)
17. [Known limitations and gotchas](#known-limitations-and-gotchas)

---

## Quick start

```bash
mvn clean package
java -jar target/lexicon-compile-service-*.jar          # profile "local", port 8080
```

Compile two terms:

```bash
curl -s -X POST http://localhost:8080/api/lexicon/compile \
  -H 'Content-Type: application/json' \
  -d '{
        "request_id": "550e8400-e29b-41d4-a716-446655440000",
        "lexiconRuleName": "lexicon_research_1",
        "requestType": "Natural Language",
        "terms": [
          { "termId": "lexicon_research_1::1", "termDescription": "(righteous babe) OR (pd)" },
          { "termId": "lexicon_research_1::2", "termDescription": "(manipulate) NEAR{5} ((price) OR (spread))" }
        ]
      }'
```

Build the scan-ready database (returns a zip):

```bash
curl -s -X POST http://localhost:8080/api/lexicon/compile/bundle \
  -H 'Content-Type: application/json' -d @request.json -o lexicon_research_1-compile-bundle.zip
```

Health: `GET /api/lexicon/health` (static info) and `GET /actuator/health` (compiles a probe
pattern to prove the native library works).

---

## Endpoints at a glance

| Endpoint | Input | Output | Consumer |
|---|---|---|---|
| `POST /api/lexicon/compile` | JSON `TypedCompileRequest` (plain or gzip) | JSON `CompileResponse` | Scanner Service |
| `POST /api/lexicon/compile/csv` | multipart CSV upload | JSON `CompileResponse` (same shape) | Scanner Service |
| `POST /api/lexicon/compile/bundle` | JSON `TypedCompileRequest` | `application/zip`: results JSON + `.hdb` (or `NO_DATABASE.txt`) | Scan Engine |
| `GET /api/lexicon/health` | — | JSON engine/feature listing | — |

**HTTP 200 does not mean every term compiled.** A structurally valid request always gets 200;
a term that fails is reported inside the response with `compilationStatus: "FAILED"`. Only a
malformed *request* gets an error status — see [Validation rules](#validation-rules-and-error-catalog).

Request bodies may be gzip-compressed (`Content-Encoding: gzip`, inflated by `GzipRequestFilter`);
responses are gzip-compressed by Tomcat when the client sends `Accept-Encoding: gzip` and the body
exceeds 1 KiB.

---

## Request format

`/compile` and `/compile/bundle` take the same body (`TypedCompileRequest`); `/compile/csv` builds
the same object from the uploaded file.

```json
{
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "lexiconRuleName": "lexicon_research_1",
  "requestType": "Natural Language",
  "terms": [
    { "termId": "lexicon_research_1::1", "termDescription": "insider AND trading" }
  ]
}
```

| Field | Rule |
|---|---|
| `request_id` | required, non-blank; echoed back unchanged (a UUID is generated for CSV) |
| `lexiconRuleName` | required, non-blank; used for the bundle's file names |
| `requestType` | required; exactly `"Natural Language"` or `"Regex"` (case-sensitive) for **all** terms — mixed requests are not supported |
| `terms` | required, non-empty. There is **no** per-request term-count limit (see [Configuration](#configuration)) |
| `terms[].termId` | required, non-blank; echoed back. For `/compile/bundle` it must end in `::<n>` — see below |
| `terms[].termDescription` | required, non-blank; the operator-language expression, or a raw PCRE pattern for `"Regex"` |

Unknown JSON properties are ignored.

**Normalisation applied to every term before validation**

1. Runs of newline, carriage-return and tab characters in `termDescription` become **one space**
   (so a description that is only such characters is rejected as blank, and a term pasted from a
   multi-line source keeps its meaning).
2. For Natural Language terms only: the text is trimmed, `""` is unescaped to `"` (CSV-style), and
   the result is Unicode **NFC**-normalised.

**Term ids for `/compile/bundle`.** Every `termId` must match `<anything>::<n>` where `n` is a
non-negative integer (`lexicon_research_1::27`), and every `n` must be unique within the request.
The number becomes the term's Hyperscan expression id; a malformed or duplicate id is rejected for
the **whole** request before any term compiles (HTTP 400). `/compile` and `/compile/csv` accept any
non-blank `termId`.

**`requestType` is honoured only by `/compile/bundle`.** `/compile` validates the field but always
translates every term as Natural Language; `/compile/csv` always builds a Natural Language request.
A Regex-type request sent to `/compile` therefore fails per term (the regex is parsed as operator
language) — use `/compile/bundle` for Regex-type terms.

---

## The operator language

### Operators and precedence

| Operator | Meaning | Example |
|---|---|---|
| `OR` | any operand matches | `price OR spread` |
| `AND` | every operand present **anywhere** in the message, any order, any distance | `insider AND announcement` |
| `AND NOT (…)` / `AND (NOT (…))` | required side matches **and** the excluded side does not | `price AND NOT (legitimate)` |
| `NEAR{n}` | the two operands within `n` words/characters of each other, **either order** | `(crap OR bad) NEAR{3} (bonus OR comp)` |
| `FOLLOWEDBY{n}` | left operand, then right operand, within `n` words/characters, **that order only** | `don't FOLLOWEDBY{3} compliance` |
| `*` in a bare word | zero or more non-whitespace characters | `chimp*`, `*handler`, `*pd*` |
| `?` in a bare word | **exactly one** non-whitespace character | `he?d` (matches "held", "he'd") |
| `"…"` | quoted phrase, matched exactly; `*` and `?` inside are literal characters | `"do not share"` |
| `( … )` | grouping, at any depth | `((a) OR (b)) NEAR{2} (c)` |

Binding, tightest first: **atoms → `NEAR`/`FOLLOWEDBY` → `AND` → `AND NOT` → `OR`**. Use
parentheses whenever the grouping is not obvious.

Reserved keywords (`OR`, `AND`, `NOT`, `NEAR`, `FOLLOWEDBY`) are recognised **only in exact upper
case**; `or`, `near`, `Not` are ordinary words. A reserved word can still be used as literal text by
quoting it (`"NEAR"`).

**`NEAR{n}` / `FOLLOWEDBY{n}` distance** — `n` is a whole number from **1 to 50**, written directly
after the keyword with no whitespace and no leading zero (`NEAR{3}`; not `NEAR {3}`, `NEAR{03}`,
`NEAR{0}`, `NEAR{3,6}`, `NEAR{51}`). For a word-based script `n` is the maximum number of words
**between** the operands: adjacent operands (zero words between) always satisfy the operator. See
[Character-based vs. token-based terms](#character-based-vs-token-based-word-based-terms) for how `n`
is interpreted for other scripts.

**Unwrapped phrases.** Consecutive bare words form one literal phrase, so
`bomb this place OR blow this place up` works without parentheses; wrapping is still clearer. Words
inside a phrase are joined by exactly **one space**, so `bomb this place` does not match text with
two spaces or a line break between the words.

**Chained proximity** — `A FOLLOWEDBY{5} B FOLLOWEDBY{6} C` without parentheses is accepted and read
as `(A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C`; a warning is logged. Prefer explicit parentheses.

### `NOT`

`NOT` is a unary operator that always takes a parenthesised group and is always paired with `AND`:

```
✓ bond AND (NOT (james bond))               NOT-group as an AND operand
✓ apple AND NOT (banana)                    the glued spelling — identical meaning
✓ apple AND (NOT (apple NEAR{10} banana))   NOT may wrap any sub-expression
✗ NOT (james bond)                          nothing required precedes it
✗ price AND NOT legitimate                  the excluded side must be a parenthesised group
✗ apple AND NOT NEAR{10} banana             NOT must be immediately followed by '('
✗ apple NOT (banana)                        NOT between two expressions has no meaning
```

`NOT` that does **not** start a group is plain text: `(NOT LAUNCHING)` is the literal phrase
"NOT LAUNCHING".

`A AND NOT (B) AND NOT (C)` excludes **B or C** (either one present excludes the message).

### `AND NOT` must be the outermost operator

`AND NOT` may appear only at the root of a term — `A AND NOT (B)` where `A` can itself be any
expression. Anywhere else it is rejected with an explanation rather than silently ignored:
`(X AND NOT (Y)) NEAR{3} Z` and `P OR Q AND NOT (R)` are errors; write `(X NEAR{3} Z) AND NOT (Y)`
instead. Auto-hoisting the exclusion was deliberately rejected because it would change the term's
meaning.

### `AND` operand limit

At most **5** operands at one `AND` level. `AND` is compiled by listing every ordering of its
operands (`N!` alternatives); more than 5 reliably exceeds Hyperscan's size limit, so it is rejected
with a message to split the term.

### A `NEAR`/`FOLLOWEDBY` alternative inside `OR`

`(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)` is accepted — the proximity clause sits at the
**edge** of the `OR` list. A proximity clause with `OR` alternatives on **both** sides
(`(a) OR (b) NEAR{2} (c) OR (d)`) is rejected as ambiguous; group the intended operands
(`((a) OR (b)) NEAR{2} ((c) OR (d))`) or move the clause to the edge.

### Wildcards, phrases and punctuation

* Everything is **case-insensitive** (`CASELESS`).
* Regex metacharacters in a term are escaped and matched literally (`u.s.`, `$100`, `a+b`).
* Apostrophes, digits and punctuation are ordinary characters: `don't report`, `144 scam`, `stop!`.
* Leet-speak is just literal text (`1ns1d3r`); there is no substitution table.

### Whole-word matching

A literal is wrapped in `\b…\b` on each edge whose first/last character is an ASCII letter, digit or
underscore, so **`pd` matches the word "pd" but not the "pd" inside "updates"**:

| Term | Pattern | Matches "updates"? | Matches "pdf"? |
|---|---|---|---|
| `pd` | `\bpd\b` | no | no |
| `pd*` | `\bpd\S*` | no | yes |
| `*pd*` | `\S*pd\S*` | yes | yes |
| `u.s.` | `\bu\.s\.` | — | — |

* A wildcard edge (`*`, `?`) or a non-word edge (`$100`, `u.s.`, `#tag`) gets no `\b` on that edge.
  The wildcard is therefore how an author asks for a substring or prefix match.
* **A term containing any non-ASCII text (accents, CJK, Arabic, Hebrew, emoji, …) gets no word
  boundaries at all** — Hyperscan rejects `\b` in Unicode (UCP) mode, which such a term needs — and a
  warning is logged. `café OR pd` is a plain substring search.
* Leaves of a term that fell back to independent parts ([below](#how-a-term-is-compiled)) get a
  **leading** `\b` only, because Hyperscan rejects a native-combination sub-expression that ends in an
  assertion; such a term matches at the start of a word (`\bprice` also matches "pricey").

### Worked examples

Every pattern below is real service output (`\b` shown as emitted; JSON adds one more backslash).

| Term | `regexPattern` |
|---|---|
| `launder` | `\blaunder\b` |
| `price skimming` | `\bprice skimming\b` |
| `"please don't forward"` | `\bplease don't forward\b` |
| `(righteous babe) OR (pd)` | `(?:\brighteous babe\b\|\bpd\b)` |
| `insider AND trading` | `(?:\binsider\b[\s\S]*\btrading\b\|\btrading\b[\s\S]*\binsider\b)` |
| `contraba*` | `\bcontraba\S*` |
| `he?d kill` | `\bhe\Sd kill\b` |
| `*handler` | `\S*handler\b` |
| `keep FOLLOWEDBY{3} mouth shut` | `\bkeep\b(?:\s+\S+){0,3}\s+\bmouth shut\b` |
| `(manipulate) NEAR{5} ((price) OR (spread) OR (stock))` | `(?:\bmanipulate\b(?:\s+\S+){0,5}\s+(?:\bprice\b\|\bspread\b\|\bstock\b)\|(?:\bprice\b\|\bspread\b\|\bstock\b)(?:\s+\S+){0,5}\s+\bmanipulate\b)` |
| `(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)` | `(?:\bplain phrase\b\|(?:\bEURIBOR FIXING\b(?:\s+\S+){0,2}\s+\bTENOR\b\|\bTENOR\b(?:\s+\S+){0,2}\s+\bEURIBOR FIXING\b))` |
| `price AND NOT (legitimate)` | required `\bprice\b`, excluded `\blegitimate\b` |
| `(💰) AND price` | `(?:\x{1F4B0}[\s\S]*price\|price[\s\S]*\x{1F4B0})` (non-ASCII → no `\b`) |

---

## How a term is compiled

```
termDescription (raw text)
  → preprocess              trim, "" → ", Unicode NFC                          (TermSyntaxTranslator)
  → Tokenizer               lexical checks → token stream
  → ExpressionParser        grammar checks → Ast (Or, And, AndNot, Near, FollowedBy, Word, Phrase, QuotedPhrase)
  → PatternDecomposer       ALWAYS: independent gap-less leaves + the literal-keyword text (resolvedPatterns)
  → resolveSide()           chooses regexPattern:
                              1. one self-contained pattern with the gap embedded   (preferred)
                              2. else the decomposed leaves                         (fallback)
  → PatternCodeGenerator    Ast → PCRE; proximity gaps via MultiLanguagePatternBuilder
  → HyperscanCompiler       the real Hyperscan compiler has the final say
```

**Design principle:** no stage hands back a pattern the real Hyperscan compiler has not accepted. A
term is `PASS` because Hyperscan compiled the final text, never because the AST "looked fine".

### One pattern when safe, independent parts as the fallback

For a side containing `NEAR`/`FOLLOWEDBY` the service first tries **one** pattern with the gap
embedded, so Hyperscan itself enforces distance and order (this is the common case). It skips that
attempt when `PatternComplexityAnalyzer` predicts the pattern too large (nested proximity over wide
`OR` groups is the usual cause — compiled state count grows multiplicatively with nesting depth), and
falls back whenever real Hyperscan rejects the single pattern for any reason.

The fallback splits the side into independent, **gap-less** leaves:

```
(manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock)
  regexPattern:     ["(?:\bmanipulate|\bfront run)", "(?:\bprice|\bspread)", "\bstock"]
  resolvedPatterns: "(?:\bmanipulate|\bfront run) NEAR{5} ((?:\bprice|\bspread) NEAR{5} \bstock)"
```

The leaves match when **all** are present anywhere in the message. The distance and order between
them are **not** encoded in the patterns — they survive only as literal `NEAR{n}` / `FOLLOWEDBY{n}`
text in `resolvedPatterns`, with the author's raw distance. A consumer that needs exact proximity for
such a term must read `resolvedPatterns` and apply it. Check `regexPattern.size()`: one entry means
Hyperscan already enforced everything; several means the fallback was used.

`resolvedPatterns` keeps the authored grouping: when the **right** operand of a proximity operator is
itself a proximity expression it is wrapped in parentheses (`… NEAR{5} (… NEAR{5} …)`); the left side
never is, so a plain chain renders flat (`A FOLLOWEDBY{4} B FOLLOWEDBY{4} C`).

Two structural exceptions:

* **`NEAR`/`FOLLOWEDBY` inside an `OR`** has no leaf form (flattening an `OR` alternative into
  AND'd leaves would change its meaning), so it is always one gap-embedded pattern.
* **`AND` containing proximity** is flattened into leaves (this is lossless: `AND` already means "all
  present anywhere"). An `AND` with no proximity stays one pattern.

### Size limits and narrowing

Hyperscan's "Pattern is too large" is driven by compiled state count, not string length. The service
handles it in layers: the complexity pre-check above; a **cap on the gap width**
(`{0,29}` words, `{0,30}` characters — calibrated against the real library); **adaptive narrowing**
that trial-compiles the actual pattern and reduces the width further for wide `OR` operands; and
finally the decomposition fallback. Clamping loses precision (matches with more intervening words
than the compiled width are missed) and logs a warning.

---

## Character-based vs. token-based (word-based) terms

`NEAR{n}` / `FOLLOWEDBY{n}` mean "within `n` words", but "word" has no single meaning across scripts.
The service picks one of two gap strategies **per proximity operator**, from the script of its two
operands.

### How the script is identified

`ScriptDetector` reads every code point of the two operands with ICU4J `UScript` and reduces the
text to a `ScriptType`:

1. Non-letter ASCII (digits, spaces, punctuation, regex fragments) is ignored; ASCII letters count as
   Latin. Combining marks, zero-width joiners, directional marks and the BOM are skipped; emoji and
   symbols are ignored.
2. Greek, Cyrillic, Armenian and Georgian count as Latin; Devanagari and the other Indic scripts and
   Tibetan count as Indic; Thaana, N'Ko, Samaritan and Mandaic count as Arabic; Lao and Myanmar count
   as Thai.
3. **A single script family** is resolved first (`内幕` → CJK, `내부자` → Hangul, `ราคา` → Thai,
   `السعر` → Arabic, `מידע` → Hebrew, Indic → Devanagari, plain Latin → Latin).
4. Otherwise it is a **mixture**: any space-free script (CJK, Kana, Hangul, Thai) forces
   `MIXED_CJK`; RTL with Latin/Indic is `MIXED_RTL`; anything else (Arabic + Hebrew, Latin + Indic,
   …) is `MIXED`.

### The two gaps

| Strategy | Scripts | Gap pattern | Width |
|---|---|---|---|
| **Token (word) based** | Latin (incl. Greek/Cyrillic), Arabic, Hebrew, Devanagari and other Indic, mixed RTL + Latin/Indic | `(?:\s+\S+){0,n}\s+` | `n` words, at most **29** |
| **Character based** | CJK (Han), Kana | `[\s\S]{0,N}` | `N = n × 3 + n` = **4n** |
| | Hangul | `[\s\S]{0,N}` | `N = n × 5 + n` = **6n** |
| | Thai, Lao, Myanmar | `[\s\S]{0,N}` | `N = n × 6 + n` = **7n** |
| | any mixture containing a space-free script (`MIXED_CJK`) | `[\s\S]{0,N}` | `N = n × 4 + n` = **5n** |
| | any other mixture (`MIXED`) | `[\s\S]{0,N}` | `N = n × 6 + n` = **7n** |

* The token gap `(?:\s+\S+){0,n}\s+` needs whitespace between the operands, so it suits scripts that
  separate words with spaces. `\S+` is a run of non-whitespace, so a word with attached punctuation
  is one token. The final `\s+` means two fragments **inside one word can never match** (this is why
  `(F) FOLLOWEDBY{1} (cking)` can never catch "Fucking" — the parser logs a warning for that shape).
* The character gap `[\s\S]{0,N}` also matches whitespace, so it works whether or not the text has
  spaces (Korean chat text often omits them). The `+ n` term is a buffer for punctuation and mixed
  characters. The window is deliberately generous — slightly more false positives, fewer false
  negatives.
* `N` is capped at **30**, so the cap bites from `NEAR{8}` for CJK/Kana, `NEAR{6}` for Hangul,
  `NEAR{5}` for Thai and `MIXED`, and `NEAR{7}` for `MIXED_CJK`. Above the cap the width is clamped
  and a precision-loss warning is logged. A wide `OR` operand can force a still narrower width,
  found by trial-compiling the real pattern.
* **Arabic and Hebrew** are stored in logical order (typed/read order), and the engine works on
  stored order, so `A FOLLOWEDBY B` means "A before B" for purely RTL text with no special handling.
  For FOLLOWEDBY with *mixed* RTL and LTR operands the result matches stored order, which may differ
  from the visual order; a warning is logged.

Verified outputs:

| Term | Script | Pattern |
|---|---|---|
| `keep FOLLOWEDBY{3} mouth shut` | Latin | `\bkeep\b(?:\s+\S+){0,3}\s+\bmouth shut\b` |
| `מניפולציה NEAR{2} שוק` | Hebrew | `(?:מניפולציה(?:\s+\S+){0,2}\s+שוק\|שוק(?:\s+\S+){0,2}\s+מניפולציה)` |
| `السعر FOLLOWEDBY{2} السوق` | Arabic | `السعر(?:\s+\S+){0,2}\s+السوق` |
| `内幕 NEAR{3} 交易` | CJK | `(?:内幕[\s\S]{0,12}交易\|交易[\s\S]{0,12}内幕)` |
| `내부자 NEAR{3} 거래` | Hangul | `(?:내부자[\s\S]{0,18}거래\|거래[\s\S]{0,18}내부자)` |
| `ราคา NEAR{2} หุ้น` | Thai | `(?:ราคา[\s\S]{0,14}หุ้น\|หุ้น[\s\S]{0,14}ราคา)` |
| `insider NEAR{3} 내부자` | mixed (`MIXED_CJK`) | `(?:insider[\s\S]{0,15}내부자\|내부자[\s\S]{0,15}insider)` |
| `内幕 NEAR{10} 交易` | CJK, clamped | `(?:内幕[\s\S]{0,30}交易\|交易[\s\S]{0,30}内幕)` (raw 40 → 30) |
| `a NEAR{50} b` | Latin, clamped | `(?:\ba\b(?:\s+\S+){0,29}\s+\bb\b\|\bb\b(?:\s+\S+){0,29}\s+\ba\b)` |

### What the response tells you

The response has **no field naming the gap strategy** — read it from the pattern shape (`\s+\S+`
versus `[\s\S]{0,N}`). Clamping and narrowing warnings are log-only.

In the **fallback** case there is no gap in the patterns at all; `resolvedPatterns` carries the
author's raw `n`, un-multiplied and un-clamped, and the consumer decides how a "word" is measured (the
reference matcher in the test tree counts whitespace-delimited tokens).

---

## Language and text handling

| Aspect | Behaviour |
|---|---|
| Encoding | JSON bodies and CSV files are read as **UTF-8**; JSON and the results JSON inside the zip are written as UTF-8. Send `Content-Type: application/json; charset=UTF-8` |
| Mis-encoded input | A Natural Language term containing U+FFFD (the decoder's replacement character, e.g. `übergeh*` saved in a legacy code page) is **rejected** (`FAILED`, with a message to re-send as UTF-8) instead of compiling to a pattern that can never match |
| Unicode form | The term is NFC-normalised. Scanned text is not normalised by Hyperscan, so a consumer should NFC-normalise messages so precomposed and decomposed accents match |
| Case | Always case-insensitive. With UCP (non-ASCII terms) folding follows Unicode properties; an ASCII-only term folds ASCII letters |
| Scripts | Latin (incl. Greek, Cyrillic), Arabic, Hebrew, CJK, Kana, Hangul, Thai/Lao/Myanmar, Indic scripts, and mixtures. The `/health` list of languages is a human-readable summary; coverage follows `ScriptDetector` |
| Emoji | Emitted as `\x{HEX}` and force UTF-8 mode. A term made only of emoji/symbols has "no meaningful content" and is rejected — combine it with a word (`(💰) AND price`) |
| Non-ASCII words | Kept literally (`über…`, `café`); `*` and `?` still work (`verschwör*` → `verschwör\S*`); no `\b` (see [Whole-word matching](#whole-word-matching)) |
| Wildcards under UCP | `\S` matches any non-whitespace code point, including Arabic, Hebrew and CJK characters |
| Proximity | Word gap or character gap by script — [above](#character-based-vs-token-based-word-based-terms) |
| Whitespace | `\s` in gaps and `[\s\S]` in `AND` match line breaks; multi-word phrases need exactly one space between words |

---

## Hyperscan flags

Which flags an expression compiles with is decided by **what kind of expression it is**, never by a
caller option:

| Expression | Flags |
|---|---|
| Plain, single-pattern, non-AND-NOT term — also what every candidate pattern is validated under | `CASELESS`, `DOTALL`, `SOM_LEFTMOST` (+ `UTF8`, `UCP` when needed) |
| Required or excluded pattern of an AND NOT term | `CASELESS` (+ `UTF8`, `UCP`) |
| Decomposed leaf feeding a native COMBINATION (fallback term without AND NOT) | `CASELESS`, `QUIET` (+ `UTF8`, `UCP`) |
| The COMBINATION formula expression | `COMBINATION` only |

* **`UTF8` + `UCP` are added only when the term needs them** — any non-ASCII character or emoji.
  The bitmask is `1` (CASELESS) for ASCII-only terms and `97` (CASELESS+UTF8+UCP) otherwise.
* They must stay conditional: Hyperscan rejects `\b` in UCP mode (generated word boundaries and
  Regex-type terms use it), and UCP compiles roughly 15× slower even for ASCII patterns. They cannot
  be dropped either: non-ASCII text and `\x{XXXX}` escapes need UTF8 to compile, otherwise a pattern
  could validate yet fail the combined build with "Hexadecimal value is greater than \xFF".
* **`SOM_LEFTMOST` cannot be combined with `QUIET`** (a real Hyperscan compile error), so QUIET leaves
  never have it; plain expressions do, which lets a consumer read match offsets. AND NOT patterns
  omit it deliberately — only their presence matters.
* A `COMBINATION` expression ignores every flag except `QUIET`/`SINGLEMATCH`.
* The flag bitmask is **not** in the JSON response (`hyperscanFlags` is `@JsonIgnore`). A consumer
  compiling the patterns itself should use `CASELESS`, add `UTF8|UCP` when a pattern contains a
  non-ASCII character or a `\x{…}` escape, and use `DOTALL|SOM_LEFTMOST` for plain expressions.
* Flags for **Regex-type** terms come from the script of the pattern: `CASELESS|DOTALL` (bitmask 3)
  for a Latin pattern, plus `UTF8|UCP` (99) for any other script.

### Native COMBINATION, and why AND NOT never uses it

Hyperscan 5.0+ lets an expression be a boolean formula over other expressions' ids (`"(101&102)"`).
It is used **only** for a decomposed term without AND NOT: a positive-only formula has no negation, so
Hyperscan's eager evaluation is safe. AND NOT is never built that way: Hyperscan raises a combination
match **progressively**, as soon as its condition is true, and only *purely negative* combinations
are deferred to end of data. `R&!E` needs `R` too, so it can fire the moment `R` matches while `E`
has merely not been reached yet — before `E` could appear later in the same text. Instead every AND NOT
pattern is its own plain expression and the consumer evaluates the condition **after the whole scan**.

---

## Response format

### `CompileResponse` (every endpoint)

| Field | Present | Meaning |
|---|---|---|
| `request_id` | always | echoed (generated for CSV) |
| `lexiconRuleName` | always | |
| `requestType` | `/compile/bundle` only | `"Natural Language"` or `"Regex"` |
| `totalTerms`, `passCount`, `failedCount` | always | |
| `hasFailures` | always | `failedCount > 0` |
| `engineMode` | always | `"HYPERSCAN_NATIVE"` (no fallback engine) |
| `hyperscanVersion` | `/compile`, `/compile/csv` | `"5.4.0-2.0.0"`; absent from the bundle response |
| `compiledAt` | always | ISO-8601 instant |
| `processingTimeMs` | when non-zero | wall-clock time for the whole request |
| `results` | always | one `TermCompilationResult` per term, in request order |
| `databaseError` | `/compile/bundle`, only on a build failure | see [`/compile/bundle`](#post-apilexiconcompilebundle) |

Null fields are omitted from the JSON.

### `TermCompilationResult` (one per term)

| Field | Meaning |
|---|---|
| `termId`, `termDescription` | echoed (the description after newline/tab normalisation) |
| `compilationStatus` | `PASS` or `FAILED` |
| `regexPattern` | the required side's pattern(s) — see below. Absent for a translation failure; holds the attempted pattern for a Regex-type Hyperscan failure |
| `requiresExclusionCheck` | `true` for an AND NOT term, otherwise `false` (always present) |
| `exclusionRegex` | the excluded side's pattern(s); present only when `requiresExclusionCheck` |
| `resolvedPatterns` | the term as text with literal `NEAR{n}`/`FOLLOWEDBY{n}`/`AND NOT` — always exactly **one string** despite the plural name. Present for every PASS Natural Language term on all endpoints; absent for FAILED and Regex-type terms |
| `translationError` | why an operator-language term could not be compiled (`FAILED` only) |
| `errorLog` | Hyperscan's message for a rejected **Regex-type** pattern (`FAILED` only; at most one of `errorLog`/`translationError`) |
| `hyperscanExpressionId` | `/compile/bundle` only, terms **without** AND NOT: the term's own number |
| `requiredExpressionIds`, `excludedExpressionIds` | `/compile/bundle` only, **AND NOT** terms: one id per `regexPattern` / `exclusionRegex` entry |
| `patternMapping` | `/compile/bundle` only, terms that needed more than one id: the boolean formula over the ids |

`regexPattern` and `exclusionRegex` are always lists. **One entry** means a single self-contained
pattern; **several** mean the fallback described [above](#one-pattern-when-safe-independent-parts-as-the-fallback).
`hyperscanFlags` and the internal formula templates are not serialised, and translation
[warnings](#warnings-log-only) are logged rather than returned.

**`resolvedPatterns`** — for a side that fell back to leaves, every leaf appears in it byte-for-byte
and in order, so a consumer can correlate a leaf's match with its position. When the side compiled as
one pattern there is no such correspondence: `resolvedPatterns` still shows the structure, but
Hyperscan already enforced it inside the single pattern.

**Ids (`/compile/bundle`).** A term without AND NOT reports at **its own term number**, whether it is
one plain expression or a native COMBINATION over QUIET leaves — so a consumer that knows the number
can predict the id to watch for. Every other expression (leaves, AND NOT patterns) takes an
auxiliary id from a range starting at `max(term number) + 1`, handed out in term order, so it never
collides with a term number.

**`patternMapping`** is a formula in Hyperscan's own syntax over the term's ids — `(6&(7&8))`,
`(9&!10)`. Each side is a bare id or a parenthesised AND-join that follows the authored nesting.
For several leaves without AND NOT it mirrors the COMBINATION inside the `.hdb`; **for AND NOT it is
the only place the formula exists** — the `.hdb` never encodes it.

### One example per variation

`/compile/bundle` output for one request (ids shown are real: term numbers 1–5, so auxiliary ids
start at 6):

```json
{ "termId": "doc_rule::1", "termDescription": "launder", "compilationStatus": "PASS",
  "regexPattern": ["\\blaunder\\b"], "requiresExclusionCheck": false,
  "resolvedPatterns": "\\blaunder\\b", "hyperscanExpressionId": 1 }
```
Simple term — one plain expression at its own number.

```json
{ "termId": "doc_rule::2", "termDescription": "(manipulate) NEAR{5} ((price) OR (spread))",
  "compilationStatus": "PASS",
  "regexPattern": ["(?:\\bmanipulate\\b(?:\\s+\\S+){0,5}\\s+(?:\\bprice\\b|\\bspread\\b)|(?:\\bprice\\b|\\bspread\\b)(?:\\s+\\S+){0,5}\\s+\\bmanipulate\\b)"],
  "requiresExclusionCheck": false,
  "resolvedPatterns": "\\bmanipulate\\b NEAR{5} (?:\\bprice\\b|\\bspread\\b)",
  "hyperscanExpressionId": 2 }
```
Proximity merged into one pattern; no `patternMapping` because there is one expression.

```json
{ "termId": "doc_rule::3",
  "termDescription": "(manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock)",
  "compilationStatus": "PASS",
  "regexPattern": ["(?:\\bmanipulate|\\bfront run)", "(?:\\bprice|\\bspread)", "\\bstock"],
  "requiresExclusionCheck": false,
  "resolvedPatterns": "(?:\\bmanipulate|\\bfront run) NEAR{5} ((?:\\bprice|\\bspread) NEAR{5} \\bstock)",
  "hyperscanExpressionId": 3, "patternMapping": "(6&(7&8))" }
```
Fallback — three QUIET leaves (6, 7, 8) and a native COMBINATION at 3 evaluating `(6&(7&8))`.

```json
{ "termId": "doc_rule::4", "termDescription": "price AND NOT (legitimate)", "compilationStatus": "PASS",
  "regexPattern": ["\\bprice\\b"], "requiresExclusionCheck": true,
  "exclusionRegex": ["\\blegitimate\\b"],
  "resolvedPatterns": "\\bprice\\b AND NOT (\\blegitimate\\b)",
  "requiredExpressionIds": [9], "excludedExpressionIds": [10], "patternMapping": "(9&!10)" }
```
AND NOT — no `hyperscanExpressionId`; two plain expressions; the consumer applies `(9&!10)` after the scan.

```json
{ "termId": "doc_rule::5",
  "termDescription": "insider AND NOT ((manipulate OR front run) NEAR{5} ((price OR spread) NEAR{5} stock))",
  "compilationStatus": "PASS",
  "regexPattern": ["\\binsider\\b"], "requiresExclusionCheck": true,
  "exclusionRegex": ["(?:\\bmanipulate\\b|\\bfront run\\b)", "(?:\\bprice\\b|\\bspread\\b)", "\\bstock\\b"],
  "resolvedPatterns": "\\binsider\\b AND NOT ((?:\\bmanipulate\\b|\\bfront run\\b) NEAR{5} ((?:\\bprice\\b|\\bspread\\b) NEAR{5} \\bstock\\b))",
  "requiredExpressionIds": [11], "excludedExpressionIds": [12, 13, 14],
  "patternMapping": "(11&!(12&(13&14)))" }
```
AND NOT with a decomposed excluded side — the exclusion holds only when **all** of ids 12–14 were found.

A failed Natural Language term (HTTP 200):

```json
{ "termId": "doc_rule::2", "termDescription": "NEAR{0} x", "compilationStatus": "FAILED",
  "translationError": "NEAR{0} is invalid in term: 'NEAR{0} x' — zero is not allowed. Expected NEAR{n} where n is a whole number from 1 to 50.",
  "requiresExclusionCheck": false }
```

A failed Regex-type term (`/compile/bundle`):

```json
{ "termId": "doc_rule::3", "termDescription": "[a-z", "compilationStatus": "FAILED",
  "regexPattern": ["[a-z"],
  "errorLog": "Hyperscan compile error: Unterminated character class starting at index 0. [pattern: [a-z]",
  "requiresExclusionCheck": false }
```

---

## Consuming the output

**`/compile`, `/compile/csv` (Scanner Service style)** — compile every pattern yourself. A term
matches when **every** `regexPattern` entry matches **and** the excluded condition is *not* fully
satisfied (every `exclusionRegex` entry found, the same AND convention as the required side). If
`regexPattern` has several entries, also read `resolvedPatterns` to enforce distance and order between
them; with one entry Hyperscan already did.

**`/compile/bundle` (Scan Engine style)** — load the `.hdb` with `Database.load`, scan each message
once, and collect the set of matched expression ids:

| Term | Matched when |
|---|---|
| has `hyperscanExpressionId` | that id is in the matched set (single pattern, or the COMBINATION over leaves) |
| has `requiredExpressionIds` / `excludedExpressionIds` | **after the whole scan**: every required id is present **and** not every excluded id is present |
| several leaves, fallback | the COMBINATION id already encodes "all present"; apply `resolvedPatterns` if exact proximity between leaves matters |

An AND NOT term is not resolvable from the `.hdb` alone — the JSON is required. Match **start** offsets
are tracked only for plain single-pattern expressions (`SOM_LEFTMOST`); AND NOT patterns report presence
only, and QUIET leaves report nothing.

The reference implementation of the `resolvedPatterns` consumer logic is
`src/test/java/.../ResolvedPatternMatcher.java`, proven end-to-end by
`ResolvedPatternMatchingIntegrationTest` — a required blueprint for the other two services.

**Contract stability.** `regexPattern` / `exclusionRegex` / `resolvedPatterns` now contain `\b`;
consumers compiling or re-parsing them must accept it. A consumer must not assume every proximity term
is multi-leaf: a simple term's `hyperscanExpressionId` normally points at one complete, natively
enforced pattern, and the fallback applies only to terms too large to compile as one.

---

## Endpoint reference

### `POST /api/lexicon/compile`

Request and response as above. Always HTTP 200 for a valid request.

```json
{
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "lexiconRuleName": "lexicon_research_1",
  "totalTerms": 2, "passCount": 2, "failedCount": 0, "hasFailures": false,
  "engineMode": "HYPERSCAN_NATIVE", "hyperscanVersion": "5.4.0-2.0.0",
  "compiledAt": "2026-09-23T18:59:38.274061300Z", "processingTimeMs": 206,
  "results": [
    { "termId": "lexicon_research_1::1", "termDescription": "(righteous babe) OR (pd)",
      "compilationStatus": "PASS", "regexPattern": ["(?:\\brighteous babe\\b|\\bpd\\b)"],
      "requiresExclusionCheck": false, "resolvedPatterns": "(?:\\brighteous babe\\b|\\bpd\\b)" },
    { "termId": "lexicon_research_1::2", "termDescription": "(manipulate) NEAR{5} ((price) OR (spread))",
      "compilationStatus": "PASS", "regexPattern": ["(?:\\bmanipulate\\b(?:\\s+\\S+){0,5}\\s+(?:\\bprice\\b|\\bspread\\b)|(?:\\bprice\\b|\\bspread\\b)(?:\\s+\\S+){0,5}\\s+\\bmanipulate\\b)"],
      "requiresExclusionCheck": false, "resolvedPatterns": "\\bmanipulate\\b NEAR{5} (?:\\bprice\\b|\\bspread\\b)" }
  ]
}
```

Recommended headers: `Content-Type: application/json`; optionally `Content-Encoding: gzip` (compressed
body) and `Accept-Encoding: gzip`.

### `POST /api/lexicon/compile/csv`

Multipart form field `file` (a two-column CSV) and optional `ruleName` (defaults to the file name
without its extension). A UUID `request_id` is generated. The response has the `/compile` shape with
no `requestType`.

```
Term ID, Term Description
# comment lines and blank lines are skipped
lexicon_research_1::1, (manipulate*) NEAR{5} ((price) OR (spread))
lexicon_research_1::2, "((""please don't forward"") OR (""do not share""))"
```

| Condition | Behaviour |
|---|---|
| Header row (first cell contains "term id", any case) | detected and skipped |
| Blank row; row whose first cell starts with `#` | skipped |
| Row with fewer than 2 columns | skipped, logged — not a request failure |
| Third and further columns | ignored |
| Quoting | RFC 4180: `""` inside a quoted cell is a literal quote; cells are trimmed |
| Encoding | UTF-8, with an optional BOM (stripped) |
| Empty file | HTTP 400, empty body |
| Header-only file | HTTP 200 with zero terms |
| Larger than `spring.servlet.multipart.max-file-size` (10 MB) | HTTP 413 |

Every row is Natural Language. Term ids are not required to follow `::<n>` here.

### `POST /api/lexicon/compile/bundle`

Same request. Honours `requestType: "Regex"`. The response is `application/zip` named
`{ruleName}-compile-bundle.zip` (rule name reduced to `[a-zA-Z0-9._-]`):

| Entry | Present |
|---|---|
| `{ruleName}-compile-results.json` | always — the `CompileResponse` shape, plus the id fields, minus `hyperscanVersion` |
| `{ruleName}.hdb` | only when **every** term reached PASS and the combined build succeeded |
| `NO_DATABASE.txt` | instead of the `.hdb` when any term did not reach PASS, or none did |

**A single FAILED term blocks the whole database** — even when every other term passed — so a caller
can never receive a `.hdb` silently missing one term's coverage. `NO_DATABASE.txt` names the failed
terms (HTTP still 200):

```
No Hyperscan database file was produced because 1 of 3 term(s) in this request did not reach PASS
status: [doc_rule::3]. A combined database is only built when every term in the request compiles
successfully. See the JSON results for each term's compilationStatus and errorLog/translationError details.
```

**HTTP 500 instead of a zip** when every term PASSED but the combined multi-pattern build itself
failed (a flag or state-count interaction only visible when all expressions are compiled together):
`Content-Type: application/json`, the results JSON with `databaseError` set, no zip. A consumer must
not infer success from per-term `compilationStatus` alone.

The `.hdb` is written by `Database.save` — expression metadata (id, pattern, flags) plus the
serialised native database — so it loads directly with `Database.load(InputStream)` and needs no
separate metadata file. It is **not portable** across CPU architectures with different
instruction-set features; load it on a compatible platform.

### `GET /api/lexicon/health`

```json
{ "status": "UP", "engineMode": "HYPERSCAN_NATIVE", "hyperscanLibrary": "com.gliwka.hyperscan",
  "hyperscanVersion": "5.4.0-2.0.0", "springBoot": "4.0.6", "jdk": "21",
  "compressionMode": "GZIP request + response",
  "supportedOperators": ["OR", "AND", "AND NOT", "NOT", "NEAR{n}", "FOLLOWEDBY{n}"],
  "supportedLanguages": ["English", "Korean", "Japanese", "Chinese", "Mandarin", "Arabic", "Hebrew", "German", "Turkish", "Emoji", "Leet-speak"],
  "timestamp": "2026-09-23T18:59:39.686143800Z" }
```

A static description (the version strings are literals). `GET /actuator/health` (with components
shown) compiles a probe pattern to verify the native library.

---

## Regex-type terms

`requestType: "Regex"` (bundle endpoint) treats `termDescription` as a finished PCRE pattern:

* compiled verbatim — no operator-language translation; `NEAR{5}` is just a PCRE repetition;
* validated with real Hyperscan; a rejection is `FAILED` with Hyperscan's message in `errorLog`;
* flags: `CASELESS|DOTALL|SOM_LEFTMOST` for a Latin pattern, plus `UTF8|UCP` for any other script — so a
  Korean or Arabic regex works without the caller knowing flag values. **`\b` fails in a non-Latin
  pattern** (Hyperscan rejects it under UCP);
* always case-insensitive; `requiresExclusionCheck` is `false`; no `resolvedPatterns`; the term reports
  at its own `hyperscanExpressionId`;
* the U+FFFD guard, whole-word wrapping and normalisation apply to Natural Language terms only.

---

## Validation rules and error catalog

Validation runs in layers, each failing with a specific message. Per-term failures are reported in the
response (`FAILED`, HTTP 200); request-level failures are HTTP errors.

### 1. Request shape (HTTP 400)

Bean validation on `TypedCompileRequest`; the body is `{status, error, details[], timestamp}` with one
`"field: message"` entry per violation:

| Field | Message |
|---|---|
| `request_id` | `request_id must not be blank` |
| `lexiconRuleName` | `lexiconRuleName must not be blank` |
| `requestType` | `requestType must be exactly 'Natural Language' or 'Regex'` (when missing) |
| `terms` | `terms list must not be empty` |
| `terms[i].termId` / `terms[i].termDescription` | `… must not be blank` |

```json
{ "status": 400, "error": "Validation failed",
  "details": ["terms: terms list must not be empty"], "timestamp": "2026-09-23T18:59:39.520650700Z" }
```

### 2. Term ids on `/compile/bundle` (HTTP 400)

`termId must end with '::<n>' where n is a non-negative integer …  Malformed termId(s): [nonumber]`, or
`Every termId's term number must be unique … Duplicate(s): [term number 1 used by [x::1, y::1]]`.
Malformed ids are reported before duplicates.

### 3. Term translation (per term → `FAILED` + `translationError`)

| Rule | Example → message (abridged) |
|---|---|
| null/blank, or only symbols (needs a letter or digit) | `#@$#%$`, `💰` → `Lexicon term contains no meaningful content …` |
| U+FFFD present | `und�geh*` → `Term contains the Unicode replacement character U+FFFD … Re-send the request as UTF-8` |
| unbalanced parentheses / empty `()` | `(a NEAR{5}` → `Unmatched opening parenthesis '(' …` |
| unclosed quote | `"unclosed` → `Unclosed quoted phrase starting at position 0 …` |
| `NEAR`/`FOLLOWEDBY` shape | `NEAR {2}` → `must be immediately followed by '{n}' with no whitespace`; bare `NEAR` → `is missing its '{n}' distance value` |
| distance not 1–50 | `NEAR{0}` → `zero is not allowed`; `NEAR{51}` → `only values from 1 to 50 are allowed`; also `NEAR{-1}`, `NEAR{03}`, `NEAR{3,6}`, `NEAR{x}` |
| operator with a missing operand | `NEAR{2} (price)` → `Could not parse term … (expected a term, parenthesis, or quoted phrase)` |
| `NOT` misuse | `NOT (james bond)` → `NOT must always be combined with a preceding required expression via AND`; `price AND NOT legitimate` / `apple AND NOT NEAR{10} banana` → `NOT must always be followed immediately by a parenthesised group`; `A NOT B` → `Standalone NOT is not supported as an operator` |
| proximity sandwiched in `OR` | `(a) OR (b) NEAR{2} (c) OR (d)` → `… combined with OR at the same level, with OTHER OR alternatives on BOTH sides …` |
| more than 5 `AND` operands | `Too many AND operands at the same level (6) …` |
| `AND NOT` not at the root | `(x AND NOT (y)) NEAR{3} z` → `AND NOT may only appear at the top level of a term …` |
| a pattern Hyperscan rejects for a non-size reason | Hyperscan's own message surfaced (`… translated to a pattern Hyperscan rejected: …`) |
| a leaf with no proximity left to split that Hyperscan still rejects | `… one part ('…') was rejected by Hyperscan … reduce the number of OR-alternatives or wildcard usage` |

A pattern Hyperscan rejects as **"too large"** is *not* an error when the side has proximity structure —
it falls back to independent parts automatically.

### 4. Regex-type terms (per term → `FAILED` + `errorLog`)

Hyperscan's message, e.g. `Hyperscan compile error: Unterminated character class starting at index 0. [pattern: [a-z]`.

### 5. Bundle-level outcomes

See [`/compile/bundle`](#post-apilexiconcompilebundle): a FAILED term → no `.hdb` (200 + `NO_DATABASE.txt`); a
failed combined build → 500 with `databaseError`.

### HTTP status summary

| Situation | Status | Body |
|---|---|---|
| valid request, any mix of PASS/FAILED terms | 200 | results |
| bean-validation failure | 400 | `{status, error: "Validation failed", details[], timestamp}` |
| invalid/duplicate `termId` on `/compile/bundle` | 400 | `{status, error: <message>, timestamp}` |
| empty CSV upload | 400 | empty |
| CSV larger than the multipart limit | 413 | `{status, error: "Uploaded file exceeds maximum allowed size", timestamp}` |
| combined database build failure | 500 | results JSON with `databaseError` |
| CSV parse or zip-build I/O failure | 500 | empty |
| **malformed JSON, unsupported content type, or an unknown `requestType` value (e.g. `"Standard"`)** | **500** | `{status: 500, error: "Internal server error", timestamp}` |

The last row is current behaviour, not a design choice: the catch-all `Exception` handler also receives
the framework's own request-parsing failures, so these client errors surface as 500 instead of 400/415
(see [Known limitations](#known-limitations-and-gotchas)).

---

## Warnings (log-only)

The translator records non-fatal warnings; `LexiconCompileService` **logs them and does not return
them** (the response has no `warnings` field). Watch the service log (`WARN`, logger
`LexiconCompileService`) for:

| Warning | Meaning |
|---|---|
| Fell back to independent parts | proximity between leaves lives only in `resolvedPatterns` |
| Fallback leaves are start-of-word only | leaves are combination sub-expressions, so no trailing `\b` |
| Whole-word matching not applied | the term contains non-ASCII text (UCP) |
| Gap clamped or narrowed | requested distance exceeded the compiled width; long-distance matches are missed |
| Mixed RTL + LTR `FOLLOWEDBY` | matches stored order, which may differ from visual order |
| Chained `NEAR`/`FOLLOWEDBY` without parentheses | read as left-associative nesting |
| `NEAR{1}`/`FOLLOWEDBY{1}` with a single-character operand | likely an attempt to split one word, which can never match |

---

## Configuration

`application.yml`, with profile files layered on top. The default profile is `local`; the container
sets `SPRING_PROFILES_ACTIVE=cloud-run` (`application-prd.yml` activates the `cloud-run` profile).

| Key | Default | Effect |
|---|---|---|
| `server.port` | `8080` (`${PORT}` on Cloud Run) | HTTP port |
| `server.compression.*` | enabled, min 1024 bytes, JSON/text | response gzip |
| `server.tomcat.threads.max` / `min-spare` | 200 / 10 (20 on cloud-run) | request threads |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `10MB` | **the** limit on CSV uploads |
| `lexicon.compiler.max-terms-per-request` | 1000 (2000 on cloud-run) | bound to `LexiconProperties` but **not enforced** anywhere |
| `lexicon.upload.max-file-size`, `lexicon.hyperscan-version` | `10MB`, `5.4.0-2.0.0` | bound but not read by any code |
| `management.endpoints.web.exposure.include` | `health, info, metrics` | actuator; includes a native-library health indicator |
| `logging.level.com.db.macs3.ecomms.spectre` | `INFO` (`DEBUG` on `local`/`test`) | translator and compile logs |

CORS: `/api/**` allows any origin for `GET`/`POST`/`OPTIONS` and exposes `Content-Encoding` and
`Content-Length` — intended for Cloud Run service-to-service calls, not a public deployment.

The native Hyperscan library ships inside the jar (linux-x86_64, linux-aarch64, osx-aarch64,
windows-x86_64) and is extracted to `java.io.tmpdir`; the runtime image only needs `libstdc++6` and
`libgomp1`. Startup fails fast if the library cannot load.

---

## Build, test and run

```bash
mvn clean test          # JUnit 5 with the real Hyperscan native library, JDK 21
mvn clean package       # executable Spring Boot jar
docker build -t lexicon-compile-service .
```

The suite runs real Hyperscan end to end: it compiles terms, builds and reloads real `.hdb` files, and
scans text against them. `WholeWordMatchingTest` covers whole-word behaviour (including scans of the
reported "pd in updates" case), and `ResolvedPatternMatchingIntegrationTest` proves the `resolvedPatterns`
consumer contract.

Source layout:

| Package | Role |
|---|---|
| `controller` | REST endpoints, error mapping |
| `service` | `LexiconCompileService` (per-term pipeline), `LexiconCompileBundleService` (ids + combined database), `CsvCompileService` |
| `translator` | `Tokenizer` → `ExpressionParser` → `PatternDecomposer` / `PatternComplexityAnalyzer` → `PatternCodeGenerator` / `MultiLanguagePatternBuilder`, owned by `TermSyntaxTranslator` |
| `hyperscan` | `HyperscanCompiler` (validation, flags, combined database), `HyperscanCombinationHandler` (expression shapes and ids) |
| `util`, `model` | `ScriptDetector`, `ScriptType`, request/response types |
| `config` | gzip filter, CORS, health indicator, properties |

---

## Known limitations and gotchas

* **Client errors return HTTP 500.** Malformed JSON, a wrong content type and an unknown `requestType`
  value are caught by the catch-all exception handler and reported as `500 Internal server error`
  instead of 400/415.
* **`/compile` and `/compile/csv` ignore `requestType`.** Only `/compile/bundle` compiles Regex-type
  terms; on the other endpoints a regex is parsed as operator language and normally fails.
* **Translation warnings are not in the response** — they are logged only, so a consumer cannot tell
  from the JSON that a gap was clamped or whole-word matching was skipped. A fallback is visible as a
  multi-entry `regexPattern`.
* **Fallback terms are start-of-word only** (`\bprice` matches "pricey"), and a term with any non-ASCII
  text is a plain substring search.
* **Multi-word phrases need exactly one space** between words in the scanned text.
* **A wide gap loses precision**: word gaps are capped at 29 words, character gaps at 30 characters.
* **Adaptive narrowing cannot help a term whose `OR` width alone is too large** for Hyperscan; that is
  reported as a translation failure with Hyperscan's own message.
* **`.hdb` files are architecture-specific** — build and load on compatible CPUs.
* **`lexicon.compiler.max-terms-per-request` is not enforced**; very large requests are limited only by
  memory and time.
* **Unused code**: `CompileRequest` (the old per-term request model) and `ProximityMatch` are
  referenced nowhere in `src/main`.
* **Three services, no shared code.** The Scanner Service and Scan Engine each carry their own
  decision logic for AND NOT and decomposition; a change to the response shape, the id scheme, or how
  terms are split needs a matching change in both — there is no compile-time link and a mismatch fails
  silently (a wrong hit/no-hit decision or a wrong `term_id`).
