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
 * Request body for {@code POST /api/lexicon/compile/bundle}.
 *
 * <p><b>Schema changes from the previous per-term version</b>
 * <ul>
 *   <li>{@code requestType} has moved from each individual {@code TermInput} to
 *       the <em>root level</em> — a single request now declares one term type
 *       for all its terms. Mixed-type requests are no longer supported; submit
 *       separate requests for Natural Language and Regex terms.</li>
 *   <li>{@code riskDriverName} has been removed entirely.</li>
 *   <li>{@code request_id} has been added for end-to-end request tracing; it
 *       is echoed back unchanged in the response JSON.</li>
 * </ul>
 *
 * <p>JSON example:
 * <pre>
 * {
 *   "request_id": "550e8400-e29b-41d4-a716-446655440000",
 *   "lexiconRuleName": "lexicon_research_1",
 *   "requestType": "Natural Language",
 *   "terms": [
 *     {
 *       "termId": "lexicon_research_1::1",
 *       "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))"
 *     },
 *     {
 *       "termId": "lexicon_research_1::2",
 *       "termDescription": "insider AND trading"
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>See {@link TermType} for exactly what {@code "Natural Language"} and
 * {@code "Regex"} mean for how {@code termDescription} is interpreted.
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
     * One lexicon term. Contains only the term's identifier and its description.
     * {@code requestType} and {@code riskDriverName} are no longer carried
     * per-term (see root-level fields above).
     *
     * @param termId          unique identifier, echoed back in the result
     * @param termDescription operator-language expression ({@link TermType#NATURAL_LANGUAGE})
     *                        or raw PCRE pattern ({@link TermType#REGEX}) —
     *                        determined by the root {@code requestType}
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
