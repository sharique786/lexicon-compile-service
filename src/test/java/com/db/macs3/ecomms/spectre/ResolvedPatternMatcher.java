package com.db.macs3.ecomms.spectre;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference implementation of how a downstream consumer (Lexicon Scan
 * Engine / Lexicon Scanner Service) can evaluate one lexicon-compile-service
 * {@code resolvedPatterns} string against real message text — i.e. how
 * NEAR/FOLLOWEDBY/AND NOT, no longer compiled into Hyperscan regex by this
 * service, get re-applied downstream using plain {@code java.util.regex}.
 *
 * <p><b>Not consumed by lexicon-compile-service itself</b> — this class is a
 * worked example/blueprint only, kept in the test tree, so its intent and
 * limitations can be documented plainly rather than hidden inside a
 * "for reference" comment on a class this service actually depends on. See
 * {@code TermCompilationResult#resolvedPatterns()} class Javadoc for the
 * exact string contract parsed here — e.g.
 * {@code "bash FOLLOWEDBY{30} (?:fuck|fck)"} or
 * {@code "insider AND NOT ((?:wordA...) FOLLOWEDBY{2} (?:wordH...) FOLLOWEDBY{2} (?:wordO...))"}.
 *
 * <p><b>Usage</b>
 * <pre>{@code
 * Node tree = ResolvedPatternMatcher.parse(result.resolvedPatterns(), result.hyperscanFlags());
 * boolean matched = ResolvedPatternMatcher.matches(tree, messageText);
 * }</pre>
 *
 * <p><b>The evaluation model — word-index spans, not single word indices</b>
 * <p>{@link TokenProximityMatcher} (this class's own seed) assumed every
 * leaf was exactly one word, matched via {@code Pattern.matches()} against
 * one cleaned token. Real {@code regexPattern}/{@code exclusionRegex}
 * leaves are frequently multi-word phrases (e.g. {@code "the rate"},
 * {@code "fed rate move"}), so this class instead finds every occurrence of
 * a leaf pattern directly in the message text via {@code Matcher.find()},
 * then maps each match's character span onto the WORD indices it overlaps
 * (words are simply {@code \S+} runs, matching how this project's own
 * {@code MultiLanguagePatternBuilder}/{@code Tokenizer} treat "words" for
 * Natural-Language proximity). The gap between two occurrences is the
 * number of whole words strictly between them — the same definition
 * {@code NEAR{n}}/{@code FOLLOWEDBY{n}} use in the lexicon operator
 * language itself.
 *
 * <p><b>A chain of NEAR/FOLLOWEDBY is a genuine constraint-satisfaction
 * search, not a left-to-right greedy match</b>
 * <p>A LEFT-nested chain — e.g. {@code (A FOLLOWEDBY{4} B) FOLLOWEDBY{4} C},
 * whether the author wrote it that way explicitly or relied on
 * {@code ExpressionParser}'s implicit left-associative chaining — renders as
 * flat text, {@code "A FOLLOWEDBY{4} B FOLLOWEDBY{4} C"}, deliberately: the
 * string doesn't need to encode which pairs were originally nested, only
 * that every CONSECUTIVE pair in the chain must satisfy its own operator and
 * distance against ONE common, simultaneously-chosen occurrence of each
 * element. {@link #matchesChain} finds this via straightforward backtracking
 * over each element's candidate occurrences (spans, plural — see below) —
 * correct and easy to follow, intentionally not optimised, appropriate for a
 * reference implementation.
 *
 * <p><b>RIGHT-nested proximity renders as an explicit, parenthesised GROUP —
 * parsed and evaluated recursively, not flattened</b>
 * <p>Unlike left-nesting, a NEAR/FOLLOWEDBY node that is the RIGHT operand of
 * another one (only reachable via an author explicitly grouping it, e.g.
 * {@code "(A) NEAR{5} ((B) NEAR{5} (C))"}) is rendered with its OWN wrapping
 * parentheses — {@code "A NEAR{5} (B NEAR{5} C)"} — precisely because a flat
 * rendering would be indistinguishable from (and silently misread as) the
 * left-nested chain above. {@link #parseChainElement} recognises such a
 * segment (fully parenthesised AND containing a further top-level
 * NEAR/FOLLOWEDBY keyword inside) as a {@link ChainElement.Group} and
 * recurses back into {@link #parseChain} for its contents, rather than
 * naively compiling the whole segment — parentheses AND ALL — as one leaf
 * regex. {@link #enumerateChainSpans} evaluates a Group by first resolving
 * ITS OWN internal chain into every valid overall occurrence SPAN (not a
 * single word index — a satisfied group can cover several words), then
 * offers each such span to the ENCLOSING chain as one candidate occurrence
 * for that element, with the enclosing chain's own gap arithmetic measuring
 * from whichever edge of the span is nearest — the same thing a real
 * gap-embedded proximity pattern would do, and materially different from
 * (more permissive than) measuring against one arbitrarily-chosen leaf
 * inside the group.
 *
 * <p><b>Known simplification of the parser, documented rather than hidden</b>
 * <p>{@link #parse} recognises {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/
 * {@code AND NOT (} boundaries by literal text matching at each string's
 * TOP paren-nesting level. This is safe for every string this service
 * actually produces — a leaf's own regex text never happens to contain the
 * literal substring {@code " NEAR{<digits>} "} etc., since Natural Language
 * lexicon terms only ever produce those exact keywords via the reserved,
 * exact-case {@code NEAR{n}}/{@code FOLLOWEDBY{n}}/{@code AND NOT} syntax,
 * which this service's own tokenizer already consumes as an operator
 * rather than ever emitting as literal leaf text — but this is not a
 * hardened, general-purpose PCRE parser.
 */
public final class ResolvedPatternMatcher {

    private ResolvedPatternMatcher() {
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Parses one {@code resolvedPatterns} string into an evaluable tree.
     *
     * @param resolvedPatterns the exact string from {@code TermCompilationResult#resolvedPatterns()}
     *                         (or {@code TranslationResult.Success#resolvedPattern()})
     * @param hyperscanFlags   the term's {@code hyperscanFlags} bitmask — used to decide whether
     *                         leaf patterns need {@code Pattern.UNICODE_CHARACTER_CLASS} (mirrors
     *                         {@code HS_FLAG_UTF8}, bit 32) in addition to the case-insensitivity
     *                         every leaf always needs
     */
    public static Node parse(String resolvedPatterns, int hyperscanFlags) {
        int javaFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if ((hyperscanFlags & 32) != 0) { // HS_FLAG_UTF8
            javaFlags |= Pattern.UNICODE_CHARACTER_CLASS;
        }
        return parseAndNot(resolvedPatterns.trim(), javaFlags);
    }

    /**
     * Evaluates a parsed tree against one message — {@code true} iff the
     * whole term (proximity structure, and AND NOT's required-present /
     * excluded-absent condition) is satisfied somewhere in {@code messageText}.
     */
    public static boolean matches(Node node, String messageText) {
        return switch (node) {
            case Node.Chain chain -> matchesChain(chain, messageText);
            case Node.AndNot andNot ->
                    matches(andNot.required(), messageText) && !matches(andNot.excluded(), messageText);
        };
    }

    // ── Tree shape ────────────────────────────────────────────────────────

    /**
     * A parsed {@code resolvedPatterns} tree. Exactly two shapes: a
     * (possibly length-1, i.e. no proximity at all) chain of elements
     * connected by NEAR/FOLLOWEDBY, or one AND NOT wrapping a required chain
     * and an excluded chain (which — per this service's guarantee that AND
     * NOT is never nested — is always itself a plain {@code Chain}, never a
     * further {@code AndNot}).
     */
    public sealed interface Node {

        /**
         * @param elements  one entry per chain element, in left-to-right term order — either a
         *                  plain leaf pattern, or (see {@link ChainElement.Group}) a whole
         *                  further nested proximity chain the term author explicitly grouped
         *                  as ONE operand of this chain (e.g. the right-hand side of
         *                  {@code "(A) NEAR{5} ((B) NEAR{5} (C))"})
         * @param operators {@code "NEAR"} or {@code "FOLLOWEDBY"} between consecutive elements —
         *                  {@code operators.size() == elements.size() - 1}
         * @param distances the raw, un-clamped distance for each operator — same size as {@code operators}
         */
        record Chain(List<ChainElement> elements, List<String> operators, List<Integer> distances) implements Node {
        }

        record AndNot(Node required, Node excluded) implements Node {
        }
    }

    /**
     * One element of a {@link Node.Chain} — either an ordinary leaf pattern,
     * or a whole nested proximity chain the author explicitly grouped as ONE
     * operand of the enclosing chain (see {@code PatternDecomposer}'s
     * right-side-wraps-in-parentheses rule in the main service). Distinguishing
     * these two shapes is what lets this reference matcher recover the
     * author's ACTUAL grouping (needed for gap arithmetic between a nested
     * group and its neighbour to be measured against the group's own
     * boundary, not just one arbitrarily-chosen leaf inside it) instead of
     * re-flattening every chain into one undifferentiated sequence.
     */
    public sealed interface ChainElement {
        record Leaf(Pattern pattern) implements ChainElement {
        }

        record Group(Node.Chain nested) implements ChainElement {
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    private static final String AND_NOT_MARKER = " AND NOT (";
    private static final Pattern PROXIMITY_KEYWORD = Pattern.compile(" (NEAR|FOLLOWEDBY)\\{(\\d+)\\} ");

    private static Node parseAndNot(String text, int javaFlags) {
        int markerAt = findTopLevel(text, AND_NOT_MARKER);
        if (markerAt < 0) {
            return parseChain(text, javaFlags);
        }
        String requiredText = text.substring(0, markerAt);
        int openParenAt = markerAt + AND_NOT_MARKER.length() - 1;
        int closeParenAt = matchingCloseParen(text, openParenAt);
        String excludedText = text.substring(openParenAt + 1, closeParenAt);
        return new Node.AndNot(parseChain(requiredText, javaFlags), parseChain(excludedText, javaFlags));
    }

    private static Node.Chain parseChain(String text, int javaFlags) {
        List<ChainElement> elements = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        List<Integer> distances = new ArrayList<>();

        int depth = 0;
        int segmentStart = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0) {
                Matcher m = PROXIMITY_KEYWORD.matcher(text);
                m.region(i, text.length());
                if (m.lookingAt()) {
                    elements.add(parseChainElement(text.substring(segmentStart, i), javaFlags));
                    operators.add(m.group(1));
                    distances.add(Integer.parseInt(m.group(2)));
                    i = m.end();
                    segmentStart = i;
                    continue;
                }
            }
            i++;
        }
        elements.add(parseChainElement(text.substring(segmentStart), javaFlags));

        return new Node.Chain(elements, operators, distances);
    }

    /**
     * One chain segment is an ordinary leaf pattern UNLESS it is fully
     * wrapped in one matching pair of parentheses AND that wrapping's own
     * content contains a further top-level NEAR/FOLLOWEDBY keyword — in which
     * case it is the literal rendering of an explicitly nested proximity
     * GROUP (see {@code PatternDecomposer}'s right-side-wraps-in-parentheses
     * rule), recovered here by recursing back into {@link #parseChain} on
     * the unwrapped content. An ordinary leaf's own regex text frequently
     * ALSO happens to be fully parenthesised (e.g. {@code "(?:price|spread)"}
     * is a non-capturing group spanning the whole segment) — {@link #hasTopLevelKeyword}
     * is what tells the two apart, checked BEFORE recursing into
     * {@link #parseChain} (rather than recursing first and checking the
     * result), since the unwrapped content of an ordinary leaf like
     * {@code "(?:price|spread)"} — {@code "?:price|spread"} — is not valid
     * regex on its own and must never be hesitated over as if it might be one.
     */
    private static ChainElement parseChainElement(String segment, int javaFlags) {
        if (!segment.isEmpty() && segment.charAt(0) == '('
                && matchingCloseParen(segment, 0) == segment.length() - 1) {
            String inner = segment.substring(1, segment.length() - 1);
            if (hasTopLevelKeyword(inner)) {
                return new ChainElement.Group(parseChain(inner, javaFlags));
            }
        }
        return new ChainElement.Leaf(Pattern.compile(segment, javaFlags));
    }

    /**
     * True when {@code text} contains at least one TOP-level (paren-depth 0)
     * occurrence of {@link #PROXIMITY_KEYWORD} — a structural check only,
     * compiling nothing, so probing whether a segment is a nested proximity
     * Group never risks trying to compile an invalid regex fragment (e.g. an
     * ordinary leaf's unwrapped content, which is frequently not valid regex
     * on its own — see {@link #parseChainElement}).
     */
    private static boolean hasTopLevelKeyword(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0) {
                Matcher m = PROXIMITY_KEYWORD.matcher(text);
                m.region(i, text.length());
                if (m.lookingAt()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The index of the first TOP-level (paren-depth 0) occurrence of
     * {@code marker} in {@code text}, or -1 if none — used to find the
     * (at most one) {@code " AND NOT ("} boundary without being confused by
     * a leaf's own parenthesised regex content.
     */
    private static int findTopLevel(String text, String marker) {
        int depth = 0;
        for (int i = 0; i <= text.length() - marker.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && text.startsWith(marker, i)) {
                return i;
            }
        }
        return -1;
    }

    private static int matchingCloseParen(String text, int openParenAt) {
        int depth = 0;
        for (int i = openParenAt; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("Unbalanced parentheses in resolvedPatterns text: " + text);
    }

    // ── Evaluation ────────────────────────────────────────────────────────

    private static boolean matchesChain(Node.Chain chain, String messageText) {
        return !enumerateChainSpans(chain, messageText, wordSpans(messageText)).isEmpty();
    }

    /**
     * Every valid, self-consistent overall {@code {minWordIndex, maxWordIndex}}
     * span for one full assignment of {@code chain}'s own elements — recursing
     * into any nested {@link ChainElement.Group} FIRST, so a group is only
     * ever offered to its enclosing chain as a whole, already-internally-valid
     * occurrence. Used both to answer "does this chain match at all" (a
     * non-empty result, from {@link #matchesChain}) and, when this chain is
     * itself one nested group inside an OUTER chain, to give that outer chain
     * a candidate occurrence span for each way this whole group could be
     * satisfied — the outer chain's own gap arithmetic then measures distance
     * to whichever edge of the span is nearest, exactly like a real
     * gap-embedded proximity pattern would, rather than arbitrarily picking
     * one leaf inside the group.
     */
    private static List<int[]> enumerateChainSpans(Node.Chain chain, String messageText, List<int[]> words) {
        List<List<int[]>> candidatesPerElement = new ArrayList<>(chain.elements().size());
        for (ChainElement element : chain.elements()) {
            List<int[]> candidates = switch (element) {
                case ChainElement.Leaf leaf -> matchWordIndices(leaf.pattern(), messageText, words)
                        .stream().map(index -> new int[] {index, index}).toList();
                case ChainElement.Group group -> enumerateChainSpans(group.nested(), messageText, words);
            };
            if (candidates.isEmpty()) {
                return List.of(); // this element never appears/is never satisfied — the whole chain cannot match
            }
            candidatesPerElement.add(candidates);
        }
        List<int[]> results = new ArrayList<>();
        enumerateAssignments(candidatesPerElement, chain.operators(), chain.distances(),
                0, null, new ArrayList<>(), results);
        return results;
    }

    /**
     * Every whole-word occurrence of {@code leaf} in {@code messageText},
     * represented by the END word index it occupies (for a multi-word match,
     * the LAST word it spans) — proximity gap counting (see class Javadoc)
     * only cares about the boundary nearest the OTHER leaf in the pair, and
     * using the end index uniformly keeps the gap arithmetic in
     * {@link #enumerateAssignments} simple and symmetric for both NEAR directions.
     */
    private static List<Integer> matchWordIndices(Pattern leaf, String messageText, List<int[]> words) {
        List<Integer> indices = new ArrayList<>();
        Matcher m = leaf.matcher(messageText);
        while (m.find()) {
            int endWordIndex = wordIndexAtOrBefore(words, m.end());
            if (endWordIndex >= 0) {
                indices.add(endWordIndex);
            }
            if (m.end() == m.start()) {
                break; // guard against a zero-width match looping forever
            }
        }
        return indices;
    }

    private static List<int[]> wordSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = Pattern.compile("\\S+").matcher(text);
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
        }
        return spans;
    }

    private static int wordIndexAtOrBefore(List<int[]> words, int charOffset) {
        for (int i = words.size() - 1; i >= 0; i--) {
            if (words.get(i)[0] < charOffset) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Backtracking search: choose one candidate span for each element, in
     * order, such that every consecutive pair satisfies its operator's
     * constraint against the PREVIOUSLY chosen element's span — generalizes
     * the original single-word-index version to spans (needed once an
     * element can itself be a nested {@link ChainElement.Group} covering
     * several words) — see {@link #gapBetween}. Every complete, valid
     * assignment's overall {@code {min, max}} span (across ALL chosen
     * elements, not just the two ends) is appended to {@code resultsOut} —
     * a chain can be satisfiable in more than one way, and (when this chain
     * is itself a nested group) an enclosing chain may need a DIFFERENT one
     * of those ways depending on where its own neighbour sits.
     */
    private static void enumerateAssignments(List<List<int[]>> candidatesPerElement, List<String> operators,
                                             List<Integer> distances, int elementIndex, int[] previousSpan,
                                             List<int[]> chosenSoFar, List<int[]> resultsOut) {
        if (elementIndex == candidatesPerElement.size()) {
            int min = chosenSoFar.stream().mapToInt(span -> span[0]).min().orElseThrow();
            int max = chosenSoFar.stream().mapToInt(span -> span[1]).max().orElseThrow();
            resultsOut.add(new int[] {min, max});
            return;
        }
        for (int[] candidate : candidatesPerElement.get(elementIndex)) {
            boolean ok;
            if (elementIndex == 0) {
                ok = true;
            } else {
                Integer gap = gapBetween(previousSpan, candidate, operators.get(elementIndex - 1));
                ok = gap != null && gap >= 0 && gap <= distances.get(elementIndex - 1);
            }
            if (ok) {
                chosenSoFar.add(candidate);
                enumerateAssignments(candidatesPerElement, operators, distances,
                        elementIndex + 1, candidate, chosenSoFar, resultsOut);
                chosenSoFar.remove(chosenSoFar.size() - 1);
            }
        }
    }

    /**
     * The count of whole words strictly between two occurrence spans,
     * matching this project's own {@code NEAR{n}}/{@code FOLLOWEDBY{n}}
     * distance definition exactly — or {@code null} when the spans overlap,
     * or when {@code operator} is {@code FOLLOWEDBY} (directional: {@code a}
     * must come strictly before {@code b}) and they are not in that order.
     * {@code NEAR} allows either order. A plain leaf's span is degenerate
     * ({@code {index, index}}); this generalizes cleanly to a
     * {@link ChainElement.Group}'s multi-word span too, measuring from
     * whichever edge is actually nearest — exactly like a real gap-embedded
     * proximity pattern would, rather than an arbitrarily-chosen leaf inside it.
     */
    private static Integer gapBetween(int[] a, int[] b, String operator) {
        if (a[1] < b[0]) {
            return b[0] - a[1] - 1; // a entirely before b
        }
        if (!"FOLLOWEDBY".equals(operator) && b[1] < a[0]) {
            return a[0] - b[1] - 1; // b entirely before a -- only valid for bidirectional NEAR
        }
        return null; // overlapping spans, or FOLLOWEDBY in the wrong direction
    }
}
