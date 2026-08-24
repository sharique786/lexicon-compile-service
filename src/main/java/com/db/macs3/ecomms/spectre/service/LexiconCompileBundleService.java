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
 * Orchestrates {@code POST /api/lexicon/compile/bundle}.
 *
 * <p>{@link TypedCompileRequest} is the single request type shared with
 * {@code /compile}/{@code /compile/csv} (see {@link LexiconCompileService})
 * — there is no separate request shape for this endpoint any more.
 *
 * <p>Each request carries one root-level {@code termType}:
 * <ul>
 *   <li>{@code NATURAL_LANGUAGE} — translated via the existing
 *       {@code TermSyntaxTranslator} pipeline, by delegating straight to
 *       {@link LexiconCompileService#compileTerm}. Identical behaviour to
 *       {@code /compile} for every Natural Language term.</li>
 *   <li>{@code REGEX} — the caller's {@code termDescription} is already
 *       a PCRE pattern. No translation step runs; the pattern is compiled
 *       as-is. Flags are still derived from the pattern's script content
 *       via {@link ScriptDetector} so non-Latin Regex-type patterns (e.g. a raw
 *       Korean or Arabic regex) get the correct UTF8/UCP flags.</li>
 * </ul>
 *
 * <p>After every term is resolved to PASS or FAILED, all PASS terms'
 * expressions (built by {@link HyperscanCombinationHandler} — see that
 * class for the QUIET/COMBINATION mechanism and the id scheme, including why
 * AND NOT terms deliberately do NOT use native Hyperscan COMBINATION) are
 * compiled into <b>one combined multi-pattern Hyperscan database</b> via
 * {@link HyperscanCompiler#compileCombinedDatabase}. The JSON summary
 * returned by {@link #buildBundle} is the same {@link CompileResponse}
 * / {@link TermCompilationResult} shape that {@code /compile} returns, with
 * additions specific to this endpoint: a non-AND-NOT term gets
 * {@code hyperscanExpressionId} (always its own term number); an AND NOT
 * term gets {@code requiredExpressionIds}/{@code excludedExpressionIds}
 * instead — see {@link TermCompilationResult} class Javadoc.
 *
 * <h2>Hyperscan expression id scheme</h2>
 * <p>Every {@code termId} in this platform follows the convention
 * {@code <lexicon_rule_name>::<term_number>} (e.g. {@code lexicon_research_1::1}).
 * Every {@code termId} in a bundle request MUST end with {@code ::<n>} for a
 * non-negative integer {@code n}, and every {@code n} in one request must be
 * unique — both are validated for the WHOLE request before any term is
 * compiled; see {@link #validateTermIds} and {@link InvalidTermIdException}.
 * See {@link HyperscanCombinationHandler} class Javadoc for the full id
 * scheme, including the AND NOT case, where the {@code .hdb} file is no
 * longer self-sufficient on its own and the JSON response's
 * {@code requiredExpressionIds}/{@code excludedExpressionIds} are required
 * to interpret a scan result correctly.
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
     * Compiles every term in the request and builds the combined Hyperscan
     * database from the PASS subset.
     *
     * @param request validated typed-compile request
     * @return {@link CompileBundleResult} — JSON summary + optional database bytes
     */
    public CompileBundleResult buildBundle(TypedCompileRequest request) {
        long startTimeMs = System.currentTimeMillis();
        log.info("Compiling bundle: {} {} term(s) for rule '{}' (request_id={})",
                request.getTerms().size(), request.getTermType(),
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
                        ? termResult.withHyperscanExpressionId(assignment.hyperscanExpressionId())
                        : termResult.withExpressionIds(assignment.requiredExpressionIds(),
                        assignment.excludedExpressionIds());
            }
            termResults.add(termResult);
        }

        log.info("Bundle compile done rule='{}': {}/{} PASS, {}ms",
                request.getLexiconRuleName(),
                termResults.stream().filter(TermCompilationResult::isPass).count(),
                termResults.size(),
                System.currentTimeMillis() - startTimeMs);

        CompileResponse jsonResponse = CompileResponse.ofBundle(
                request.getRequestId(),
                request.getTermType(),
                request.getLexiconRuleName(),
                termResults);

        return buildDatabasePortion(jsonResponse, passingExpressions);
    }

    // ── Term id validation ───────────────────────────────────────────────────────

    /**
     * Matches the platform-wide {@code <rule_name>::<term_number>} termId convention.
     */
    private static final Pattern TERM_ID_PATTERN = Pattern.compile("^.*::(\\d+)$");

    /**
     * Parses the term number out of every {@code termId} in {@code request}
     * and validates the WHOLE request before returning: every termId must
     * match {@link #TERM_ID_PATTERN}, and every parsed term number must be
     * unique within the request. A downstream Hyperscan id collision (two
     * terms compiled at the same expression id) would silently corrupt the
     * combined database — better to reject clearly here than debug that later.
     *
     * @return termId → term number, one entry per term in the request
     * @throws InvalidTermIdException naming every malformed or duplicate
     *                                termId found, if any
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
     * Compiles a Regex-type term: the pattern is the caller's
     * {@code termDescription} verbatim — no operator-language translation.
     *
     * <p>Flags are still derived automatically via {@link ScriptDetector} so
     * a raw non-Latin regex (e.g. a hand-written Korean or Arabic pattern)
     * gets UTF8/UCP without the caller having to know Hyperscan's flag
     * bitmask values. {@code requiresExclusionCheck} is always {@code false}
     * for Regex-type terms — AND NOT is part of the Natural Language operator
     * language's syntax; an arbitrary caller-supplied regex has no such
     * two-pattern exclusion contract to participate in.
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

    private CompileBundleResult buildDatabasePortion(CompileResponse jsonResponse,
                                                     List<Expression> passingExpressions) {
        if (passingExpressions.isEmpty()) {
            log.warn("No PASS terms for rule '{}' — no combined database will be built",
                    jsonResponse.lexiconRuleName());
            return new CompileBundleResult(jsonResponse, null,
                    "No Hyperscan database file was produced because zero terms reached "
                            + "PASS status. See the JSON results for per-term compilationStatus and "
                            + "errorLog/translationError details.");
        }

        HyperscanCompiler.CombinedCompileResult combinedResult =
                compiler.compileCombinedDatabase(passingExpressions);

        if (combinedResult.success()) {
            databaseBuiltCounter.increment();
            return new CompileBundleResult(jsonResponse, combinedResult.databaseBytes(), null);
        }

        databaseFailedCounter.increment();
        log.error("Combined database compile FAILED for rule '{}': {} (failedExpressionId={})",
                jsonResponse.lexiconRuleName(), combinedResult.errorMessage(), combinedResult.failedExpressionId());

        String explanation = "No Hyperscan database file was produced.\nReason: "
                + combinedResult.errorMessage()
                + describeFailedExpression(jsonResponse, combinedResult.failedExpressionId());
        return new CompileBundleResult(jsonResponse, null, explanation);
    }

    /**
     * Resolves {@code failedExpressionId} back to the specific term that
     * caused it, checking every id shape a term might report under: a
     * non-AND-NOT term's single {@code hyperscanExpressionId}, or an AND NOT
     * term's {@code requiredExpressionIds}/{@code excludedExpressionIds}
     * (any of which could be the one Hyperscan rejected during combined
     * compilation).
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
     * Carries the two zip-file payloads back to the controller.
     *
     * @param jsonResponse           identical shape to {@code /compile}'s response —
     *                               this is what gets written as the zip's JSON entry
     * @param hyperscanDatabaseBytes the combined {@code .hdb} file content, or
     *                               {@code null} when no database could be built
     * @param databaseNote           explanation written into {@code NO_DATABASE.txt}
     *                               when {@code hyperscanDatabaseBytes} is null;
     *                               null when a database was built successfully
     */
    public record CompileBundleResult(
            CompileResponse jsonResponse,
            byte[] hyperscanDatabaseBytes,
            String databaseNote
    ) {
        /**
         * @return true when a combined Hyperscan database was produced.
         */
        public boolean hasDatabase() {
            return hyperscanDatabaseBytes != null && hyperscanDatabaseBytes.length > 0;
        }
    }
}
