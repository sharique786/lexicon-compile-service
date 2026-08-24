# Lexicon Compile Service — JSON Response Reference

This document explains every attribute in the JSON response returned by the
Lexicon Compile Service.

**Applies to:**
- `POST /api/lexicon/compile`
- `POST /api/lexicon/compile/csv`
- The `{ruleName}-compile-results.json` file inside the zip returned by
  `POST /api/lexicon/compile/bundle`

All three return the exact same JSON shape described below.

## Example response

```json
{
  "lexiconRuleName": "lexicon_research_1",
  "totalTerms": 2,
  "passCount": 1,
  "failedCount": 1,
  "hasFailures": true,
  "engineMode": "HYPERSCAN_NATIVE",
  "hyperscanVersion": "5.4.0-2.0.0",
  "compiledAt": "2026-06-18T09:42:11.503Z",
  "processingTimeMs": 47,
  "results": [
    {
      "termId": "lexicon_research_1::1",
      "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))",
      "riskDriverName": "Market Abuse",
      "compilationStatus": "PASS",
      "translatedPattern": "(?:manipulate(?:\\s+\\S+){0,5}\\s+(?:price|spread|stock)|(?:price|spread|stock)(?:\\s+\\S+){0,5}\\s+manipulate)",
      "errorLog": null,
      "translationError": null,
      "hyperscanFlags": 3,
      "requiresAndPostFilter": false,
      "compiledAt": "2026-06-18T09:42:11.498Z"
    },
    {
      "termId": "lexicon_research_1::2",
      "termDescription": "NEAR{5} (price)",
      "riskDriverName": "Market Abuse",
      "compilationStatus": "FAILED",
      "translatedPattern": null,
      "errorLog": null,
      "translationError": "NEAR{5} is missing a left and/or right operand in term: 'NEAR{5} (price)'. Expected format: 'word1 NEAR{n} word2'.",
      "hyperscanFlags": 0,
      "requiresAndPostFilter": false,
      "compiledAt": "2026-06-18T09:42:11.501Z"
    }
  ]
}
```

## Top-level attributes

These describe the overall outcome of compiling every term in the request.

| Attribute | Type | Meaning |
|---|---|---|
| `lexiconRuleName` | string | The name of the lexicon rule, echoed back exactly as it was sent in the request. Identifies which rule this compile result belongs to. |
| `totalTerms` | integer | The total number of terms that were submitted for compilation, equal to the length of the `results` array. |
| `passCount` | integer | How many of those terms compiled successfully (`compilationStatus` = `"PASS"`). |
| `failedCount` | integer | How many terms failed to compile (`compilationStatus` = `"FAILED"`), for any reason. |
| `hasFailures` | boolean | A convenience flag, `true` whenever `failedCount` is greater than zero. Lets a caller check one boolean instead of inspecting `failedCount` itself. |
| `engineMode` | string | Which matching engine compiled these terms. Currently always the literal value `"HYPERSCAN_NATIVE"` — the service has no fallback engine, so this field is mostly present for future-proofing and for the scan engine to confirm engine compatibility. |
| `hyperscanVersion` | string | The version of the underlying Intel Hyperscan library (via the `com.gliwka.hyperscan` Java binding) that performed the compilation, e.g. `"5.4.0-2.0.0"`. Useful for confirming the scan engine that will later load these patterns is running a compatible Hyperscan version. |
| `compiledAt` | string (ISO-8601 timestamp) | The moment this overall response was generated. Marks when the whole batch finished, not any individual term. |
| `processingTimeMs` | integer (milliseconds) | How long the entire request took to compile, from the first term to the last. Useful for monitoring compile performance as lexicon size grows. |
| `results` | array | One entry per term submitted in the request, in the same order. See the next section for what each entry contains. |

## Per-term attributes (inside `results[]`)

Each object in the `results` array reports the outcome for exactly one term from the request.

| Attribute | Type | Meaning |
|---|---|---|
| `termId` | string | The unique identifier of this term, echoed back exactly as it was sent in the request. Used to match a result back to the term that produced it. |
| `termDescription` | string | The original, untranslated term text exactly as submitted — the operator-language expression (e.g. `"(manipulate) NEAR{5} (price)"`) or, for an NLT term, the raw pattern the caller supplied. Echoed back for traceability; this is not the compiled pattern. |
| `riskDriverName` | string or `null` | An optional label from the request (e.g. `"Market Abuse"`, `"Insider Trading"`) describing which compliance risk category this term belongs to. `null` if it wasn't supplied in the request. |
| `compilationStatus` | string — `"PASS"` or `"FAILED"` | Whether this specific term compiled successfully. `"PASS"` means the term produced a valid pattern that Hyperscan accepted. `"FAILED"` means it did not, for a reason explained by `errorLog` or `translationError` below. |
| `translatedPattern` | string or `null` | The Hyperscan-compatible PCRE pattern produced from `termDescription`. Present whenever any pattern was actually produced — both for a `"PASS"` and for a term that failed at the Hyperscan stage (the pattern that was attempted but rejected). It is `null` only when the term failed before a pattern could even be produced (see `translationError`). |
| `errorLog` | string or `null` | The error message from Hyperscan itself, returned when a produced pattern was syntactically valid PCRE but Hyperscan's compiler still rejected it (e.g. a resource limit or an unsupported construct). `null` unless this specific failure happened at the Hyperscan compile stage. |
| `translationError` | string or `null` | The error message from the term-description translator, returned when the term's text could not be turned into a pattern at all (e.g. malformed syntax such as a `NEAR{n}` operator missing one of its operands). `null` unless this specific failure happened at the translation stage, before Hyperscan was ever invoked. |
| `hyperscanFlags` | integer (bitmask) | The Hyperscan compile flags applied to `translatedPattern`, encoded as a bitmask: `1` = case-insensitive matching, `2` = allow `.` to match newlines, `32` = treat the pattern as UTF-8, `64` = use Unicode character properties (needed for non-Latin scripts). Flags are combined by adding their values — for example `3` means both `1` and `2` are set. A value of `0` is a specific signal that translation never completed (no pattern was ever produced to compute flags for). |
| `requiresAndPostFilter` | boolean | `true` when the term used an `AND` operator. Hyperscan alone cannot fully evaluate `AND` semantics, so `true` tells the downstream scan engine it must apply an additional check after a Hyperscan hit, confirming all of the `AND` operands actually appear in the same message before raising an alert. Always `false` for terms that failed to compile. |
| `compiledAt` | string (ISO-8601 timestamp) | The moment this individual term finished compiling. Distinct from the top-level `compiledAt`, which marks the end of the whole batch. |

### Reading `errorLog` and `translationError` together

These two fields are mutually exclusive: at most one of them is ever non-null for a given term, and both are always `null` when `compilationStatus` is `"PASS"`. Which one is populated tells you *where* the failure happened:

- `translationError` set, `errorLog` null — the term's own syntax was the problem (e.g. unbalanced operators, an unclosed character class). Hyperscan was never even invoked.
- `errorLog` set, `translationError` null — the term translated into a pattern successfully, but Hyperscan itself rejected that pattern.

A consumer that wants a single "what went wrong" message for a failed term should check `translationError` first, then fall back to `errorLog`.

## `compilationStatus` values

| Value | Meaning |
|---|---|
| `PASS` | The term compiled into a valid Hyperscan pattern and is ready to be used for scanning. |
| `FAILED` | The term did not produce a usable pattern. Consult `translationError` or `errorLog` for why. |
