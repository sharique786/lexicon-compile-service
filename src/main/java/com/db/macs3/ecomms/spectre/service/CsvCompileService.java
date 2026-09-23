package com.db.macs3.ecomms.spectre.service;

import com.db.macs3.ecomms.spectre.model.CompileResponse;
import com.db.macs3.ecomms.spectre.model.TermType;
import com.db.macs3.ecomms.spectre.model.TypedCompileRequest;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an uploaded lexicon CSV and delegates to {@link LexiconCompileService}.
 *
 * <p><b>Format</b> — two columns, {@code Term ID} and {@code Term Description}:
 * <pre>
 * Term ID, Term Description
 * lexicon_research_1::1, (manipulate*) NEAR{5} ((price) OR (spread))
 * lexicon_research_1::2, "((""please don't forward"") OR (""do not share""))"
 * </pre>
 * <ul>
 *   <li>A header row is detected when the first column contains "term id" (any case) and is skipped.</li>
 *   <li>RFC 4180 quoting through OpenCSV ({@code ""} inside quotes is a literal quote); cells are trimmed.</li>
 *   <li>A UTF-8 BOM (Excel export) is stripped; the file is read as UTF-8.</li>
 *   <li>Skipped: blank rows, rows whose first cell starts with {@code #}, and rows with fewer than two
 *       columns (logged). Extra columns are ignored.</li>
 * </ul>
 * Every row is Natural-Language syntax: the service builds a {@link TypedCompileRequest} with
 * {@code requestType = NATURAL_LANGUAGE}. CSV parsing itself is lenient — a bad row never fails the
 * upload; a bad TERM fails only that term's result.
 */
@Service
public class CsvCompileService {

    private static final Logger log = LoggerFactory.getLogger(CsvCompileService.class);

    private final LexiconCompileService compileService;

    public CsvCompileService(LexiconCompileService compileService) {
        this.compileService = compileService;
    }

    /**
     * Parses the CSV and compiles every term. A CSV with no term rows yields a response with zero terms.
     *
     * @param csvStream raw CSV bytes (UTF-8, BOM optional)
     * @param ruleName  the lexicon rule name for the response
     * @param requestId a generated UUID, echoed as {@code request_id}
     * @throws IOException if the CSV cannot be parsed
     */
    public CompileResponse compileFromCsv(InputStream csvStream, String ruleName, String requestId)
            throws IOException {
        List<TypedCompileRequest.TermInput> terms = parseCsv(csvStream, ruleName);
        log.info("CSV parsed: {} terms for rule '{}' (request_id={})",
                terms.size(), ruleName, requestId);

        TypedCompileRequest request = new TypedCompileRequest();
        request.setRequestId(requestId);
        request.setLexiconRuleName(ruleName);
        request.setRequestType(TermType.NATURAL_LANGUAGE);
        request.setTerms(terms);
        // SOM_LEFTMOST application is decided internally, per-expression, based on whether
        // it's a plain, QUIET, or COMBINATION expression -- see HyperscanCompiler.
        return compileService.compile(request);
    }

    // ── CSV parsing ───────────────────────────────────────────────────────────

    private List<TypedCompileRequest.TermInput> parseCsv(InputStream raw, String source)
            throws IOException {
        List<TypedCompileRequest.TermInput> terms = new ArrayList<>();

        try (CSVReader reader = new CSVReaderBuilder(
                new InputStreamReader(stripBom(raw), StandardCharsets.UTF_8))
                .withCSVParser(new CSVParserBuilder()
                        .withSeparator(',')
                        .withQuoteChar('"')
                        .withIgnoreLeadingWhiteSpace(true)
                        .build())
                .build()) {

            List<String[]> rows = reader.readAll();
            if (rows.isEmpty()) {
                return terms;
            }

            int start = isHeaderRow(rows.getFirst()) ? 1 : 0;

            for (int i = start; i < rows.size(); i++) {
                String[] row = rows.get(i);
                int lineNum = i + 1;

                if (isBlankRow(row)) {
                    continue;
                }
                if (row.length > 0 && row[0].trim().startsWith("#")) {
                    continue;
                }
                if (row.length < 2) {
                    log.warn("Skipping CSV row {} in '{}': only {} column(s)",
                            lineNum, source, row.length);
                    continue;
                }

                terms.add(new TypedCompileRequest.TermInput(
                        row[0].trim(),
                        row[1].trim()));   // riskDriverName removed; any extra columns ignored
            }

        } catch (CsvException e) {
            throw new IOException(
                    "CSV parse error in '" + source + "': " + e.getMessage(), e);
        }

        return terms;
    }

    private boolean isHeaderRow(String[] row) {
        return row != null && row.length > 0
                && row[0].trim().toLowerCase().contains("term id");
    }

    private boolean isBlankRow(String[] row) {
        if (row == null) {
            return true;
        }
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips UTF-8 BOM (EF BB BF) that Excel prepends to CSV exports.
     */
    private InputStream stripBom(InputStream is) throws IOException {
        PushbackInputStream pis = new PushbackInputStream(is, 3);
        byte[] bom = new byte[3];
        int bytesRead = pis.read(bom, 0, 3);
        if (bytesRead == 3
                && (bom[0] & 0xFF) == 0xEF
                && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF) {
            return pis;
        }
        if (bytesRead > 0) {
            pis.unread(bom, 0, bytesRead);
        }
        return pis;
    }
}
