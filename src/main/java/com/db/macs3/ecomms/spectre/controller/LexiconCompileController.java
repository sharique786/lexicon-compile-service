package com.db.macs3.ecomms.spectre.controller;

import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.db.macs3.ecomms.spectre.service.CsvCompileService;
import com.db.macs3.ecomms.spectre.service.LexiconCompileBundleService;
import com.db.macs3.ecomms.spectre.service.LexiconCompileService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST controller for the Lexicon Compile Service, base path {@code /api/lexicon}.
 * <dl>
 *   <dt>{@code POST /compile}</dt>
 *   <dd>JSON body (plain or GZIP) → per-term compile results.</dd>
 *   <dt>{@code POST /compile/csv}</dt>
 *   <dd>Multipart CSV file → the same results.</dd>
 *   <dt>{@code POST /compile/bundle}</dt>
 *   <dd>JSON body → a zip with the JSON results and one combined Hyperscan database.</dd>
 *   <dt>{@code GET /health}</dt>
 *   <dd>Engine status and the supported operator/language listing.</dd>
 * </dl>
 *
 * <p><b>Compression.</b> Request bodies with {@code Content-Encoding: gzip} are inflated by
 * {@code GzipRequestFilter}; responses are compressed by Tomcat ({@code server.compression.*}).
 * HTTP 200 is returned even when individual terms fail to compile.
 */
@RestController
@RequestMapping("/api/lexicon")
public class LexiconCompileController {

    private static final Logger log = LoggerFactory.getLogger(LexiconCompileController.class);

    private final LexiconCompileService compileService;
    private final CsvCompileService csvCompileService;
    private final LexiconCompileBundleService bundleService;
    private final ObjectMapper objectMapper;

    public LexiconCompileController(LexiconCompileService compileService,
                                    CsvCompileService csvCompileService,
                                    LexiconCompileBundleService bundleService,
                                    ObjectMapper objectMapper) {
        this.compileService = compileService;
        this.csvCompileService = csvCompileService;
        this.bundleService = bundleService;
        this.objectMapper = objectMapper;
    }

    // ── POST /api/lexicon/compile ─────────────────────────────────────────────

    /**
     * Compiles lexicon terms from a JSON body.
     *
     * <p>{@link TypedCompileRequest} is the single request type shared with
     * {@code /compile/bundle} (see that class's Javadoc) — {@code request_id}
     * is now required for every {@code /compile} call too, and {@code requestType}
     * lets a caller submit Regex-type terms here as well, not just
     * Natural Language.
     *
     * <p>Recommended client request:
     * <pre>
     * POST /api/lexicon/compile
     * Content-Type: application/json
     * Content-Encoding: gzip        ← request body is GZIP-compressed
     * Accept-Encoding:  gzip        ← client accepts GZIP response
     * </pre>
     *
     * @param request validated compile request
     * @return compile response with per-term results
     */
    @PostMapping(
            value = "/compile",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    /**
     * Compiles lexicon terms from a JSON body. {@link TypedCompileRequest} is the request type shared with
     * {@code /compile/bundle}: {@code request_id}, {@code lexiconRuleName}, {@code requestType} and a
     * non-empty {@code terms} list are all required. {@code requestType} is validated but this endpoint
     * translates every term as Natural Language; only {@code /compile/bundle} honours {@code "Regex"}.
     *
     * <p>Recommended client headers: {@code Content-Encoding: gzip} for a compressed body and
     * {@code Accept-Encoding: gzip} for a compressed response.
     *
     * @param request the validated request
     * @return the per-term results
     */
    public ResponseEntity<CompileResponse> compileJson(
            @Valid @RequestBody TypedCompileRequest request) {

        log.info("POST /compile — rule='{}', terms={}, request_id={}",
                request.getLexiconRuleName(), request.getTerms().size(), request.getRequestId());
        return ResponseEntity.ok(compileService.compile(request));
    }

    // ── POST /api/lexicon/compile/csv ─────────────────────────────────────────

    /**
     * Compiles lexicon terms from a CSV multipart file upload.
     *
     * <p>The CSV must have two columns: {@code Term ID} and
     * {@code Term Description}. The {@code Risk Driver Name} column is no
     * longer required and is silently ignored if present.
     *
     * <p>A UUID {@code request_id} is generated automatically for each call
     * and echoed back in the response for end-to-end request tracking.
     *
     * @param file     CSV file (UTF-8, BOM optional)
     * @param ruleName optional rule name override (defaults to filename without extension)
     * @return compile response with per-term results and a generated {@code request_id}
     */
    @PostMapping(
            value = "/compile/csv",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    /**
     * Compiles lexicon terms from a CSV upload (see {@link CsvCompileService} for the format). A UUID
     * {@code request_id} is generated and echoed in the response.
     *
     * @param file     the CSV file (UTF-8, BOM optional); an empty file is rejected with HTTP 400
     * @param ruleName optional rule name; defaults to the file name without its extension
     * @return the per-term results
     */
    public ResponseEntity<CompileResponse> compileCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ruleName", required = false) String ruleName) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        String effectiveRule = (ruleName != null && !ruleName.isBlank())
                ? ruleName
                : stripExtension(file.getOriginalFilename());

        // Generate a UUID for this request — echoed back in the response
        // so callers can correlate requests to responses end-to-end.
        String requestId = UUID.randomUUID().toString();

        log.info("POST /compile/csv — file='{}', ruleName='{}', size={}, request_id={}",
                file.getOriginalFilename(), effectiveRule, file.getSize(), requestId);

        try {
            return ResponseEntity.ok(
                    csvCompileService.compileFromCsv(file.getInputStream(), effectiveRule, requestId));
        } catch (IOException e) {
            log.error("Failed to parse CSV: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── POST /api/lexicon/compile/bundle ──────────────────────────────────────

    /**
     * Compiles lexicon terms with per-term {@code requestType} ("Natural Language" or
     * "Regex") and returns a zip file containing the JSON compile results
     * (identical shape to {@code /compile}'s response) and a single combined
     * Hyperscan database file built from every term that reached PASS.
     *
     * <p>Zip contents (on a 200 response):
     * <ul>
     *   <li>{@code {ruleName}-compile-results.json} — always present</li>
     *   <li>{@code {ruleName}.hdb} — present ONLY when EVERY term in the
     *       request reached PASS and the combined multi-pattern compile
     *       succeeded</li>
     *   <li>{@code NO_DATABASE.txt} — present instead of the {@code .hdb}
     *       file when at least one term did not reach PASS — including when
     *       every OTHER term passed — or when zero terms reached PASS
     *       (already fully explained by each term's own compilationStatus
     *       in the JSON; a combined database is never built at all in
     *       either case)</li>
     * </ul>
     *
     * <p><b>500 instead of a zip: every term PASSED but the combined build itself failed</b>
     * <p>When every term individually resolves PASS/FAILED normally but the
     * combined multi-pattern Hyperscan database build/serialisation then
     * fails anyway (e.g. a flag/state-count interaction only visible once
     * every PASS expression is compiled together — see
     * {@code LexiconCompileBundleService.CompileBundleResult#databaseBuildFailed}),
     * this endpoint does NOT return a 200 zip with a buried
     * {@code NO_DATABASE.txt} note next to per-term statuses that would still
     * read PASS. It returns HTTP 500 with {@code Content-Type: application/json}
     * instead of a zip — body is the same JSON shape {@code /compile} returns,
     * with the top-level {@code databaseError} field populated (see
     * {@link CompileResponse#databaseError()}).
     *
     * <p>The {@code .hdb} file is produced by
     * {@code com.gliwka.hyperscan.wrapper.Database#save}, which writes both
     * the per-expression metadata (id/pattern/flags) and the serialised
     * native database into one stream — it can be loaded directly via
     * {@code Database.load(InputStream)} with no separate metadata file.
     * Each expression's id equals its term's 0-based index in the request's
     * {@code terms} array (and in the JSON {@code results} array).
     *
     * @param request validated typed-compile request
     * @return zip file (application/zip) with the JSON results and the
     * combined Hyperscan database
     */
    @PostMapping(
            value = "/compile/bundle",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "application/zip"
    )
    /**
     * Compiles the request's terms and returns a zip with the JSON results and one combined Hyperscan
     * database.
     *
     * <p>Zip contents (HTTP 200, {@code application/zip}):
     * <ul>
     *   <li>{@code {ruleName}-compile-results.json} — always present, the same shape as {@code /compile}'s response;</li>
     *   <li>{@code {ruleName}.hdb} — present only when EVERY term reached PASS and the combined build succeeded;</li>
     *   <li>{@code NO_DATABASE.txt} — instead of the {@code .hdb} when any term did not reach PASS (even if all
     *       others passed) or none did.</li>
     * </ul>
     * The zip file name is {@code {ruleName}-compile-bundle.zip}, with the rule name reduced to
     * {@code [a-zA-Z0-9._-]}.
     *
     * <p><b>HTTP 500 instead of a zip</b> when every term PASSED but the combined build itself failed: the body
     * is {@code application/json} in {@code /compile}'s shape with {@code databaseError} set, so per-term PASS
     * statuses cannot be mistaken for a usable bundle.
     *
     * <p>The {@code .hdb} is written by {@code Database#save} (expression metadata plus the serialised native
     * database) and loads directly with {@code Database.load(InputStream)}. Each expression's id is its term
     * number (or an auxiliary id); see {@code HyperscanCombinationHandler}.
     *
     * @param request the validated request
     */
    public ResponseEntity<byte[]> compileBundle(
            @Valid @RequestBody TypedCompileRequest request) {

        log.info("POST /compile/bundle — rule='{}', terms={}",
                request.getLexiconRuleName(), request.getTerms().size());

        LexiconCompileBundleService.CompileBundleResult bundle = bundleService.buildBundle(request);

        // Every term reached PASS/FAILED normally, but the combined Hyperscan database
        // build/serialisation itself then failed — a genuine system-level failure, not a
        // per-term translation problem (see CompileBundleResult.databaseBuildFailed
        // Javadoc). Returning a 200 zip here (with only a buried NO_DATABASE.txt note and
        // every term's own compilationStatus still reading PASS) would misrepresent this as
        // success. Surface it as an explicit HTTP error with the JSON results — carrying
        // databaseError — as the body, instead of a zip.
        if (bundle.databaseBuildFailed()) {
            log.error("compile/bundle database build failed for rule '{}' despite {} PASS term(s): {}",
                    request.getLexiconRuleName(), bundle.jsonResponse().passCount(), bundle.databaseNote());
            byte[] errorJson = objectMapper.writeValueAsBytes(bundle.jsonResponse());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson);
        }

        byte[] zipBytes;
        try {
            zipBytes = buildBundleZip(request.getLexiconRuleName(), bundle);
        } catch (IOException e) {
            log.error("Failed to build compile-bundle zip for rule '{}': {}",
                    request.getLexiconRuleName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        String zipFilename = sanitizeFilename(request.getLexiconRuleName()) + "-compile-bundle.zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilename + "\"")
                .contentType(MediaType.valueOf("application/zip"))
                .body(zipBytes);
    }

    /**
     * Builds the zip in memory: the JSON results, then the {@code .hdb} or a {@code NO_DATABASE.txt} explanation.
     */
    private byte[] buildBundleZip(String ruleName,
                                  LexiconCompileBundleService.CompileBundleResult bundle)
            throws IOException {

        String safeName = sanitizeFilename(ruleName);
        ByteArrayOutputStream zipBuffer = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(zipBuffer)) {

            // 1. JSON results — identical shape to /compile's response
            zos.putNextEntry(new ZipEntry(safeName + "-compile-results.json"));
            zos.write(objectMapper.writeValueAsBytes(bundle.jsonResponse()));
            zos.closeEntry();

            // 2. Combined Hyperscan database, or an explanatory note
            if (bundle.hasDatabase()) {
                zos.putNextEntry(new ZipEntry(safeName + ".hdb"));
                zos.write(bundle.hyperscanDatabaseBytes());
                zos.closeEntry();
            } else {
                zos.putNextEntry(new ZipEntry("NO_DATABASE.txt"));
                zos.write(bundle.databaseNote().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        return zipBuffer.toByteArray();
    }

    /**
     * Reduces {@code name} to {@code [a-zA-Z0-9._-]} (anything else becomes {@code _}) for safe zip and
     * file names; a blank name becomes {@code lexicon_rule}.
     */
    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "lexicon_rule";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ── GET /api/lexicon/health ───────────────────────────────────────────────

    /**
     * Liveness plus a static listing of the engine, library versions, supported operators and languages.
     * The Spring Actuator {@code /actuator/health} additionally probes the native library.
     */
    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> engineHealth() {
        var info = new LinkedHashMap<String, Object>();
        info.put("status", "UP");
        info.put("engineMode", compileService.getEngineMode());
        info.put("hyperscanLibrary", "com.gliwka.hyperscan");
        info.put("hyperscanVersion", "5.4.0-2.0.0");
        info.put("springBoot", "4.0.6");
        info.put("jdk", "21");
        info.put("compressionMode", "GZIP request + response");
        info.put("supportedOperators",
                List.of("OR", "AND", "AND NOT", "NOT", "NEAR{n}", "FOLLOWEDBY{n}"));
        info.put("supportedLanguages",
                List.of("English", "Korean", "Japanese", "Chinese", "Mandarin",
                        "Arabic", "Hebrew", "German", "Turkish", "Emoji", "Leet-speak"));
        info.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(info);
    }

    private String stripExtension(String filename) {
        if (filename == null) {
            return "unknown_rule";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
