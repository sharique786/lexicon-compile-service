package com.db.macs3.ecomms.spectre.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a request's {@code termDescription}s are compiled. The JSON values are exact and case-sensitive:
 * <ul>
 *   <li>{@link #NATURAL_LANGUAGE} ({@code "Natural Language"}) — the operator language (OR, AND, AND NOT,
 *       NOT, NEAR{n}, FOLLOWEDBY{n}, wildcards, quoted phrases), translated to Hyperscan PCRE by
 *       {@link com.db.macs3.ecomms.spectre.translator.TermSyntaxTranslator}.</li>
 *   <li>{@link #REGEX} ({@code "Regex"}) — {@code termDescription} is already a PCRE pattern, compiled as
 *       given with no translation; UTF8/UCP are still derived from the pattern's script. Honoured by
 *       {@code /compile/bundle} only.</li>
 * </ul>
 * The same two names are used by the downstream Lexicon Scanner Service.
 */
public enum TermType {

    /**
     * Operator-language syntax, translated via {@code TermSyntaxTranslator}.
     */
    NATURAL_LANGUAGE("Natural Language"),

    /**
     * Already-valid PCRE, compiled verbatim with no translation.
     */
    REGEX("Regex");

    private final String jsonValue;

    TermType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /**
     * The exact string used in JSON request/response bodies, e.g. {@code "requestType": "Natural Language"}.
     */
    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }

    /**
     * Resolves a JSON {@code requestType} string to a {@link TermType}, exact
     * match only (case-sensitive, matching {@link #jsonValue()} precisely).
     *
     * @param value the raw JSON string, e.g. {@code "Natural Language"} or {@code "Regex"}
     * @return the matching {@link TermType}
     * @throws IllegalArgumentException if {@code value} matches neither constant
     */
    @JsonCreator
    public static TermType fromJsonValue(String value) {
        for (TermType termType : values()) {
            if (termType.jsonValue.equals(value)) {
                return termType;
            }
        }
        throw new IllegalArgumentException(
                "requestType must be exactly 'Natural Language' or 'Regex', got: '" + value + "'");
    }
}
