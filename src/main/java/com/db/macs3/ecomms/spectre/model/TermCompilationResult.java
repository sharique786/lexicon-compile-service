package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Compilation outcome for a single lexicon term.
 *
 * <h2>Null field semantics</h2>
 * <ul>
 *   <li>{@code translatedPattern} — null/absent only when translation failed before any
 *       pattern could be produced (see {@code translationError}); for a PASS term, always
 *       present with at least one entry — see below</li>
 *   <li>{@code errorLog} — non-null only for Hyperscan-stage failures</li>
 *   <li>{@code translationError} — non-null only for translation-stage failures</li>
 *   <li>{@code exclusionPattern} — non-null only when {@code requiresExclusionCheck} is true</li>
 *   <li>{@code hyperscanExpressionId} — populated only on a {@code /compile/bundle}
 *       response, and only for a term that does NOT require an exclusion check
 *       (simple or purely decomposed). Null for {@code /compile}/{@code /compile/csv},
 *       and null for an AND NOT term even on {@code /compile/bundle} — see
 *       {@code requiredExpressionIds}/{@code excludedExpressionIds} below and
 *       {@code HyperscanCombinationHandler} class Javadoc for why.</li>
 *   <li>{@code requiredExpressionIds} / {@code excludedExpressionIds} — populated only
 *       on a {@code /compile/bundle} response, and only for an AND NOT term. Null
 *       otherwise (including null for a non-AND-NOT term on {@code /compile/bundle},
 *       which uses {@code hyperscanExpressionId} instead).</li>
 *   <li>{@code patternMapping} — populated only on a {@code /compile/bundle}
 *       response, and only when the term needed MORE than one Hyperscan
 *       expression id to represent it (a pure-decomposition term, an AND NOT
 *       term, or both at once). Null for a simple term (single
 *       {@code hyperscanExpressionId}, nothing to map) and null outside
 *       {@code /compile/bundle} — see "{@code patternMapping}: the logical
 *       formula, independent of whether the {@code .hdb} itself encodes it"
 *       below.</li>
 * </ul>
 * At most one of {@code errorLog} / {@code translationError} is non-null.
 * Both are null when {@code compilationStatus} is {@code PASS}.
 *
 * <h2>{@code translatedPattern} and {@code exclusionPattern} are always lists</h2>
 * <p>Every lexicon term — however structurally complex, and whether or not
 * it uses {@code AND NOT} — is represented uniformly: {@code translatedPattern}
 * holds the required side's independently Hyperscan-valid pattern(s), one
 * entry for a term simple enough to compile as a single pattern, two or
 * more for a term too structurally complex for that (see
 * {@code PatternComplexityAnalyzer} / {@code PatternDecomposer}) — decomposed
 * into independent leaf patterns instead of being rejected outright. There
 * is no separate "was this decomposed" boolean any more: a caller checks
 * {@code translatedPattern.size()}. The same applies to
 * {@code exclusionPattern} for an AND NOT term's excluded side.
 *
 * <p><b>Multiple entries are a real precision trade-off — always check
 * {@code warnings}.</b> Decomposition discards the original NEAR/FOLLOWEDBY
 * ordering/distance constraint BETWEEN leaf patterns: the leaves are
 * combined with pure boolean AND ("all of these appear somewhere in the
 * message"), not with any positional relationship to EACH OTHER. Each leaf
 * after the first still carries its own originating NEAR/FOLLOWEDBY node's
 * gap fragment as a literal prefix in its own pattern text (so it can never
 * match with nothing preceding it — e.g. as the message's first token), but
 * that gap is no longer anchored to the SPECIFIC leaf that preceded it in
 * the original term — only the cross-leaf relationship is lost, not the gap
 * width itself. {@code warnings} always carries an explicit entry whenever
 * this trade-off applies to either side, so no caller can silently treat a
 * decomposed match as a genuine proximity match.
 *
 * <h2>AND NOT has two different correct implementations, for two different callers</h2>
 * <p>{@code translatedPattern} and {@code exclusionPattern} are independently
 * Hyperscan-valid pattern lists (Hyperscan cannot express "absent from the
 * whole message" in a single pattern — no negative lookaround support).
 * What a caller does with them differs by which endpoint it used:
 *
 * <ul>
 *   <li><b>{@code /compile} and {@code /compile/csv}</b> — the caller (e.g.
 *       the Lexicon Scanner Service) reads the JSON, compiles every pattern
 *       in both lists itself, and combines the boolean results in
 *       application code: matched iff EVERY entry of {@code translatedPattern}
 *       matches AND the excluded condition (every entry of {@code exclusionPattern}
 *       found — pure AND, same convention as the required side) is NOT fully
 *       satisfied.</li>
 *   <li><b>{@code /compile/bundle}</b> — for a term that does NOT require an
 *       exclusion check, every pattern in {@code translatedPattern} is compiled
 *       into the combined database as a QUIET sub-expression feeding a native
 *       Hyperscan LOGICAL COMBINATION ({@code HS_FLAG_COMBINATION}) at
 *       {@code hyperscanExpressionId} — Hyperscan itself evaluates the full
 *       "all parts present" condition natively. <b>For an AND NOT term, this
 *       native-combination approach is NOT used</b> — confirmed unreliable by
 *       Hyperscan's own documented eager, progressive combination evaluation
 *       (a formula mixing a positive requirement with a negation can fire
 *       before the negated pattern has had a chance to appear later in the
 *       same text). Instead, every required and excluded pattern compiles as
 *       its own independently reportable plain expression
 *       ({@code requiredExpressionIds} / {@code excludedExpressionIds}), and
 *       the caller evaluates the boolean condition itself AFTER the whole
 *       scan completes, using the complete match-id set
 *       {@code Scanner.scan()} returns. See
 *       {@code HyperscanCombinationHandler} and {@code LexiconCompileBundleService}.</li>
 * </ul>
 *
 * <h2>{@code hyperscanExpressionId}: the term's own term number, when it applies</h2>
 * <p>On a {@code /compile/bundle} response, a non-AND-NOT PASS term's
 * reportable Hyperscan expression id — whether it needed decomposition or
 * not — is ALWAYS its own term number (parsed from its {@code termId}'s
 * {@code ::<n>} suffix). This is deliberate: a downstream consumer that
 * already knows a term's number can predict which expression id to watch
 * for WITHOUT reading this JSON at all. An AND NOT term does not have this
 * property any more — see the two-implementations note above — and reports
 * via {@code requiredExpressionIds}/{@code excludedExpressionIds} instead.
 * Every QUIET sub-expression a pure-decomposition combination needs is
 * assigned an id from a separate allocated range that never collides with
 * any term number — see {@code HyperscanCombinationHandler}.
 *
 * <h2>{@code patternMapping}: the logical formula, independent of whether the {@code .hdb} itself encodes it</h2>
 * <p>A boolean formula over this term's Hyperscan expression ids, using the
 * same {@code &}/{@code !} operator syntax Hyperscan's own
 * {@code HS_FLAG_COMBINATION} formulas use — e.g. {@code "(5&6&7)"} or
 * {@code "(8&!(9&10&11))"}. Each side (required / excluded, when present)
 * is AND-joined: a bare id when that side has exactly one, or a
 * parenthesised {@code (id1&id2&...)} when it was decomposed into several
 * — matching the "AND convention" documented above for
 * {@code requiredExpressionIds}/{@code excludedExpressionIds}.
 *
 * <p><b>This does NOT always mean the {@code .hdb} file itself contains a
 * native {@code HS_FLAG_COMBINATION} expression evaluating this formula —
 * read the id/case carefully:</b>
 * <ul>
 *   <li><b>Pure decomposition, no AND NOT</b> — the {@code .hdb} DOES
 *       contain a real {@code COMBINATION} expression at
 *       {@code hyperscanExpressionId} evaluating exactly this formula
 *       natively during the scan (see {@code HyperscanCombinationHandler}
 *       class Javadoc — safe here, no negation involved).
 *       {@code patternMapping} mirrors that same formula string for a
 *       caller that wants it without cross-referencing the {@code .hdb}'s
 *       own expression metadata.</li>
 *   <li><b>AND NOT (regardless of decomposition on either side)</b> — the
 *       {@code .hdb} file contains NO combination for this formula at all
 *       — confirmed unsafe, since Hyperscan evaluates a formula mixing a
 *       positive requirement with a negation eagerly and progressively,
 *       which can fire before the negated pattern has had any chance to
 *       appear later in the same text (see {@code HyperscanCombinationHandler}
 *       class Javadoc). Every required/excluded pattern instead compiles as
 *       its own independently reportable plain expression. {@code patternMapping}
 *       is the ONLY place this formula is recorded — a consumer that loads
 *       just the {@code .hdb} (no JSON) cannot derive AND NOT semantics
 *       from the database alone; a consumer needing this (e.g. the Lexicon
 *       Scan Engine) must read {@code patternMapping} from this JSON and
 *       apply it itself, AFTER the whole scan completes, against the
 *       complete set of matched expression ids.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TermCompilationResult(

        @JsonProperty("termId")
        String termId,

        @JsonProperty("termDescription")
        String termDescription,

        @JsonProperty("compilationStatus")
        CompilationStatus compilationStatus,

        /*
         * The required side's independently Hyperscan-validated pattern(s) —
         * see class Javadoc. Non-null and non-empty for a PASS term; null
         * for a FAILED term where translation never produced a pattern at
         * all (may still be non-null, for visibility, when the failure was
         * a Hyperscan-stage rejection of an attempted pattern).
         */
        @JsonProperty("translatedPattern")
        List<String> translatedPattern,

        /*
         * Hyperscan error message when a pattern was syntactically valid
         * PCRE but Hyperscan's compiler still rejected it — the main
         * pattern, the exclusion pattern, or a decomposed leaf of either
         * side (the message says which). Null for PASS terms and for
         * translation-stage failures.
         */
        @JsonProperty("errorLog")
        String errorLog,

        /*
         * Error from the operator-language translator, when the term's text
         * could not be converted into a PCRE pattern at all (e.g. missing
         * operand for NEAR{n}), OR when a side was over budget AND had no
         * NEAR/FOLLOWEDBY structure left to decompose, OR when even a
         * decomposed leaf was itself over budget on its own — see
         * {@code PatternDecomposer}. Null for PASS terms and for
         * Hyperscan-stage failures.
         */
        @JsonProperty("translationError")
        String translationError,

        /*
         * Hyperscan compile-flag bitmask applied to the pattern(s).
         * {@code 1}=CASELESS, {@code 32}=UTF8, {@code 64}=UCP.
         * {@code 0} indicates translation never completed.
         */
        @JsonProperty("hyperscanFlags")
        int hyperscanFlags,

        /*
         * {@code true} when the term used {@code AND NOT} — see the
         * two-implementations note in class Javadoc for what the caller
         * does with this depending on which endpoint produced this result.
         * Always {@code false} for failed terms and for terms using only
         * {@code AND} (which is fully expressed by {@code translatedPattern}
         * alone — see {@code PatternCodeGenerator} class Javadoc).
         */
        @JsonProperty("requiresExclusionCheck")
        boolean requiresExclusionCheck,

        /*
         * Independently Hyperscan-valid pattern(s) that must NOT (collectively,
         * per the AND convention described in class Javadoc) match the same
         * message for this term to be considered matched. Non-null and
         * non-empty iff {@code requiresExclusionCheck} is true; null otherwise.
         */
        @JsonProperty("exclusionPattern")
        List<String> exclusionPattern,

        /*
         * Non-fatal issues worth surfacing to the caller — never null, may
         * be empty. Always includes an explicit entry whenever decomposition
         * applied to either side (see class Javadoc), and whenever the term
         * relied on chained NEAR/FOLLOWEDBY without explicit parentheses
         * (see {@code ExpressionParser}). Present regardless of
         * {@code compilationStatus}, though in practice only PASS terms
         * currently produce any.
         */
        @JsonProperty("warnings")
        List<String> warnings,

        /*
         * On a {@code /compile/bundle} response only, for a term that does NOT
         * require an exclusion check: this term's own term number — see class
         * Javadoc "hyperscanExpressionId: the term's own term number, when it
         * applies". Null outside {@code /compile/bundle}, null for FAILED terms,
         * and null for an AND NOT term (see {@code requiredExpressionIds} /
         * {@code excludedExpressionIds} instead).
         */
        @JsonProperty("hyperscanExpressionId")
        Integer hyperscanExpressionId,

        /*
         * On a {@code /compile/bundle} response only, for an AND NOT term ONLY:
         * the expression id(s) of the required side's independently reportable
         * plain pattern(s) — one per entry of {@code translatedPattern}. Null
         * for every other case (including a non-AND-NOT term on
         * {@code /compile/bundle}, which uses {@code hyperscanExpressionId}
         * instead). See class Javadoc "AND NOT has two different correct
         * implementations" for how a caller combines this with
         * {@code excludedExpressionIds}.
         */
        @JsonProperty("requiredExpressionIds")
        List<Integer> requiredExpressionIds,

        /*
         * On a {@code /compile/bundle} response only, for an AND NOT term ONLY:
         * the expression id(s) of the excluded side's independently reportable
         * plain pattern(s) — one per entry of {@code exclusionPattern}. Null
         * for every other case. <b>Same AND convention as the required side:</b>
         * the excluded condition is considered satisfied (and the term
         * therefore excluded) only when EVERY entry of this list was found
         * somewhere in the scan — not when merely one was. A caller must
         * confirm the excluded condition was NOT fully satisfied (at least
         * one entry absent) for this term to count as matched, exactly
         * mirroring the documented {@code /compile}/{@code /compile/csv}
         * contract in class Javadoc.
         */
        @JsonProperty("excludedExpressionIds")
        List<Integer> excludedExpressionIds,

        /*
         * On a {@code /compile/bundle} response only, for a term that needed
         * more than one Hyperscan expression id (pure decomposition, AND
         * NOT, or both): the logical formula over those ids — see class
         * Javadoc "patternMapping: the logical formula...". Null for a
         * simple term (single {@code hyperscanExpressionId}) and null
         * outside {@code /compile/bundle}.
         */
        @JsonProperty("patternMapping")
        String patternMapping,

        @JsonProperty("compiledAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant compiledAt

) {
    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Creates a PASS result. {@code translatedPattern} and (when
     * {@code requiresExclusionCheck}) {@code exclusionPattern} may each have
     * one entry (simple term) or several (decomposed) — see class Javadoc.
     * {@code warnings} may be empty.
     */
    public static TermCompilationResult pass(TypedCompileRequest.TermInput input,
                                             List<String> translatedPattern,
                                             int hyperscanFlags,
                                             boolean requiresExclusionCheck,
                                             List<String> exclusionPattern,
                                             List<String> warnings) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.PASS,
                translatedPattern, null, null,
                hyperscanFlags, requiresExclusionCheck, exclusionPattern,
                warnings == null ? List.of() : List.copyOf(warnings),
                null, null, null, null, Instant.now());
    }

    /**
     * Convenience overload for a PASS result with no AND-NOT exclusion and no warnings.
     */
    public static TermCompilationResult pass(TypedCompileRequest.TermInput input,
                                             List<String> translatedPattern,
                                             int hyperscanFlags) {
        return pass(input, translatedPattern, hyperscanFlags, false, null, List.of());
    }

    /**
     * Creates a FAILED result where Hyperscan rejected a pattern — the main
     * pattern, the exclusion pattern, or a decomposed leaf of either side
     * (the message says which).
     *
     * @param translatedPattern the pattern(s) attempted, for visibility — may be null
     *                          if the failure was in the exclusion side specifically
     */
    public static TermCompilationResult failedHyperscan(TypedCompileRequest.TermInput input,
                                                        List<String> translatedPattern,
                                                        String errorLog,
                                                        int hyperscanFlags) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.FAILED,
                translatedPattern, errorLog, null,
                hyperscanFlags, false, null, List.of(),
                null, null, null, null, Instant.now());
    }

    /**
     * Creates a FAILED result where the term's syntax could not be translated
     * into a PCRE pattern at all — including the narrower decomposition-failure
     * cases (no proximity structure left to decompose; a leaf itself over
     * budget) — see {@code TermSyntaxTranslator}. Hyperscan was never invoked.
     */
    public static TermCompilationResult failedTranslation(TypedCompileRequest.TermInput input,
                                                          String translationError) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.FAILED,
                null, null, translationError,
                0, false, null, List.of(),
                null, null, null, null, Instant.now());
    }

    /**
     * Returns a copy of this (PASS, non-AND-NOT) result with
     * {@link #hyperscanExpressionId} set — used only by
     * {@code LexiconCompileBundleService}, which always sets it to the
     * term's own term number (see class Javadoc). Never used for an AND NOT
     * term — see {@link #withExpressionIds}.
     *
     * @param patternMapping the logical formula over this term's leaf ids —
     *                       see class Javadoc "patternMapping" — null for a
     *                       simple, non-decomposed term (nothing to map)
     */
    public TermCompilationResult withHyperscanExpressionId(int id, String patternMapping) {
        return new TermCompilationResult(
                termId, termDescription, compilationStatus,
                translatedPattern, errorLog, translationError,
                hyperscanFlags, requiresExclusionCheck, exclusionPattern, warnings,
                id, null, null, patternMapping, compiledAt);
    }

    /**
     * Returns a copy of this (PASS, AND NOT) result with
     * {@link #requiredExpressionIds} and {@link #excludedExpressionIds} set —
     * used only by {@code LexiconCompileBundleService} for a term where
     * {@link #requiresExclusionCheck} is true. {@link #hyperscanExpressionId}
     * remains null — see class Javadoc "AND NOT has two different correct
     * implementations".
     *
     * @param patternMapping the AND-NOT formula over the required/excluded
     *                       ids — see class Javadoc "patternMapping" — this
     *                       is the ONLY place an AND NOT term's combination
     *                       formula is recorded, since the {@code .hdb}
     *                       itself never encodes it
     */
    public TermCompilationResult withExpressionIds(List<Integer> requiredExpressionIds,
                                                   List<Integer> excludedExpressionIds,
                                                   String patternMapping) {
        return new TermCompilationResult(
                termId, termDescription, compilationStatus,
                translatedPattern, errorLog, translationError,
                hyperscanFlags, requiresExclusionCheck, exclusionPattern, warnings,
                null, requiredExpressionIds, excludedExpressionIds, patternMapping, compiledAt);
    }

    public boolean isPass() {
        return CompilationStatus.PASS == compilationStatus;
    }

    public boolean isFailed() {
        return CompilationStatus.FAILED == compilationStatus;
    }
}
