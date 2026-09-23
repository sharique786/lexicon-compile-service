package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The request body for {@code POST /compile} and {@code POST /compile/bundle}; {@code /compile/csv}
 * builds the same type internally.
 *
 * <pre>
 * {
 *   "request_id": "550e8400-e29b-41d4-a716-446655440000",
 *   "lexiconRuleName": "lexicon_research_1",
 *   "requestType": "Natural Language",
 *   "terms": [
 *     { "termId": "lexicon_research_1::1",
 *       "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))" },
 *     { "termId": "lexicon_research_1::2", "termDescription": "insider AND trading" }
 *   ]
 * }
 * </pre>
 * <ul>
 *   <li>{@code request_id}, {@code lexiconRuleName} — required, non-blank; {@code request_id} is echoed back.</li>
 *   <li>{@code requestType} — required, exactly {@code "Natural Language"} or {@code "Regex"} for ALL terms
 *       (mixed requests are not supported). See {@link TermType}.</li>
 *   <li>{@code terms} — required, non-empty; each needs a non-blank {@code termId} and
 *       {@code termDescription}. There is no per-request term-count limit.</li>
 *   <li>Unknown JSON properties are ignored.</li>
 * </ul>
 * See {@link TermType} for how {@code termDescription} is interpreted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TypedCompileRequest {

    /**
     * Caller-assigned identifier for this request, echoed unchanged in the
     * response JSON. Typically a UUID but any non-blank string is accepted.
     * Used to correlate requests and responses across distributed systems.
     */
    @NotBlank(message = "request_id must not be blank")
    @JsonProperty("request_id")
    private String requestId;

    @NotBlank(message = "lexiconRuleName must not be blank")
    @JsonProperty("lexiconRuleName")
    private String lexiconRuleName;

    /**
     * Compilation strategy applied uniformly to every term in this request.
     * See {@link TermType} for the exact semantics of each value.
     */
    @NotNull(message = "requestType must be exactly 'Natural Language' or 'Regex'")
    @JsonProperty("requestType")
    private TermType requestType;

    @NotEmpty(message = "terms list must not be empty")
    @Valid
    @JsonProperty("terms")
    private List<TermInput> terms = new ArrayList<>();

    // ── Nested record: TermInput ──────────────────────────────────────────────

    /**
     * One lexicon term.
     *
     * <p>The compact constructor replaces every run of newline, carriage-return and tab characters in
     * {@code termDescription} with one space before validation, so a term pasted from a multi-line source is
     * unchanged in meaning, and one consisting only of such characters is rejected as blank.
     *
     * @param termId          identifier echoed back in the result; for {@code /compile/bundle} it must end in
     *                        {@code ::<n>} (see {@code LexiconCompileBundleService})
     * @param termDescription an operator-language expression ({@link TermType#NATURAL_LANGUAGE}) or a raw PCRE
     *                        pattern ({@link TermType#REGEX}), decided by the root {@code requestType}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TermInput(

            @JsonProperty("termId")
            @NotBlank(message = "termId must not be blank")
            String termId,

            @JsonProperty("termDescription")
            @NotBlank(message = "termDescription must not be blank")
            String termDescription

    ) {
        /**
         * Strips newline ({@code \n}, {@code \r}) and tab ({@code \t})
         * characters out of {@code termDescription}, converting each run of
         * them to a single space, before this input is ever validated or
         * translated — a term pasted from a multi-line source should not
         * silently change meaning (or, for a Regex-type term, break PCRE
         * syntax) just because it carries stray control characters. Runs
         * before {@code @NotBlank} is evaluated, so a description that was
         * nothing but newlines/tabs is correctly rejected as blank.
         */
        public TermInput {
            termDescription = stripNewlinesAndTabs(termDescription);
        }

        private static String stripNewlinesAndTabs(String text) {
            return text == null ? null : text.replaceAll("[\\t\\n\\r]+", " ");
        }
    }

    // ── Derived helpers ────────────────────────────────────────────────────────

    /**
     * @return {@code true} when {@link #requestType} is {@link TermType#REGEX},
     * meaning every term's description is a raw PCRE pattern
     * requiring no operator-language translation.
     */
    public boolean isRegexType() {
        return requestType == TermType.REGEX;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getLexiconRuleName() {
        return lexiconRuleName;
    }

    public void setLexiconRuleName(String lexiconRuleName) {
        this.lexiconRuleName = lexiconRuleName;
    }

    public TermType getRequestType() {
        return requestType;
    }

    public void setRequestType(TermType requestType) {
        this.requestType = requestType;
    }

    public List<TermInput> getTerms() {
        return terms;
    }

    public void setTerms(List<TermInput> terms) {
        this.terms = terms;
    }
}
