package com.db.macs3.ecomms.spectre.translator;

/**
 * Thrown by {@link Tokenizer}, {@link ExpressionParser}, or
 * {@link PatternCodeGenerator} when a lexicon term cannot be translated —
 * either a syntax/validation error in the term itself, or (rarely) an
 * unrecoverable internal condition. Always carries a human-readable message
 * suitable for surfacing directly to the compliance analyst who authored
 * the term; see each throw site for the specific guidance given.
 */
public final class TranslationException extends RuntimeException {
    public TranslationException(String message) {
        super(message);
    }
}
