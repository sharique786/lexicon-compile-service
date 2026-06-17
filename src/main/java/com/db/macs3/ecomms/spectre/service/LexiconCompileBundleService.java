package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.hyperscan.HyperscanCompiler;
import com.db.macs3.ecomms.spectre.model.CompileRequest;
import com.db.macs3.ecomms.spectre.model.CompileResponse;
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
import java.util.List;

/**
 * Orchestrates {@code POST /api/lexicon/compile/bundle}.
 *
 * <p>Each term in the request carries a {@code termType}:
 * <ul>
 *   <li>{@code "Standard"} — translated via the existing
 *       {@code TermSyntaxTranslator} pipeline, by delegating straight to
 *       {@link LexiconCompileService#compileTerm}. Identical behaviour to
 *       {@code /compile} for every Standard term.</li>
 *   <li>{@code "NLT"} — the caller's {@code termDescription} is already a
 *       PCRE pattern. No translation step runs; the pattern is compiled
 *       as-is. Flags are still derived from the pattern's script content
 *       via {@link ScriptDetector} so non-Latin NLT patterns (e.g. a raw
 *       Korean or Arabic regex) get the correct UTF8/UCP flags.</li>
 * </ul>
 *
 * <p>After every term is resolved to PASS or FAILED, all PASS terms are
 * compiled into <b>one combined multi-pattern Hyperscan database</b> via
 * {@link HyperscanCompiler#compileCombinedDatabase}. The JSON summary
 * returned by {@link #buildBundle} is the exact same {@link CompileResponse}
 * / {@link TermCompilationResult} shape that {@code /compile} returns — no
 * new fields are added to it — so the JSON inside the response zip is
 * byte-for-byte the same format as the existing endpoint.
 *
 * <h2>Hyperscan expression id convention</h2>
 * <p>The id assigned to each {@link Expression} (and therefore the id a
 * downstream scan engine sees on a Hyperscan match) is the term's <b>0-based
 * index in the request's {@code terms} array</b> — which is also its index
 * in the JSON {@code results} array. FAILED terms are simply absent from the
 * combined database, so ids are not necessarily contiguous (e.g. if the term
 * at index 2 fails, the database contains ids {@code {0, 1, 3, 4, ...}}).
 * This convention requires no extra field on the JSON response: a consumer
 * who needs to know whether a given id is present just checks
 * {@code results[id].compilationStatus}.
 */
@Service
public class LexiconCompileBundleService {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileBundleService.class);

    private final LexiconCompileService compileService;
    private final HyperscanCompiler     compiler;
    private final Counter               standardCounter;
    private final Counter               nltCounter;
    private final Counter               databaseBuiltCounter;
    private final Counter               databaseFailedCounter;

    public LexiconCompileBundleService(LexiconCompileService compileService,
                                        HyperscanCompiler compiler,
                                        MeterRegistry meterRegistry) {
        this.compileService       = compileService;
        this.compiler              = compiler;
        this.standardCounter       = meterRegistry.counter("lexicon.compile.bundle.standard");
        this.nltCounter             = meterRegistry.counter("lexicon.compile.bundle.nlt");
        this.databaseBuiltCounter  = meterRegistry.counter("lexicon.compile.bundle.database.built");
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
        long startMs = System.currentTimeMillis();
        log.info("Compiling bundle: {} term(s) for rule '{}'",
                request.getTerms().size(), request.getLexiconRuleName());

        List<TermCompilationResult> results = new ArrayList<>(request.getTerms().size());
        List<Expression> passExpressions    = new ArrayList<>(request.getTerms().size());

        int index = 0;
        for (TypedCompileRequest.TermInput term : request.getTerms()) {

            // Adapter: existing TermCompilationResult factories (pass/failedHyperscan/
            // failedTranslation) require CompileRequest.TermInput specifically — this
            // adapter lets us reuse those factories unmodified so the JSON result shape
            // is guaranteed identical to /compile's, with zero changes to Models.java.
            CompileRequest.TermInput adapter = new CompileRequest.TermInput(
                    term.termId(), term.termDescription(), term.riskDriverName());

            TermCompilationResult result;
            if (term.isNlt()) {
                nltCounter.increment();
                result = compileNltTerm(adapter);
            } else {
                standardCounter.increment();
                result = compileService.compileTerm(adapter); // exact same pipeline as /compile
            }
            results.add(result);

            if (result.isPass()) {
                passExpressions.add(new Expression(
                        result.translatedPattern(),
                        compiler.toExpressionFlags(result.hyperscanFlags()),
                        index));
            }
            index++;
        }

        long elapsed = System.currentTimeMillis() - startMs;
        CompileResponse jsonResponse = CompileResponse.of(
                request.getLexiconRuleName(), results, elapsed, compiler.getHyperscanVersion());

        log.info("Bundle compile done rule='{}': {}/{} PASS, {}ms",
                request.getLexiconRuleName(), jsonResponse.passCount(),
                jsonResponse.totalTerms(), elapsed);

        return buildDatabasePortion(jsonResponse, passExpressions);
    }

    // ── NLT term handling ────────────────────────────────────────────────────

    /**
     * Compiles an {@code "NLT"} term: the pattern is the caller's
     * {@code termDescription} verbatim — no operator-language translation.
     *
     * <p>Flags are still derived automatically via {@link ScriptDetector} so
     * a raw non-Latin regex (e.g. a hand-written Korean or Arabic pattern)
     * gets UTF8/UCP without the caller having to know Hyperscan's flag
     * bitmask values. {@code requiresAndPostFilter} is always {@code false}
     * for NLT — the AND-lookahead post-filter convention is part of the
     * Standard operator language's semantics, not something an arbitrary
     * caller-supplied regex participates in.
     */
    private TermCompilationResult compileNltTerm(CompileRequest.TermInput adapter) {
        String pattern = adapter.termDescription();
        int flags = ScriptDetector.detect(pattern).recommendedHsFlags();

        HyperscanCompiler.ValidationResult validation = compiler.validate(pattern, flags);

        return validation.isPass()
                ? TermCompilationResult.pass(adapter, pattern, flags, false)
                : TermCompilationResult.failedHyperscan(
                        adapter, pattern, validation.errorMessage(), flags);
    }

    // ── Combined database ────────────────────────────────────────────────────

    private CompileBundleResult buildDatabasePortion(CompileResponse jsonResponse,
                                                       List<Expression> passExpressions) {
        if (passExpressions.isEmpty()) {
            log.warn("No PASS terms for rule '{}' — no combined database will be built",
                    jsonResponse.lexiconRuleName());
            return new CompileBundleResult(jsonResponse, null,
                    "No Hyperscan database file was produced because zero terms reached "
                  + "PASS status. See the JSON results for per-term compilationStatus and "
                  + "errorLog/translationError details.");
        }

        HyperscanCompiler.CombinedCompileResult combined =
                compiler.compileCombinedDatabase(passExpressions);

        if (combined.success()) {
            databaseBuiltCounter.increment();
            return new CompileBundleResult(jsonResponse, combined.databaseBytes(), null);
        }

        databaseFailedCounter.increment();
        log.error("Combined database compile FAILED for rule '{}': {} (failedExpressionId={})",
                jsonResponse.lexiconRuleName(), combined.errorMessage(), combined.failedExpressionId());

        String explanation = "No Hyperscan database file was produced.\nReason: "
                + combined.errorMessage()
                + (combined.failedExpressionId() != null
                        ? "\nThe term at results[" + combined.failedExpressionId()
                          + "] (0-based index) caused the combined compile to fail, even though "
                          + "it passed individual validation. See the JSON results for that term's details."
                        : "");
        return new CompileBundleResult(jsonResponse, null, explanation);
    }

    // ── Result carrier ───────────────────────────────────────────────────────

    /**
     * Carries the two zip-file payloads back to the controller.
     *
     * @param jsonResponse            identical shape to {@code /compile}'s response —
     *                                this is what gets written as the zip's JSON entry
     * @param hyperscanDatabaseBytes  the combined {@code .hdb} file content, or
     *                                {@code null} when no database could be built
     * @param databaseNote            explanation written into {@code NO_DATABASE.txt}
     *                                when {@code hyperscanDatabaseBytes} is null;
     *                                null when a database was built successfully
     */
    public record CompileBundleResult(
            CompileResponse jsonResponse,
            byte[]          hyperscanDatabaseBytes,
            String          databaseNote
    ) {
        /** @return true when a combined Hyperscan database was produced. */
        public boolean hasDatabase() {
            return hyperscanDatabaseBytes != null && hyperscanDatabaseBytes.length > 0;
        }
    }
}
