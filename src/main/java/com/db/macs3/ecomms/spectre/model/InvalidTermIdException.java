package com.db.macs3.ecomms.spectre.model;

/**
 * Thrown when a {@code /compile/bundle} request's term ids don't satisfy
 * what the endpoint needs to build a combined Hyperscan database with
 * term-number-based expression ids — see
 * {@code LexiconCompileBundleService} class Javadoc's "Hyperscan expression
 * id scheme" section.
 *
 * <p>Specifically: every {@code termId} must end with {@code ::<n>} where
 * {@code n} is a non-negative integer (the "term number" convention already
 * used throughout the surveillance platform, e.g. {@code lexicon_rule_1::1}),
 * and every term number in one request must be unique. Both are checked for
 * the WHOLE request before any term is compiled — a downstream Hyperscan id
 * collision would silently corrupt the combined database (two terms
 * fighting over the same expression id), so this is validated up front and
 * rejected clearly rather than surfacing as a confusing compile failure.
 */
public class InvalidTermIdException extends RuntimeException {
    public InvalidTermIdException(String message) {
        super(message);
    }
}
