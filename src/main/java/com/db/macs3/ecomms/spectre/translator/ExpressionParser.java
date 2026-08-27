package com.db.macs3.ecomms.spectre.translator;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser: {@code List<Token>} → {@link Ast}.
 *
 * <p><b>Grammar (highest precedence last, i.e. tightest-binding first)</b>
 * <pre>
 * orExpr        := andNotExpr ( OR andNotExpr )*
 * andNotExpr    := andExpr ( AND_NOT requiredGroup )*             [glued "AND NOT (...)" spelling]
 * andExpr       := proximityExpr ( AND proximityExpr )*           [folds any NOT-group operand into AndNot]
 * proximityExpr := atom ( (NEAR | FOLLOWEDBY) atom )*              [left-associative]
 * atom          := '(' orExpr ')' | notGroup | QUOTED_PHRASE | wordOrPhrase
 * notGroup      := NOT requiredGroup                              [only valid as an andExpr operand — see below]
 * requiredGroup := '(' orExpr ')'
 * wordOrPhrase  := WORD+                                          [greedy]
 * </pre>
 *
 * <p><b>Standalone NOT — always a group, always paired with AND</b>
 * <p>{@code NOT} is never an independent "but not" operator and never
 * applies to a bare word/phrase or to a proximity expression directly — it
 * is only ever valid immediately followed by a parenthesised group, and
 * that group is only ever valid as a LATER operand of an {@code AND} that
 * also has at least one other, non-{@code NOT} operand:
 * <pre>
 *   bond AND (NOT (james bond))                 -- valid: NOT-group as an AND operand
 *   apple AND (NOT (apple NEAR{10} banana))     -- valid: NOT wraps an arbitrary sub-expression
 *   apple AND NOT (banana)                      -- valid: the older "glued" spelling, now requiring parens too
 *   NOT (james bond)                            -- REJECTED: no preceding required expression
 *   apple NOT NEAR{10} banana                   -- REJECTED: NOT directly before a proximity operator
 *   apple AND NOT NEAR{10} banana                -- REJECTED: NOT not immediately followed by '('
 * </pre>
 * Both spellings ({@code X AND (NOT (Y))} and {@code X AND NOT (Y)}) parse to
 * the exact same {@link Ast.AndNot} shape — see {@link #parseAtom} (which
 * recognises {@code NOT '('} as a {@code notGroup} atom, reachable anywhere
 * an atom is) and {@link #parseAnd} (which folds every {@link Ast.Not}
 * operand it collected into one {@link Ast.AndNot}, or rejects the whole
 * level if that would leave no required operand). A {@link Ast.Not} that
 * survives unfolded — because it was never a direct operand of an
 * {@code AND} at all (used standalone, as an {@code OR} alternative, or as
 * a {@code NEAR}/{@code FOLLOWEDBY} operand) — is a parse-time error, not
 * something that reaches {@link PatternCodeGenerator}.
 *
 * <p>Because every level of this grammar recurses into {@code atom}, and
 * {@code atom}'s parenthesised form recurses straight back into
 * {@code orExpr}, parentheses are resolved at whatever depth they actually
 * appear at — there is no separate "strip the outer parens first" pass to
 * get subtly wrong for multiply-nested or redundant wrapping. A term like
 * {@code (((me) OR (cking)))} is handled by ordinary recursion: the outer
 * two parens are pure grouping around a single atom, so each is consumed by
 * exactly one non-branching pass through {@code atom} → {@code orExpr} →
 * ... → {@code atom} before the actual {@code (me) OR (cking)} content is reached.
 *
 * <p><b>Unwrapped multi-word phrases are accepted, not rejected</b>
 * <p>{@code wordOrPhrase} greedily consumes every consecutive {@code WORD}
 * token with no operator between them into one {@link Ast.Phrase} (or a
 * single {@link Ast.Word} if there is only one). This means a lexicon term
 * author who writes {@code bomb this place OR blow this place up} without
 * wrapping the phrases in parentheses still gets a working term — "bomb
 * this place" is treated as one literal phrase automatically, exactly as if
 * it had been written {@code (bomb this place)}. Because the greedy
 * collection stops the moment it sees a token that is NOT a bare word (an
 * operator, a parenthesis, end of input), operators like {@code OR} still
 * correctly separate one phrase from the next.
 *
 * <p><b>Chained NEAR/FOLLOWEDBY: warned, not rejected</b>
 * <p>{@code A FOLLOWEDBY{5} B FOLLOWEDBY{6} C} chains two proximity operators
 * at the same level with no explicit parentheses. This is now ACCEPTED —
 * parsed as if the author had written {@code (A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C}
 * (left-associative nesting) — with a warning recorded rather than an
 * exception thrown. This service has no persisted state and therefore no way
 * to distinguish a brand-new term from one that has been in the lexicon for
 * years; existing terms using this implicit chained form must keep compiling
 * successfully. The warning exists so a CALLER that DOES have that context —
 * a lexicon management UI, for instance — can apply its own stricter policy
 * (e.g. blocking the implicit form for newly-authored terms) using the
 * warning as the signal, without this service itself needing to make that
 * call. See {@link #warnChainedProximityOperator}. Because the resulting AST
 * shape is identical to explicit nesting, this can still trigger
 * {@code PatternComplexityAnalyzer}'s rejection (or now, decomposition — see
 * {@code PatternDecomposer}) exactly as explicit nesting would.
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
        return new Ast.Or(alternatives);
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
     * Rejects {@code ast} if it is a bare {@link Ast.Not} that {@link #foldNotOperands}
     * deferred — meaning it never found an enclosing {@code AND} operand slot to
     * fold into, and is instead about to be used somewhere {@code NOT} is not
     * supported (standalone, an {@code OR} alternative, a {@code NEAR}/{@code FOLLOWEDBY}
     * operand, or the required side of an {@code AND NOT}). See class Javadoc
     * "Standalone NOT" section.
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
     * The excluded side of the "glued" {@code AND NOT} spelling must always
     * be an explicit parenthesised group — never a bare word/phrase and
     * never a proximity operator sitting directly after {@code NOT} with no
     * parentheses. This is what rejects {@code apple AND NOT NEAR{10} banana}
     * (with a specific, actionable message) instead of falling through to
     * {@link #parseAnd}'s more general — and, for this position, wrong —
     * grammar. See class Javadoc "Standalone NOT" section.
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
     * <p>Caps the operand count at {@link ParseContext#MAX_AND_OPERANDS} —
     * {@link PatternCodeGenerator generateAnd} expresses "all present, any
     * order, unbounded distance" by enumerating every ordering of the
     * operands (N! permutations), so operand count directly controls
     * pattern size; beyond the ceiling the resulting pattern reliably fails
     * Hyperscan compilation with "Pattern Too Long", the same class of
     * failure checkForChainedProximityOperator prevents for
     * chained NEAR/FOLLOWEDBY.
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
     * Folds every {@link Ast.Not} operand collected by one {@code AND} level
     * into a single {@link Ast.AndNot} — the {@code NOT}-group equivalent of
     * {@link #parseAndNot}'s own {@code AND_NOT}-token loop, so that
     * {@code "bond AND (NOT (james bond))"} produces EXACTLY the same
     * {@code Ast.AndNot(required=bond, excluded=[james bond])} shape as the
     * already-supported {@code "bond AND NOT (james bond)"} spelling — see
     * class Javadoc "Standalone NOT" section. Multiple {@code NOT}-group
     * operands at the same level (e.g. {@code "a AND (NOT (b)) AND (NOT (c))"})
     * combine into one {@link Ast.AndNot} with both excluded, exactly like
     * chained {@code "a AND NOT b AND NOT c"} already does.
     *
     * <p><b>Deferral for a single, redundantly-wrapped NOT-group</b>
     * <p>When {@code operands} has EXACTLY one entry and it is a bare
     * {@link Ast.Not} (nothing else at this level to be "required"), this
     * does NOT reject it outright — {@code "bond AND (NOT (james bond))"}
     * puts the NOT-group inside its OWN extra pair of parentheses, so the
     * {@code parseAnd()} call for THAT inner group sees only the one
     * {@code Not} operand, with "bond" only visible to the OUTER {@code AND}.
     * The {@code Not} is returned unchanged here, to be folded once it
     * bubbles back up to that outer level (parentheses are otherwise
     * transparent everywhere else in this grammar — see class Javadoc). If it
     * never finds such an outer {@code AND} — used standalone, as an
     * {@code OR} alternative, or as a {@code NEAR}/{@code FOLLOWEDBY} operand
     * — {@link #rejectBareNot} catches it at that point instead.
     *
     * @throws TranslationException if MULTIPLE operands were collected and
     * every one of them is a {@link Ast.Not} — {@code NOT} always needs a
     * preceding, non-{@code NOT} required operand at the same level once
     * there is more than one operand to reconcile
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
     * {@code proximityExpr := notLevel ( (NEAR | FOLLOWEDBY) notLevel )*}
     *
     * <p>Grammatically {@code *} (zero or more), so chained proximity
     * operators at the same level parse successfully — see class Javadoc's
     * "Chained NEAR/FOLLOWEDBY" section for why this is now accepted (with a
     * warning) rather than rejected. Each additional chained operator wraps
     * the ACCUMULATED result so far as its left operand — i.e.
     * {@code A FOLLOWEDBY{5} B FOLLOWEDBY{6} C} builds exactly the same AST
     * shape as the explicitly-nested {@code (A FOLLOWEDBY{5} B) FOLLOWEDBY{6} C}.
     * A term written with explicit parentheses is unaffected either way: the
     * inner parenthesised group is still parsed at its own, separate level,
     * so it never triggers the chained-operator warning in the first place.
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
        return (proximityToken instanceof Token.Near(int distance))
                ? new Ast.Near(leftOperand, rightOperand, distance)
                : new Ast.FollowedBy(leftOperand, rightOperand, ((Token.FollowedBy) proximityToken).distance());
    }

    /**
     * Records a non-fatal warning when a second (or subsequent) NEAR/FOLLOWEDBY
     * is found chained directly after the first, with no wrapping parentheses.
     * See class Javadoc's "Chained NEAR/FOLLOWEDBY" section for why this is a
     * warning rather than the {@link TranslationException} an earlier revision
     * threw here — existing lexicon terms already use this implicit form and
     * must keep compiling.
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
     * There is no independent {@code NOT} operator — {@code NOT} is only
     * ever valid immediately after {@code AND}, forming a single
     * {@link Token.AndNot} token at the LEXICAL level (see {@link Tokenizer}).
     *
     * <p>A bare {@link Token.Not} reaching a point in the grammar where an
     * OPERATOR was expected (continuing an {@code OR}/{@code AND}/
     * {@code AND NOT}/{@code NEAR}/{@code FOLLOWEDBY} chain, or closing a
     * parenthesised group, or ending the whole term) means the author wrote
     * something shaped like {@code "A NOT B"} — {@code NOT} sitting between
     * two ALREADY-PARSED expressions, mimicking a standalone "but not"
     * operator this grammar (and Hyperscan, which has no negative lookaround)
     * does not support. That specific shape is rejected here with an
     * actionable error — see the two call sites: {@link #expectClosingParen()}
     * and {@link #parse}'s end-of-input check.
     *
     * <p>A bare {@link Token.Not} reaching {@link #parseAtom()} instead — i.e.
     * at the START of a fresh atom, where a word could otherwise begin one
     * (the very first token of the term, or immediately after {@code (},
     * {@code OR}, {@code AND}, {@code AND NOT}, {@code NEAR{n}}, or
     * {@code FOLLOWEDBY{n}}) — is NOT an operator-position case at all:
     * there is no left-hand expression for it to apply to, so it cannot be
     * mimicking {@code AND NOT}. That case is treated as ordinary literal
     * text instead, folded into whatever word/phrase run follows — see
     * {@link #parseWordOrPhrase()}. This is what lets
     * {@code (NOT LAUNCHING)} or {@code (NOT TO LAUNCH THE PRODUCT)} compile
     * as the literal phrases "NOT LAUNCHING"/"NOT TO LAUNCH THE PRODUCT",
     * while {@code (disintermediate*) NOT ((LAUNCHING) OR (...))} — NOT
     * between two complete expressions — is still rejected.
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
     * Thrown when a bare {@link Token.Not} is directly followed by
     * {@code NEAR}/{@code FOLLOWEDBY} — e.g. {@code "apple NOT NEAR{10} banana"}
     * or a bare {@code "NOT NEAR{10} banana"} with nothing preceding it.
     * {@code NOT} is never a proximity operand or a modifier on one; the only
     * supported shape is {@code NOT} immediately followed by a parenthesised
     * group (see {@link #parseAtom}), never by a proximity keyword.
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
     * {@code notGroup := NOT '(' orExpr ')'} — consumes the {@code NOT}
     * keyword and the parenthesised group that must immediately follow it,
     * producing an {@link Ast.Not} wrapping whatever the group contains
     * (which may itself be an arbitrary expression, e.g. a NEAR/FOLLOWEDBY —
     * see {@code "apple AND (NOT (apple NEAR{10} banana))"} in class Javadoc).
     */
    private Ast parseNotGroup() {
        advance(); // consume NOT
        Ast operand = parseParenGroup();
        return new Ast.Not(operand);
    }

    /**
     * Greedily collects one or more consecutive bare {@link Token.Word}
     * tokens — and, starting or continuing the same run, the reserved
     * keyword {@link Token.Not} treated as its own literal text
     * ({@code "NOT"}) rather than an operator (see
     * {@link #standaloneNotOperatorError()} for why this position is
     * different from NOT appearing between two already-parsed expressions)
     * — into a single atom: a {@link Ast.Word} if there is just one, or an
     * {@link Ast.Phrase} if there are several. This is what lets a lexicon
     * term author write {@code bomb this place} — or {@code NOT LAUNCHING}
     * — without wrapping parentheses — see class Javadoc.
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
     * True when the token immediately after the CURRENT token (not yet
     * consumed) is {@code NEAR}/{@code FOLLOWEDBY} — used by
     * {@link #parseWordOrPhrase} to detect a bare {@code NOT} sitting
     * directly before a proximity operator.
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
     * Handles {@code '(' ... ')'} as a plain grammar rule — {@code atom := '(' orExpr ')'}.
     * No look-ahead or special-casing is needed here: whatever is inside the
     * parentheses, whether it is an operator expression or a bare multi-word
     * phrase, is resolved by the normal recursive descent through
     * {@link #parseOr} down to {@link #parseWordOrPhrase}.
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
     * True when the token immediately after the CURRENT (not yet consumed)
     * token is an instance of {@code tokenType} — used by {@link #parseAtom}
     * to recognise {@code NOT '('} as a {@code notGroup} atom without
     * consuming either token.
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
