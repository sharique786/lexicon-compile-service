package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Compilation outcome for a single lexicon term.
 *
 * <p><b>Null field semantics</b>
 * <ul>
 *   <li>{@code regexPattern} — null/absent only when translation failed before any
 *       pattern could be produced (see {@code translationError}); for a PASS term, always
 *       present with at least one entry — see below</li>
 *   <li>{@code errorLog} — non-null only for Hyperscan-stage failures</li>
 *   <li>{@code translationError} — non-null only for translation-stage failures</li>
 *   <li>{@code exclusionRegex} — non-null only when {@code requiresExclusionCheck} is true</li>
 *   <li>{@code resolvedPatterns} — non-null for every PASS Natural-Language term (see
 *       "{@code resolvedPatterns}" below); null for a FAILED term and for a PASS
 *       Regex-type term (which never goes through the translator/AST this field is
 *       built from)</li>
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
 *       expression id to represent it (a term with NEAR/FOLLOWEDBY structure, an AND NOT
 *       term, or both at once). Null for a simple term (single
 *       {@code hyperscanExpressionId}, nothing to map) and null outside
 *       {@code /compile/bundle} — see "{@code patternMapping}: the logical
 *       formula, independent of whether the {@code .hdb} itself encodes it"
 *       below.</li>
 * </ul>
 * At most one of {@code errorLog} / {@code translationError} is non-null.
 * Both are null when {@code compilationStatus} is {@code PASS}.
 *
 * <p><b>{@code regexPattern} and {@code exclusionRegex} are always lists</b>
 * <p>Every Natural-Language lexicon term — however structurally complex, and
 * whether or not it uses {@code AND NOT} — is represented uniformly:
 * {@code regexPattern} holds the required side's independently
 * Hyperscan-valid pattern(s), one entry for a side with no NEAR/FOLLOWEDBY
 * structure (or whose NEAR/FOLLOWEDBY is nested inside an {@code OR} — see
 * {@code PatternDecomposer} class Javadoc "the one exception"), two or more
 * for a side containing NEAR/FOLLOWEDBY structure elsewhere — split into
 * independent leaf patterns unconditionally, not as a complexity-driven
 * fallback (see {@code PatternDecomposer}). There is no separate "was this
 * split" boolean any more: a caller checks {@code regexPattern.size()}. The
 * same applies to {@code exclusionRegex} for an AND NOT term's excluded side.
 *
 * <p><b>Multiple entries are a real precision trade-off — read
 * {@code resolvedPatterns} for the full relationship.</b>
 * Splitting discards the original NEAR/FOLLOWEDBY ordering/distance
 * constraint BETWEEN leaf patterns: the leaves are combined with pure
 * boolean AND ("all of these appear somewhere in the message"), not with
 * any positional relationship to EACH OTHER. Unlike an earlier revision of
 * this codebase, NO gap fragment is baked into any leaf's own pattern text
 * any more — the leaves are pure, gap-free fragments. The full relationship
 * — including the term author's raw, un-clamped NEAR/FOLLOWEDBY distance —
 * is instead conveyed separately via {@code resolvedPatterns}, as literal
 * keyword text. A server-side log entry is always written whenever this
 * trade-off applies to either side (see {@code LexiconCompileService}), so
 * the condition is diagnosable even though it is no longer surfaced as a
 * {@code warnings} field in the response itself — no caller can silently
 * treat a split match as a genuine proximity match without the precision
 * trade-off being logged and moved to {@code resolvedPatterns}.
 *
 * <p><b>AND NOT has two different correct implementations, for two different callers</b>
 * <p>{@code regexPattern} and {@code exclusionRegex} are independently
 * Hyperscan-valid pattern lists (Hyperscan cannot express "absent from the
 * whole message" in a single pattern — no negative lookaround support).
 * What a caller does with them differs by which endpoint it used:
 *
 * <ul>
 *   <li><b>{@code /compile} and {@code /compile/csv}</b> — the caller (e.g.
 *       the Lexicon Scanner Service) reads the JSON, compiles every pattern
 *       in both lists itself, and combines the boolean results in
 *       application code: matched iff EVERY entry of {@code regexPattern}
 *       matches AND the excluded condition (every entry of {@code exclusionRegex}
 *       found — pure AND, same convention as the required side) is NOT fully
 *       satisfied.</li>
 *   <li><b>{@code /compile/bundle}</b> — for a term that does NOT require an
 *       exclusion check, every pattern in {@code regexPattern} is compiled
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
 * <p><b>{@code resolvedPatterns}: the literal-keyword proximity/AND-NOT structure,
 * for a downstream Java-regex-based consumer</b>
 * <p>Since {@code regexPattern}/{@code exclusionRegex} no longer encode any
 * NEAR/FOLLOWEDBY gap or AND-NOT relationship (both are conveyed only as
 * pure boolean-AND presence via ids/{@code patternMapping} above),
 * {@code resolvedPatterns} is where that relationship actually lives: this
 * term (or, for AND NOT, both sides joined by the literal keyword) rendered
 * with {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/{@code AND NOT} standing in for
 * whatever would otherwise be a gap regex — e.g.
 * {@code "bash FOLLOWEDBY{30} (?:fuck|fck)"} or
 * {@code "insider AND NOT ((?:wordA...) FOLLOWEDBY{2} (?:wordH...) FOLLOWEDBY{2} (?:wordO...))"}.
 * Despite the plural field name (matching how downstream consumers refer to
 * it), this is always exactly ONE {@code String} per term, never a list —
 * every leaf substring within it is byte-identical to the corresponding
 * {@code regexPattern}/{@code exclusionRegex} entry, in the same
 * left-to-right order, so a consumer can correlate a leaf's own
 * Hyperscan-match presence with its exact position inside this string. A
 * downstream Java-regex-based matcher (Lexicon Scan Engine / Lexicon
 * Scanner Service — not part of this repo) tokenizes this text and applies
 * the actual proximity/AND-NOT logic itself; see
 * {@code src/test/java/.../TokenProximityMatcher.java}-family classes in
 * this repo's test tree for a reference implementation of that downstream
 * technique. See {@code PatternDecomposer} class Javadoc for exactly how
 * this string is built, including the one deliberate exception (NEAR/FOLLOWEDBY
 * nested inside an {@code OR} still compiles as a single gap-embedded
 * pattern, and this field's corresponding text is that same gap-embedded
 * string, byte-identical to its {@code regexPattern}/{@code exclusionRegex}
 * entry). Populated identically for all three endpoints — unlike
 * {@code hyperscanExpressionId}/{@code patternMapping}/
 * {@code requiredExpressionIds}/{@code excludedExpressionIds}, which are
 * {@code /compile/bundle}-only.
 *
 * <p><b>{@code hyperscanExpressionId}: the term's own term number, when it applies</b>
 * <p>On a {@code /compile/bundle} response, a non-AND-NOT PASS term's
 * reportable Hyperscan expression id — whether it needed splitting or
 * not — is ALWAYS its own term number (parsed from its {@code termId}'s
 * {@code ::<n>} suffix). This is deliberate: a downstream consumer that
 * already knows a term's number can predict which expression id to watch
 * for WITHOUT reading this JSON at all. An AND NOT term does not have this
 * property any more — see the two-implementations note above — and reports
 * via {@code requiredExpressionIds}/{@code excludedExpressionIds} instead.
 * Every QUIET sub-expression a term with NEAR/FOLLOWEDBY structure needs is
 * assigned an id from a separate allocated range that never collides with
 * any term number — see {@code HyperscanCombinationHandler}.
 *
 * <p><b>{@code patternMapping}: the logical formula, independent of whether the {@code .hdb} itself encodes it</b>
 * <p>A boolean formula over this term's Hyperscan expression ids, using the
 * same {@code &}/{@code !} operator syntax Hyperscan's own
 * {@code HS_FLAG_COMBINATION} formulas use — e.g. {@code "(5&6&7)"} or
 * {@code "(8&!(9&10&11))"}. Each side (required / excluded, when present)
 * is AND-joined: a bare id when that side has exactly one, or a
 * parenthesised {@code (id1&id2&...)} when it was split into several
 * — matching the "AND convention" documented above for
 * {@code requiredExpressionIds}/{@code excludedExpressionIds}. Kept
 * unchanged, additive alongside {@code resolvedPatterns} — a caller that
 * only needs presence/AND-NOT boolean logic (not proximity precision) can
 * keep using this field exactly as before.
 *
 * <p><b>This does NOT always mean the {@code .hdb} file itself contains a
 * native {@code HS_FLAG_COMBINATION} expression evaluating this formula —
 * read the id/case carefully:</b>
 * <ul>
 *   <li><b>A term with NEAR/FOLLOWEDBY structure, no AND NOT</b> — the {@code .hdb} DOES
 *       contain a real {@code COMBINATION} expression at
 *       {@code hyperscanExpressionId} evaluating exactly this formula
 *       natively during the scan (see {@code HyperscanCombinationHandler}
 *       class Javadoc — safe here, no negation involved).
 *       {@code patternMapping} mirrors that same formula string for a
 *       caller that wants it without cross-referencing the {@code .hdb}'s
 *       own expression metadata.</li>
 *   <li><b>AND NOT (regardless of NEAR/FOLLOWEDBY structure on either side)</b> — the
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
        @JsonProperty("regexPattern")
        List<String> regexPattern,

        /*
         * Hyperscan error message when a pattern was syntactically valid
         * PCRE but Hyperscan's compiler still rejected it — the main
         * pattern, the exclusion pattern, or a leaf of either side (the
         * message says which). Null for PASS terms and for
         * translation-stage failures.
         */
        @JsonProperty("errorLog")
        String errorLog,

        /*
         * Error from the operator-language translator, when the term's text
         * could not be converted into a PCRE pattern at all (e.g. missing
         * operand for NEAR{n}), OR when a leaf with no further NEAR/FOLLOWEDBY
         * structure was itself rejected by Hyperscan — see
         * {@code TermSyntaxTranslator}. Null for PASS terms and for
         * Hyperscan-stage failures.
         */
        @JsonProperty("translationError")
        String translationError,

        /*
         * Hyperscan compile-flag bitmask applied to the pattern(s).
         * {@code 1}=CASELESS, {@code 32}=UTF8, {@code 64}=UCP.
         * {@code 0} indicates translation never completed. Not part of the
         * response JSON any more ({@code @JsonIgnore}) — it remains a real
         * field because {@code HyperscanCombinationHandler} still needs it
         * to build {@code /compile/bundle}'s combined-database expressions;
         * its value is logged instead (see {@code LexiconCompileService}).
         */
        @JsonIgnore
        int hyperscanFlags,

        /*
         * {@code true} when the term used {@code AND NOT} — see the
         * two-implementations note in class Javadoc for what the caller
         * does with this depending on which endpoint produced this result.
         * Always {@code false} for failed terms and for terms using only
         * {@code AND} (which is fully expressed by {@code regexPattern}
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
        @JsonProperty("exclusionRegex")
        List<String> exclusionRegex,

        /*
         * This term (or, for AND NOT, both sides joined by the literal
         * keyword) rendered with NEAR{n}/FOLLOWEDBY{n}/AND NOT keyword text
         * standing in for any gap regex — see class Javadoc
         * "resolvedPatterns". Always exactly one String (never a list,
         * despite the plural name) for a PASS Natural-Language term; null
         * for a FAILED term and for a PASS Regex-type term. Populated
         * identically across all three endpoints.
         */
        @JsonProperty("resolvedPatterns")
        String resolvedPatterns,

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
         * plain pattern(s) — one per entry of {@code regexPattern}. Null
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
         * plain pattern(s) — one per entry of {@code exclusionRegex}. Null
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
         * more than one Hyperscan expression id (NEAR/FOLLOWEDBY structure, AND
         * NOT, or both): the logical formula over those ids — see class
         * Javadoc "patternMapping: the logical formula...". Null for a
         * simple term (single {@code hyperscanExpressionId}) and null
         * outside {@code /compile/bundle}.
         */
        @JsonProperty("patternMapping")
        String patternMapping,

        /*
         * Not part of the response JSON ({@code @JsonIgnore}) — regexPattern's
         * own boolean-AND grouping structure, using {@code {i}} leaf-index
         * placeholders (see {@code PatternDecomposer.Result#formulaTemplate()}
         * / {@code TranslationResult.Success#patternFormulaTemplate()}). Kept
         * only so {@code HyperscanCombinationHandler} can, at
         * {@code /compile/bundle} time, substitute each placeholder with that
         * leaf's allocated Hyperscan expression id and build a
         * {@code patternMapping}/native-combination formula that reflects the
         * term's actual authored nesting (e.g. {@code "(54&(55&56))"}) instead
         * of a flat AND-join of every leaf. Null for a simple (non-decomposed)
         * term, a FAILED term, or a Regex-type term — anything
         * {@code HyperscanCombinationHandler} falls back to a flat AND-join for.
         */
        @JsonIgnore
        String patternFormulaTemplate,

        /*
         * Not part of the response JSON — the same, for exclusionRegex's own
         * grouping. Null unless {@code requiresExclusionCheck} is true.
         */
        @JsonIgnore
        String exclusionFormulaTemplate

) {
    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Creates a PASS result. {@code regexPattern} and (when
     * {@code requiresExclusionCheck}) {@code exclusionRegex} may each have
     * one entry (no NEAR/FOLLOWEDBY structure) or several (split) — see class Javadoc.
     * {@code resolvedPatterns} should be null only for a Regex-type term
     * (which never goes through the translator this field is built from).
     * Any translation warnings are logged by the caller (see
     * {@code LexiconCompileService#compileTerm}) — this result carries none.
     */
    public static TermCompilationResult pass(TypedCompileRequest.TermInput input,
                                             List<String> regexPattern,
                                             int hyperscanFlags,
                                             boolean requiresExclusionCheck,
                                             List<String> exclusionRegex,
                                             String resolvedPatterns,
                                             String patternFormulaTemplate,
                                             String exclusionFormulaTemplate) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.PASS,
                regexPattern, null, null,
                hyperscanFlags, requiresExclusionCheck, exclusionRegex,
                resolvedPatterns,
                null, null, null, null,
                patternFormulaTemplate, exclusionFormulaTemplate);
    }

    /**
     * Convenience overload for a PASS result with no AND-NOT exclusion and
     * no {@code resolvedPatterns} — used for Regex-type terms, which never
     * go through the translator/AST this field is built from.
     */
    public static TermCompilationResult pass(TypedCompileRequest.TermInput input,
                                             List<String> regexPattern,
                                             int hyperscanFlags) {
        return pass(input, regexPattern, hyperscanFlags, false, null, null, null, null);
    }

    /**
     * Creates a FAILED result where Hyperscan rejected a pattern — the main
     * pattern, the exclusion pattern, or a leaf of either side
     * (the message says which).
     *
     * @param regexPattern the pattern(s) attempted, for visibility — may be null
     *                          if the failure was in the exclusion side specifically
     */
    public static TermCompilationResult failedHyperscan(TypedCompileRequest.TermInput input,
                                                        List<String> regexPattern,
                                                        String errorLog,
                                                        int hyperscanFlags) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.FAILED,
                regexPattern, errorLog, null,
                hyperscanFlags, false, null,
                null,
                null, null, null, null,
                null, null);
    }

    /**
     * Creates a FAILED result where the term's syntax could not be translated
     * into a PCRE pattern at all — including a leaf with no further
     * NEAR/FOLLOWEDBY structure that was itself rejected by Hyperscan — see
     * {@code TermSyntaxTranslator}. Hyperscan was never invoked.
     */
    public static TermCompilationResult failedTranslation(TypedCompileRequest.TermInput input,
                                                          String translationError) {
        return new TermCompilationResult(
                input.termId(), input.termDescription(),
                CompilationStatus.FAILED,
                null, null, translationError,
                0, false, null,
                null,
                null, null, null, null,
                null, null);
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
     *                       simple, non-split term (nothing to map)
     */
    public TermCompilationResult withHyperscanExpressionId(int id, String patternMapping) {
        return new TermCompilationResult(
                termId, termDescription, compilationStatus,
                regexPattern, errorLog, translationError,
                hyperscanFlags, requiresExclusionCheck, exclusionRegex,
                resolvedPatterns,
                id, null, null, patternMapping,
                patternFormulaTemplate, exclusionFormulaTemplate);
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
                regexPattern, errorLog, translationError,
                hyperscanFlags, requiresExclusionCheck, exclusionRegex,
                resolvedPatterns,
                null, requiredExpressionIds, excludedExpressionIds, patternMapping,
                patternFormulaTemplate, exclusionFormulaTemplate);
    }

    /**
     * Not part of the response JSON ({@code @JsonIgnore}) — record accessor
     * naming (Jackson would otherwise also serialise this as a bean-style
     * {@code "pass"} property alongside {@code compilationStatus}, which
     * already conveys the same information).
     */
    @JsonIgnore
    public boolean isPass() {
        return CompilationStatus.PASS == compilationStatus;
    }

    /**
     * Not part of the response JSON — see {@link #isPass()}.
     */
    @JsonIgnore
    public boolean isFailed() {
        return CompilationStatus.FAILED == compilationStatus;
    }
}
