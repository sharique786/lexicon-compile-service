package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser: {@code List<Token>} → {@link Ast}.
 *
 * <p><b>Grammar</b> — loosest-binding rule first, tightest last:
 * <pre>
 * orExpr        := andNotExpr ( OR andNotExpr )*
 * andNotExpr    := andExpr ( AND_NOT requiredGroup )*        [the "AND NOT (...)" spelling]
 * andExpr       := proximityExpr ( AND proximityExpr )*      [a NOT-group operand is folded into AndNot]
 * proximityExpr := atom ( (NEAR | FOLLOWEDBY) atom )*        [left-associative]
 * atom          := '(' orExpr ')' | notGroup | QUOTED_PHRASE | wordOrPhrase
 * notGroup      := NOT requiredGroup                         [only valid as an AND operand — see below]
 * requiredGroup := '(' orExpr ')'
 * wordOrPhrase  := WORD+                                     [greedy]
 * </pre>
 * NEAR/FOLLOWEDBY therefore bind tighter than AND, AND tighter than AND NOT, and
 * all of them tighter than OR. Parentheses are resolved at whatever depth they
 * occur, by plain recursion ({@code atom → '(' orExpr ')'}); redundant wrapping
 * such as {@code (((me) OR (cking)))} needs no special handling.
 *
 * <p><b>{@code NOT} is always a parenthesised group, always paired with {@code AND}</b>
 * <pre>
 *   bond AND (NOT (james bond))                 -- valid
 *   apple AND (NOT (apple NEAR{10} banana))     -- valid: NOT may wrap any sub-expression
 *   apple AND NOT (banana)                      -- valid: the "glued" spelling, same AST
 *   NOT (james bond)                            -- rejected: nothing required precedes it
 *   apple NOT NEAR{10} banana                   -- rejected: NOT directly before a proximity operator
 *   apple AND NOT NEAR{10} banana               -- rejected: NOT not followed by '('
 * </pre>
 * Both valid spellings produce the same {@link Ast.AndNot}. {@link #parseAtom}
 * recognises {@code NOT '('} as an internal {@link Ast.Not} atom and
 * {@link #parseAnd} folds every {@code Not} operand at its level into one
 * {@code AndNot}. A {@code Not} that never finds an enclosing {@code AND}
 * (standalone, as an {@code OR} alternative, or as a proximity operand) is a
 * parse-time error, so no {@code Not} node ever reaches {@link PatternCodeGenerator}.
 * A {@code NOT} that does not start a group ({@code (NOT LAUNCHING)}) is
 * ordinary literal text.
 *
 * <p><b>Unwrapped multi-word phrases are accepted.</b> {@code wordOrPhrase}
 * greedily joins consecutive bare words into one {@link Ast.Phrase}, so
 * {@code bomb this place OR blow this place up} works without parentheses;
 * operators, parentheses and end of input end the run.
 *
 * <p><b>Limits and rejections</b>
 * <ul>
 *   <li>More than {@link ParseContext#MAX_AND_OPERANDS} operands at one AND level
 *       is rejected — AND is expanded into every ordering of its operands.</li>
 *   <li>A NEAR/FOLLOWEDBY that is an {@code OR} alternative with other alternatives
 *       on BOTH sides is rejected ({@link #rejectSandwichedProximity}); at either
 *       edge of the OR list it is allowed.</li>
 *   <li>Empty parentheses, a bare {@code NOT} between two expressions, and any
 *       unexpected token are rejected with a specific message.</li>
 * </ul>
 *
 * <p><b>Warnings (the term still compiles)</b>
 * <ul>
 *   <li><b>Chained proximity</b> — {@code A FOLLOWEDBY{5} B FOLLOWEDBY{6} C} without
 *       parentheses is parsed as {@code (A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C}.
 *       Existing terms use this form, so it is accepted; a caller with more context
 *       (e.g. an authoring UI) can turn the warning into a stricter policy.</li>
 *   <li><b>Possible intra-word split</b> — {@code NEAR{1}}/{@code FOLLOWEDBY{1}} with a
 *       single-character bare-word operand, e.g. {@code (F) FOLLOWEDBY{1} (cking)}
 *       meant to catch "Fucking". Proximity measures whole-word distance, so two
 *       fragments inside one word can never match; the term should be a literal
 *       word or a Regex-type term instead. This is a heuristic (it can miss longer
 *       fragments and flag a deliberate single-letter word), hence a warning only.</li>
 * </ul>
 */
final class ExpressionParser {

    private final List<Token> tokens;
    private final String originalTerm;
    private final List<String> warnings = new ArrayList<>();
    private int currentTokenIndex = 0;

    private ExpressionParser(List<Token> tokens, String originalTerm) {
        this.tokens = tokens;
        this.originalTerm = originalTerm;
    }

    /**
     * The parsed AST, plus any non-fatal warnings accumulated while parsing (never null; may be empty).
     */
    record ParseResult(Ast ast, List<String> warnings) {
    }

    /**
     * Parses a fully-tokenized lexicon term into an {@link Ast}.
     *
     * @param tokens       token stream from {@link Tokenizer#tokenize}
     * @param originalTerm the original (preprocessed) term text, for error messages
     * @return the root AST node plus any warnings accumulated while parsing
     * @throws TranslationException on any grammatical/structural validation failure
     */
    static ParseResult parse(List<Token> tokens, String originalTerm) {
        if (tokens.isEmpty()) {
            throw new TranslationException("Lexicon term is empty: '" + originalTerm + "'.");
        }
        ExpressionParser parser = new ExpressionParser(tokens, originalTerm);
        Ast result = parser.parseOr();
        if (parser.currentTokenIndex != tokens.size()) {
            if (parser.peekIs(Token.Not.class)) {
                // A bare NOT sitting AFTER a fully-parsed expression, where an operator
                // (OR/AND/AND NOT/NEAR/FOLLOWEDBY) was expected — see standaloneNotOperatorError().
                throw parser.standaloneNotOperatorError();
            }
            throw parser.unexpectedTokenError("after '" + parser.describe(result) + "'");
        }
        // The whole term's root is the one place a deferred Ast.Not (see foldNotOperands)
        // can NEVER find an enclosing AND to fold into — reject it here, e.g. a bare
        // "NOT (james bond)" with nothing else in the entire term.
        parser.rejectBareNot(result, "standalone, as the entire term");
        return new ParseResult(result, parser.warnings);
    }

    // ── Grammar levels ───────────────────────────────────────────────────────

    private Ast parseOr() {
        List<Ast> alternatives = new ArrayList<>();
        alternatives.add(parseAndNot());
        while (peekIs(Token.Or.class)) {
            advance();
            alternatives.add(parseAndNot());
        }
        if (alternatives.size() == 1) {
            // No actual OR combination happened — a deferred Ast.Not (see
            // foldNotOperands) simply passes through unchanged, still eligible to be
            // folded by an ENCLOSING AND once this bubbles further up.
            return alternatives.getFirst();
        }
        for (Ast alternative : alternatives) {
            rejectBareNot(alternative, "as an OR alternative");
        }
        rejectSandwichedProximity(alternatives);
        return new Ast.Or(alternatives);
    }

    /**
     * Rejects a NEAR/FOLLOWEDBY operator that is one alternative of an OR with other
     * alternatives on BOTH sides, e.g.
     * {@code "(I) OR (you) OR (them) FOLLOWEDBY{3} (sin bin) OR (penalty box)"}.
     * It would relate only the two operands next to the operator and silently leave every
     * other alternative unrelated to it — almost never what the author meant.
     *
     * <p>A NEAR/FOLLOWEDBY alternative at either EDGE of the OR list is allowed, e.g.
     * {@code "(plain phrase) OR ((EURIBOR FIXING) NEAR{2} TENOR)"}: nothing else on that
     * side could be meant to share its scope. See {@code PatternDecomposer} class Javadoc
     * for how such a term is compiled.
     *
     * @throws TranslationException naming the ambiguous shape and how to fix it
     */
    private void rejectSandwichedProximity(List<Ast> alternatives) {
        for (int i = 1; i < alternatives.size() - 1; i++) {
            Ast alternative = alternatives.get(i);
            if (alternative instanceof Ast.Near || alternative instanceof Ast.FollowedBy) {
                throw new TranslationException(
                        "A NEAR/FOLLOWEDBY operator was found combined with OR at the same level, with OTHER OR"
                        + " alternatives on BOTH sides of it, in term: '" + originalTerm + "'. This is ambiguous"
                        + " — it is unclear whether the proximity operator should apply only to its own two"
                        + " immediate operands (as currently written, with every other OR alternative unrelated"
                        + " to it), or to a wider group including some of the surrounding OR alternatives too."
                        + " Add explicit parentheses to make the intended grouping unambiguous — e.g. group every"
                        + " alternative meant to be on the SAME side of the proximity operator together, such as"
                        + " '((alt1) OR (alt2) OR ...) NEAR{n} ((altX) OR (altY) OR ...)'; or, if the proximity"
                        + " clause really is meant to be its own independent OR alternative with no relation to"
                        + " the others, restructure the term so it sits at an EDGE of its OR list instead of in"
                        + " the middle, e.g. '(alt1) OR (alt2) OR (altZ) OR ((altX) NEAR{n} (altY))'.");
            }
        }
    }

    private Ast parseAndNot() {
        Ast requiredOperand = parseAnd();
        List<Ast> excludedOperands = new ArrayList<>();
        while (peekIs(Token.AndNot.class)) {
            advance();
            excludedOperands.add(parseRequiredGroup());
        }
        if (excludedOperands.isEmpty()) {
            // No actual "AND NOT" token found at this level — a deferred Ast.Not (see
            // foldNotOperands) simply passes through unchanged.
            return requiredOperand;
        }
        rejectBareNot(requiredOperand, "as the required side of AND NOT");
        return new Ast.AndNot(requiredOperand, excludedOperands);
    }

    /**
     * Rejects {@code ast} if it is a {@link Ast.Not} that {@link #foldNotOperands} deferred,
     * i.e. one that never found an enclosing {@code AND} to fold into and is about to be
     * used where {@code NOT} is unsupported: standalone, as an {@code OR} alternative, as a
     * NEAR/FOLLOWEDBY operand, or as the required side of an AND NOT.
     */
    private void rejectBareNot(Ast ast, String context) {
        if (ast instanceof Ast.Not) {
            throw new TranslationException(
                    "NOT must always be combined with a preceding required expression via AND — "
                    + "'NOT (...)' cannot be used " + context + " in term: '" + originalTerm + "'."
                    + " Write it as 'X AND (NOT (Y))' or 'X AND NOT (Y)', e.g. 'bond AND (NOT (james bond))'.");
        }
    }

    /**
     * The excluded side of {@code AND NOT} must be an explicit parenthesised group — never
     * a bare word/phrase and never a proximity operator directly after {@code NOT}.
     */
    private Ast parseRequiredGroup() {
        if (!peekIs(Token.LParen.class)) {
            throw new TranslationException(
                    "NOT must always be followed immediately by a parenthesised group in term: '"
                    + originalTerm + "'. Write the exclusion as 'X AND NOT (Y)', e.g. 'price AND NOT"
                    + " (legitimate)' instead of 'price AND NOT legitimate', or 'price AND NOT (rigging"
                    + " NEAR{5} change)' instead of 'price AND NOT rigging NEAR{5} change'.");
        }
        return parseParenGroup();
    }

    /**
     * {@code andExpr := proximityExpr ( AND proximityExpr )*}
     *
     * <p>Caps the operand count at {@link ParseContext#MAX_AND_OPERANDS}:
     * {@link PatternCodeGenerator} expresses AND as every ordering (N!) of its operands, so the
     * count directly controls pattern size. Any {@link Ast.Not} operands are then folded into
     * one {@link Ast.AndNot} by {@link #foldNotOperands}.
     */
    private Ast parseAnd() {
        List<Ast> operands = new ArrayList<>();
        operands.add(parseProximity());
        while (peekIs(Token.And.class)) {
            advance();
            operands.add(parseProximity());
        }
        if (operands.size() > ParseContext.MAX_AND_OPERANDS) {
            throw new TranslationException(
                    "Too many AND operands at the same level (" + operands.size() + ") in term: '"
                            + originalTerm + "'. A maximum of " + ParseContext.MAX_AND_OPERANDS + " is supported"
                            + " — each additional operand multiplies the size of the resulting Hyperscan pattern."
                            + " Split this term into multiple simpler lexicon terms instead.");
        }
        return foldNotOperands(operands);
    }

    /**
     * Folds every {@link Ast.Not} operand collected at one {@code AND} level into a single
     * {@link Ast.AndNot}, so {@code "bond AND (NOT (james bond))"} and
     * {@code "bond AND NOT (james bond)"} produce the same
     * {@code AndNot(required=bond, excluded=[james bond])}. Several NOT-groups at one level
     * are all excluded, like chained {@code AND NOT}.
     *
     * <p><b>Deferral:</b> when {@code operands} is exactly one bare {@link Ast.Not} it is
     * returned unchanged — in {@code "bond AND (NOT (james bond))"} the inner parentheses are
     * parsed on their own, and "bond" is only visible to the OUTER {@code AND}, which folds it.
     * If no enclosing {@code AND} ever appears, {@link #rejectBareNot} rejects it.
     *
     * @throws TranslationException if several operands were collected and all are
     * {@link Ast.Not} — a NOT-group always needs a preceding non-NOT operand
     */
    private Ast foldNotOperands(List<Ast> operands) {
        List<Ast> required = new ArrayList<>();
        List<Ast> excluded = new ArrayList<>();
        for (Ast operand : operands) {
            if (operand instanceof Ast.Not not) {
                excluded.add(not.operand());
            } else {
                required.add(operand);
            }
        }
        if (excluded.isEmpty()) {
            return operands.size() == 1 ? operands.getFirst() : new Ast.And(operands);
        }
        if (required.isEmpty()) {
            if (operands.size() == 1) {
                return operands.getFirst(); // deferred — see Javadoc above
            }
            throw new TranslationException(
                    "NOT must always be combined with a preceding required expression via AND — "
                    + "'NOT (...)' cannot stand on its own in term: '" + originalTerm + "'."
                    + " Write it as 'X AND (NOT (Y))' or 'X AND NOT (Y)', e.g. 'bond AND (NOT (james bond))'.");
        }
        Ast requiredAst = required.size() == 1 ? required.getFirst() : new Ast.And(required);
        return new Ast.AndNot(requiredAst, excluded);
    }

    /**
     * {@code proximityExpr := atom ( (NEAR | FOLLOWEDBY) atom )*}
     *
     * <p>Chained operators at one level are accepted (with a warning): each further operator
     * takes the accumulated result as its left operand, so
     * {@code A FOLLOWEDBY{5} B FOLLOWEDBY{6} C} builds the same AST as the explicit
     * {@code (A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C}. An explicitly parenthesised group is parsed
     * at its own level and never triggers the chained-operator warning.
     */
    private Ast parseProximity() {
        Ast leftOperand = parseAtom();
        if (!peekIsNearOrFollowedBy()) {
            return leftOperand;
        }
        Ast result = consumeProximityOperator(leftOperand);
        while (peekIsNearOrFollowedBy()) {
            warnChainedProximityOperator();
            result = consumeProximityOperator(result);
        }
        return result;
    }

    private Ast consumeProximityOperator(Ast leftOperand) {
        rejectBareNot(leftOperand, "as a NEAR/FOLLOWEDBY operand");
        Token proximityToken = advance();
        Ast rightOperand = parseAtom();
        rejectBareNot(rightOperand, "as a NEAR/FOLLOWEDBY operand");

        boolean isNear = proximityToken instanceof Token.Near;
        int distance = isNear
                ? ((Token.Near) proximityToken).distance()
                : ((Token.FollowedBy) proximityToken).distance();
        String keyword = isNear ? LexiconOperatorKeyword.NEAR : LexiconOperatorKeyword.FOLLOWEDBY;
        warnPossibleIntraWordSplit(leftOperand, rightOperand, keyword, distance);

        return isNear
                ? new Ast.Near(leftOperand, rightOperand, distance)
                : new Ast.FollowedBy(leftOperand, rightOperand, distance);
    }

    /**
     * Records a warning when a {@code NEAR{1}}/{@code FOLLOWEDBY{1}} node has a
     * single-character bare word as an operand — the visible shape of an author trying to
     * spell ONE word by gluing a letter onto a fragment, e.g. {@code (F) FOLLOWEDBY{1} (cking)}
     * intending "Fucking". NEAR/FOLLOWEDBY measure whole-word distance, so fragments inside
     * the same word can never satisfy any distance and the term can never match its target.
     *
     * <p>A heuristic, not a proof: distance 1 is the only one that can be confused with "no
     * gap at all" (the minimum allowed distance is 1, not 0), and a single-character operand is
     * the signature of a split-off prefix/suffix. It can miss longer fragments and flag a
     * deliberate single-letter word such as {@code (a) FOLLOWEDBY{1} (boy)}, so it is only a
     * warning.
     */
    private void warnPossibleIntraWordSplit(Ast leftOperand, Ast rightOperand, String keyword, int distance) {
        if (distance != 1) {
            return;
        }
        if (!isSingleCharacterWord(leftOperand) && !isSingleCharacterWord(rightOperand)) {
            return;
        }
        warnings.add(
                keyword + "{1} was used with a single-character operand in term: '" + originalTerm + "'."
                        + " This shape often means the author intended to spell out ONE word by gluing a"
                        + " leading/trailing letter directly onto a fragment (e.g. '(F) " + keyword
                        + "{1} (cking)' intending to catch \"Fucking\") — but " + keyword + " measures WHOLE-WORD"
                        + " distance, and two fragments that land inside the SAME word have no measurable"
                        + " word-gap between them at all, so this can NEVER match, however small the distance."
                        + " If this term needs to match a specific WORD (not two separate words), express it"
                        + " directly as a literal word/phrase, or as a Regex-type term with an infix/wildcard"
                        + " pattern for the word itself — not as a word-proximity chain. (If the operands really"
                        + " are two separate words, e.g. \"F up\"/\"F me\", this term is fine as written and this"
                        + " warning can be ignored.)");
    }

    /**
     * True when {@code ast} is a bare single-character {@link Ast.Word} (e.g. {@code F}).
     * Deliberately false for an {@link Ast.Or}/{@link Ast.Phrase}, even when one of their
     * words is a single character — the heuristic targets the direct-operand shape only.
     */
    private static boolean isSingleCharacterWord(Ast ast) {
        return ast instanceof Ast.Word word && word.text().length() == 1;
    }

    /**
     * Records a warning when a second (or later) NEAR/FOLLOWEDBY is chained directly after
     * the first with no wrapping parentheses; the chain is treated as left-associative nesting.
     */
    private void warnChainedProximityOperator() {
        Token next = tokens.get(currentTokenIndex);
        String keyword = (next instanceof Token.Near) ? LexiconOperatorKeyword.NEAR : LexiconOperatorKeyword.FOLLOWEDBY;
        warnings.add(
                "Chained " + keyword + " operators without explicit parentheses were used in term: '"
                        + originalTerm + "' (found a second " + keyword + " chained directly after the first)."
                        + " This still compiles, for backward compatibility with existing lexicon terms, and is"
                        + " treated as left-associative nesting — equivalent to wrapping the earlier operator(s)"
                        + " in parentheses explicitly, e.g. 'A " + keyword + "{n} B " + keyword + "{m} C' is treated"
                        + " as '(A " + keyword + "{n} B) " + keyword + "{m} C'. New terms should use explicit"
                        + " parentheses instead, both for clarity and because this implicit form may be rejected"
                        + " for newly-created terms in a future version.");
    }

    /**
     * Error for a bare {@link Token.Not} where an OPERATOR was expected — between two
     * already-parsed expressions, e.g. {@code "A NOT B"} or {@code "(A NOT B)"}. {@code NOT}
     * is only valid immediately before a parenthesised group that is an operand of {@code AND}.
     * Reached from {@link #expectClosingParen()} and the end-of-input check in {@link #parse}.
     *
     * <p>A {@code NOT} that instead starts a fresh atom and is not followed by {@code (} is
     * ordinary literal text ({@link #parseWordOrPhrase()}), so {@code (NOT LAUNCHING)} compiles
     * as the phrase "NOT LAUNCHING".
     */
    private TranslationException standaloneNotOperatorError() {
        return new TranslationException(
                "Standalone NOT is not supported as an operator in term: '" + originalTerm + "'."
                        + " NOT is only valid immediately followed by a parenthesised group, and that"
                        + " group is only valid as a later operand of AND — written as 'X AND NOT (Y)'"
                        + " or 'X AND (NOT (Y))'. A bare NOT sitting between two separate expressions"
                        + " (rather than as a word inside one of them) has no defined meaning here."
                        + " To use \"NOT\" as literal text between two expressions, combine it with an"
                        + " explicit operator (e.g. 'X OR NOT Y') or wrap it into one side's own phrase.");
    }

    /**
     * Error for a bare {@code NOT} directly before {@code NEAR}/{@code FOLLOWEDBY}, e.g.
     * {@code "apple NOT NEAR{10} banana"}. {@code NOT} is never a proximity operand or modifier.
     */
    private TranslationException notBeforeProximityError() {
        return new TranslationException(
                "NOT cannot appear directly before NEAR/FOLLOWEDBY in term: '" + originalTerm + "'."
                        + " NOT is only supported immediately followed by a parenthesised group — written"
                        + " as 'X AND NOT (Y)' or 'X AND (NOT (Y))' — never as a standalone modifier"
                        + " combined directly with a proximity operator.");
    }

    private Ast parseAtom() {
        if (currentTokenIndex >= tokens.size()) {
            throw unexpectedTokenError("expected a term");
        }
        Token currentToken = tokens.get(currentTokenIndex);
        if (currentToken instanceof Token.LParen) {
            return parseParenGroup();
        }
        if (currentToken instanceof Token.Not && peekNextIs(Token.LParen.class)) {
            // NOT immediately followed by '(' — the NOT-group atom; see class
            // Javadoc "Standalone NOT" section. Folded into an Ast.AndNot by
            // parseAnd()/foldNotOperands, or rejected there if misplaced.
            return parseNotGroup();
        }
        if (currentToken instanceof Token.QuotedPhrase(String text)) {
            advance();
            return new Ast.QuotedPhrase(text);
        }
        if (currentToken instanceof Token.Word || currentToken instanceof Token.Not) {
            // NOT starting a fresh atom, NOT immediately followed by '(', is literal
            // text — see standaloneNotOperatorError() Javadoc for the full reasoning.
            return parseWordOrPhrase();
        }
        throw unexpectedTokenError("expected a term, parenthesis, or quoted phrase");
    }

    /**
     * {@code notGroup := NOT '(' orExpr ')'} — produces an {@link Ast.Not} around the group's
     * content, which may be any expression (see {@link #foldNotOperands} for where it ends up).
     */
    private Ast parseNotGroup() {
        advance(); // consume NOT
        Ast operand = parseParenGroup();
        return new Ast.Not(operand);
    }

    /**
     * Greedily collects consecutive bare {@link Token.Word} tokens — plus a {@link Token.Not}
     * treated as the literal word "NOT" — into a {@link Ast.Word} (one word) or
     * {@link Ast.Phrase} (several). A {@code NOT} directly before NEAR/FOLLOWEDBY is rejected
     * ({@link #notBeforeProximityError}) rather than folded into the phrase.
     */
    private Ast parseWordOrPhrase() {
        List<String> collectedWords = new ArrayList<>();
        while (currentTokenIndex < tokens.size()) {
            Token token = tokens.get(currentTokenIndex);
            if (token instanceof Token.Word(String text)) {
                collectedWords.add(text);
            } else if (token instanceof Token.Not) {
                // A bare NOT directly followed by NEAR/FOLLOWEDBY is never literal
                // text — see notBeforeProximityError() Javadoc — regardless of
                // whether anything was already collected before it (e.g. both a bare
                // "NOT NEAR{10} banana" and "apple NOT NEAR{10} banana" are rejected
                // here, not silently folded into a literal "apple NOT" phrase).
                if (nextIsNearOrFollowedBy()) {
                    throw notBeforeProximityError();
                }
                collectedWords.add(LexiconOperatorKeyword.NOT);
            } else {
                break;
            }
            currentTokenIndex++;
        }
        return collectedWords.size() == 1 ? new Ast.Word(collectedWords.getFirst()) : new Ast.Phrase(collectedWords);
    }

    /**
     * True when the token after the CURRENT (unconsumed) token is NEAR/FOLLOWEDBY.
     */
    private boolean nextIsNearOrFollowedBy() {
        int nextIndex = currentTokenIndex + 1;
        if (nextIndex >= tokens.size()) {
            return false;
        }
        Token next = tokens.get(nextIndex);
        return next instanceof Token.Near || next instanceof Token.FollowedBy;
    }

    /**
     * {@code atom := '(' orExpr ')'} — whatever is inside is resolved by ordinary recursion
     * through {@link #parseOr}; empty parentheses are rejected.
     */
    private Ast parseParenGroup() {
        advance(); // consume '('
        if (peekIs(Token.RParen.class)) {
            throw new TranslationException("Empty parentheses '()' are not allowed in term: '" + originalTerm + "'.");
        }
        Ast innerExpression = parseOr();
        expectClosingParen();
        return innerExpression;
    }

    // ── Look-ahead helpers ────────────────────────────────────────────────────

    private boolean peekIsNearOrFollowedBy() {
        if (currentTokenIndex >= tokens.size()) return false;
        Token token = tokens.get(currentTokenIndex);
        return token instanceof Token.Near || token instanceof Token.FollowedBy;
    }

    private boolean peekIs(Class<? extends Token> tokenType) {
        return currentTokenIndex < tokens.size() && tokenType.isInstance(tokens.get(currentTokenIndex));
    }

    /**
     * True when the token after the CURRENT (unconsumed) token is a {@code tokenType}.
     */
    private boolean peekNextIs(Class<? extends Token> tokenType) {
        int nextIndex = currentTokenIndex + 1;
        return nextIndex < tokens.size() && tokenType.isInstance(tokens.get(nextIndex));
    }

    private Token advance() {
        return tokens.get(currentTokenIndex++);
    }

    private void expectClosingParen() {
        if (currentTokenIndex >= tokens.size() || !(tokens.get(currentTokenIndex) instanceof Token.RParen)) {
            if (peekIs(Token.Not.class)) {
                // A bare NOT sitting between the group just parsed and its closing ')' —
                // e.g. "(A NOT B)" — see standaloneNotOperatorError() Javadoc.
                throw standaloneNotOperatorError();
            }
            throw new TranslationException(
                    "Expected closing ')' in term: '" + originalTerm + "'.");
        }
        currentTokenIndex++;
    }

    private TranslationException unexpectedTokenError(String context) {
        return new TranslationException(
                "Could not parse term: '" + originalTerm + "' (" + context + ")."
                        + " Check for unbalanced parentheses or a malformed operator.");
    }

    /**
     * Best-effort human-readable rendering of an AST node for error messages.
     */
    private String describe(Ast astNode) {
        return switch (astNode) {
            case Ast.Word word -> word.text();
            case Ast.Phrase phrase -> String.join(" ", phrase.words());
            case Ast.QuotedPhrase quotedPhrase -> "\"" + quotedPhrase.text() + "\"";
            default -> "(...)";
        };
    }
}
