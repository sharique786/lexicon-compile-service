package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCombinationHandler;
import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.InvalidTermIdException;
import com.db.macs3.ecomms.spectre.model.TermCompilationResult;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.util.ScriptDetector;
import com.gliwka.hyperscan.wrapper.Expression;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates {@code POST /api/lexicon/compile/bundle}: compiles every term, then builds one combined
 * Hyperscan database from them.
 *
 * <p><b>Term types.</b> The request's root {@code requestType} applies to all its terms:
 * <ul>
 *   <li>{@code NATURAL_LANGUAGE} — {@link LexiconCompileService#compileTerm}, identical to {@code /compile}.</li>
 *   <li>{@code REGEX} — {@code termDescription} is already PCRE and is validated and compiled as given, with
 *       no translation and no {@code resolvedPatterns}. Flags come from
 *       {@code ScriptDetector.detect(pattern).recommendedHsFlags()}: {@code CASELESS|DOTALL} for a Latin
 *       pattern, plus {@code UTF8|UCP} for anything else. {@code requiresExclusionCheck} is always false.</li>
 * </ul>
 *
 * <p><b>Term ids.</b> Every {@code termId} must end with {@code ::<n>} ({@code n} a non-negative
 * integer) and every {@code n} must be unique in the request; this is checked for the WHOLE request
 * before any term compiles ({@link #validateTermIds}, {@link InvalidTermIdException}, HTTP 400). The
 * number becomes the term's Hyperscan expression id; see {@link HyperscanCombinationHandler} for the id
 * scheme, including AND NOT.
 *
 * <p><b>The combined database is built only when EVERY term reaches PASS.</b> A single FAILED term
 * means no {@code .hdb} at all ({@link #buildDatabasePortion}), even if all others passed, so a caller
 * can never receive a database silently missing one term's coverage. Outcomes:
 * <ul>
 *   <li>all PASS and the build succeeds — a {@code .hdb};</li>
 *   <li>any term FAILED, or zero PASS — HTTP 200, {@code NO_DATABASE.txt} instead of the {@code .hdb},
 *       explained by each term's own status;</li>
 *   <li>all PASS but the combined build itself fails — {@code CompileResponse#databaseError} is set and the
 *       controller answers HTTP 500 with the JSON (no zip).</li>
 * </ul>
 * The JSON summary has the same shape as {@code /compile}'s, plus {@code hyperscanExpressionId} (a
 * non-AND-NOT term's own number) or {@code requiredExpressionIds}/{@code excludedExpressionIds}
 * (an AND NOT term), and {@code patternMapping} where a term needed several ids.
 */
@Service
public class LexiconCompileBundleService {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileBundleService.class);

    private final LexiconCompileService compileService;
    private final HyperscanCompiler compiler;
    private final HyperscanCombinationHandler combinationHandler;
    private final Counter naturalLanguageTermCounter;
    private final Counter regexTermCounter;
    private final Counter databaseBuiltCounter;
    private final Counter databaseFailedCounter;

    public LexiconCompileBundleService(LexiconCompileService compileService,
                                       HyperscanCompiler compiler,
                                       HyperscanCombinationHandler combinationHandler,
                                       MeterRegistry meterRegistry) {
        this.compileService = compileService;
        this.compiler = compiler;
        this.combinationHandler = combinationHandler;
        this.naturalLanguageTermCounter = meterRegistry.counter("lexicon.compile.bundle.natural_language");
        this.regexTermCounter = meterRegistry.counter("lexicon.compile.bundle.regex");
        this.databaseBuiltCounter = meterRegistry.counter("lexicon.compile.bundle.database.built");
        this.databaseFailedCounter = meterRegistry.counter("lexicon.compile.bundle.database.failed");
    }

    /**
     * Compiles every term in the request and builds the combined database, but only when every term reached
     * PASS; see the class Javadoc.
     *
     * @param request a validated request
     * @return the JSON summary plus the database bytes, when one was built
     * @throws InvalidTermIdException if any {@code termId} is malformed or a term number repeats
     */
    public CompileBundleResult buildBundle(TypedCompileRequest request) {
        long startTimeMs = System.currentTimeMillis();
        log.info("Compiling bundle: {} {} term(s) for rule '{}' (request_id={})",
                request.getTerms().size(), request.getRequestType(),
                request.getLexiconRuleName(), request.getRequestId());

        // Every termId's term number is needed as its expression id — parsed
        // and validated for the WHOLE request before any term is compiled, so
        // a malformed or duplicate termId is rejected clearly up front rather
        // than corrupting the combined database with a Hyperscan id collision.
        Map<String, Integer> termNumberByTermId = validateTermIds(request);
        int idOffset = combinationHandler.computeIdOffset(termNumberByTermId.values());
        HyperscanCombinationHandler.HyperscanIdAllocator idAllocator =
                new HyperscanCombinationHandler.HyperscanIdAllocator(idOffset);

        boolean isRegexType = request.isRegexType();

        List<TermCompilationResult> termResults = new ArrayList<>(request.getTerms().size());
        List<Expression> passingExpressions = new ArrayList<>(request.getTerms().size());

        for (TypedCompileRequest.TermInput termInput : request.getTerms()) {

            TermCompilationResult termResult;
            if (isRegexType) {
                regexTermCounter.increment();
                termResult = compileRegexTerm(termInput);
            } else {
                naturalLanguageTermCounter.increment();
                termResult = compileService.compileTerm(termInput); // exact same pipeline as /compile
            }

            if (termResult.isPass()) {
                int termNumber = termNumberByTermId.get(termInput.termId());
                HyperscanCombinationHandler.ExpressionAssignment assignment =
                        combinationHandler.addExpressions(termResult, termNumber, idAllocator, passingExpressions);
                termResult = (assignment.hyperscanExpressionId() != null)
                        ? termResult.withHyperscanExpressionId(assignment.hyperscanExpressionId(), assignment.patternMapping())
                        : termResult.withExpressionIds(assignment.requiredExpressionIds(),
                        assignment.excludedExpressionIds(), assignment.patternMapping());
            }
            termResults.add(termResult);
        }

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        log.info("Bundle compile done rule='{}': {}/{} PASS, {}ms",
                request.getLexiconRuleName(),
                termResults.stream().filter(TermCompilationResult::isPass).count(),
                termResults.size(),
                elapsedMs);

        CompileResponse jsonResponse = CompileResponse.ofBundle(
                request.getRequestId(),
                request.getRequestType(),
                request.getLexiconRuleName(),
                termResults,
                elapsedMs);

        return buildDatabasePortion(jsonResponse, termResults, passingExpressions);
    }

    // ── Term id validation ───────────────────────────────────────────────────────

    /**
     * Matches the platform-wide {@code <rule_name>::<term_number>} termId convention.
     */
    private static final Pattern TERM_ID_PATTERN = Pattern.compile("^.*::(\\d+)$");

    /**
     * Parses the term number from every {@code termId} and validates the whole request: each id must match
     * {@link #TERM_ID_PATTERN} (and fit an int) and every number must be unique. Two terms at one expression
     * id would corrupt the combined database, so this is rejected up front. Malformed ids are reported before
     * duplicates.
     *
     * @return termId → term number, one entry per term
     * @throws InvalidTermIdException naming every malformed or duplicate id
     */
    private Map<String, Integer> validateTermIds(TypedCompileRequest request) {
        Map<String, Integer> termNumberByTermId = new LinkedHashMap<>();
        Map<Integer, List<String>> termIdsByNumber = new LinkedHashMap<>();
        List<String> malformed = new ArrayList<>();

        for (TypedCompileRequest.TermInput termInput : request.getTerms()) {
            String termId = termInput.termId();
            Matcher matcher = TERM_ID_PATTERN.matcher(termId);
            if (!matcher.matches()) {
                malformed.add(termId);
                continue;
            }
            int termNumber;
            try {
                termNumber = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException tooLarge) {
                // Digits matched \d+ but overflowed int (e.g. 20+ digits) — still malformed.
                malformed.add(termId);
                continue;
            }
            termNumberByTermId.put(termId, termNumber);
            termIdsByNumber.computeIfAbsent(termNumber, k -> new ArrayList<>()).add(termId);
        }

        if (!malformed.isEmpty()) {
            throw new InvalidTermIdException(
                    "termId must end with '::<n>' where n is a non-negative integer (the platform's "
                            + "term-number convention, e.g. 'lexicon_rule_name::1') — required for /compile/bundle's "
                            + "Hyperscan expression id scheme. Malformed termId(s): " + malformed);
        }

        List<String> duplicateDetails = termIdsByNumber.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "term number " + e.getKey() + " used by " + e.getValue())
                .toList();
        if (!duplicateDetails.isEmpty()) {
            throw new InvalidTermIdException(
                    "Every termId's term number must be unique within a /compile/bundle request — "
                            + "two terms sharing a term number would collide at the same Hyperscan expression id. "
                            + "Duplicate(s): " + duplicateDetails);
        }

        return termNumberByTermId;
    }

    // ── Regex-type term handling ─────────────────────────────────────────────

    /**
     * Compiles a Regex-type term: the pattern is {@code termDescription} verbatim. Flags are derived from
     * the pattern's script (so a raw Korean or Arabic regex gets UTF8/UCP without the caller knowing the
     * bitmask), and the pattern is validated with real Hyperscan. A rejection is a FAILED result with the
     * Hyperscan message in {@code errorLog}.
     */
    private TermCompilationResult compileRegexTerm(TypedCompileRequest.TermInput termInput) {
        String pattern = termInput.termDescription();
        int hyperscanFlags = ScriptDetector.detect(pattern).recommendedHsFlags();

        HyperscanCompiler.ValidationResult validation = compiler.validate(pattern, hyperscanFlags);

        return validation.isPass()
                ? TermCompilationResult.pass(termInput, List.of(pattern), hyperscanFlags)
                : TermCompilationResult.failedHyperscan(
                termInput, List.of(pattern), validation.errorMessage(), hyperscanFlags);
    }

    // ── Combined database ────────────────────────────────────────────────────

    /**
     * Decides whether and how to build the database: none when any term FAILED or none PASSED (a note, HTTP 200);
     * otherwise compile all expressions into one database. A failed build sets {@code databaseError} on the JSON
     * so a caller cannot mistake per-term PASS statuses for a usable bundle.
     */
    private CompileBundleResult buildDatabasePortion(CompileResponse jsonResponse,
                                                     List<TermCompilationResult> termResults,
                                                     List<Expression> passingExpressions) {
        // No combined database at all when ANY term in this request failed to compile — even
        // when other terms passed and would otherwise have produced a usable set of expressions.
        // A caller that only inspects the zip for a ".hdb" file (not every term's own
        // compilationStatus in the JSON) must never receive a database that is silently missing
        // the failed term's intended coverage. This is deliberately the same "HTTP 200,
        // NO_DATABASE.txt instead of a .hdb" shape as the zero-PASS-terms case below (not an
        // HTTP 500/databaseError) — the combined build was never even ATTEMPTED, so this isn't
        // the "build/serialisation itself failed" case that databaseBuildFailed represents.
        List<String> failedTermIds = termResults.stream()
                .filter(TermCompilationResult::isFailed)
                .map(TermCompilationResult::termId)
                .toList();
        if (!failedTermIds.isEmpty()) {
            log.warn("No combined database built for rule '{}' — {} of {} term(s) did not reach PASS: {}",
                    jsonResponse.lexiconRuleName(), failedTermIds.size(), termResults.size(), failedTermIds);
            return new CompileBundleResult(jsonResponse, null,
                    "No Hyperscan database file was produced because " + failedTermIds.size() + " of "
                            + termResults.size() + " term(s) in this request did not reach PASS status: "
                            + failedTermIds + ". A combined database is only built when every term in the "
                            + "request compiles successfully. See the JSON results for each term's "
                            + "compilationStatus and errorLog/translationError details.",
                    false);
        }

        if (passingExpressions.isEmpty()) {
            log.warn("No PASS terms for rule '{}' — no combined database will be built",
                    jsonResponse.lexiconRuleName());
            return new CompileBundleResult(jsonResponse, null,
                    "No Hyperscan database file was produced because zero terms reached "
                            + "PASS status. See the JSON results for per-term compilationStatus and "
                            + "errorLog/translationError details.",
                    false);
        }

        HyperscanCompiler.CombinedCompileResult combinedResult =
                compiler.compileCombinedDatabase(passingExpressions);

        if (combinedResult.success()) {
            databaseBuiltCounter.increment();
            return new CompileBundleResult(jsonResponse, combinedResult.databaseBytes(), null, false);
        }

        databaseFailedCounter.increment();
        log.error("Combined database compile FAILED for rule '{}': {} (failedExpressionId={})",
                jsonResponse.lexiconRuleName(), combinedResult.errorMessage(), combinedResult.failedExpressionId());

        // Every term individually reached PASS/FAILED normally — this is NOT a per-term
        // translation problem, it is the combined multi-pattern build itself failing (e.g. a
        // flag/state-count interaction only visible once every PASS expression is compiled
        // together). The JSON must say so explicitly via databaseError — otherwise a caller
        // reading only per-term compilationStatus would see nothing but PASS and wrongly
        // conclude the bundle is usable, when in fact no .hdb was produced at all.
        String explanation = "No Hyperscan database file was produced.\nReason: "
                + combinedResult.errorMessage()
                + describeFailedExpression(jsonResponse, combinedResult.failedExpressionId());
        CompileResponse errorResponse = jsonResponse.withDatabaseError(explanation);
        return new CompileBundleResult(errorResponse, null, explanation, true);
    }

    /**
     * Resolves a failing Hyperscan expression id back to its term, checking every id shape a term can report
     * under ({@code hyperscanExpressionId}, {@code requiredExpressionIds}, {@code excludedExpressionIds}).
     */
    private String describeFailedExpression(CompileResponse jsonResponse, Integer failedExpressionId) {
        if (failedExpressionId == null) {
            return "";
        }
        return jsonResponse.results().stream()
                .filter(r -> failedExpressionId.equals(r.hyperscanExpressionId())
                        || containsId(r.requiredExpressionIds(), failedExpressionId)
                        || containsId(r.excludedExpressionIds(), failedExpressionId))
                .findFirst()
                .map(r -> "\nThe term '" + r.termId() +
                        "' (\"" + r.termDescription() + "\") caused the combined "
                        + "compile to fail at Hyperscan expression id " + failedExpressionId
                        + ", even though it passed individual validation. See the JSON results for that term's details.")
                .orElse("\nThe failing Hyperscan expression id was " + failedExpressionId
                        + ", but no term in this response maps to it — this should not happen and indicates a bug"
                        + " in the id-assignment logic.");
    }

    private boolean containsId(List<Integer> ids, Integer target) {
        return ids != null && ids.contains(target);
    }

    // ── Result carrier ───────────────────────────────────────────────────────

    /**
     * The outcome handed back to the controller.
     *
     * @param jsonResponse           the {@code /compile}-shaped summary; carries {@code databaseError} when
     *                               {@code databaseBuildFailed} is true
     * @param hyperscanDatabaseBytes the {@code .hdb} content, or null when none was built
     * @param databaseNote           why no database was built; null when one was
     * @param databaseBuildFailed    true only when every term PASSED, a combined build was attempted, and it
     *                               failed — a genuine system-level failure, unlike "a term FAILED" or "zero
     *                               PASS" (both false), which each term's own status already explains. The
     *                               controller turns true into HTTP 500 and false into a 200 zip
     */
    public record CompileBundleResult(
            CompileResponse jsonResponse,
            byte[] hyperscanDatabaseBytes,
            String databaseNote,
            boolean databaseBuildFailed
    ) {
        /**
         * @return true when a combined Hyperscan database was produced.
         */
        public boolean hasDatabase() {
            return hyperscanDatabaseBytes != null && hyperscanDatabaseBytes.length > 0;
        }
    }
}
