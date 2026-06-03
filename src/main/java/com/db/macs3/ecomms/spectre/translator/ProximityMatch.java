package com.db.macs3.ecomms.spectre.translator;

/**
 * Carries the two sides and parameters of a proximity operator found at top level.
 *
 * @param left          left-hand expression (before the proximity operator)
 * @param right         right-hand expression (after the proximity operator)
 * @param distance      word distance (n from {@code NEAR{n}} or {@code FOLLOWEDBY{n}})
 * @param bidirectional {@code true} for NEAR (A ↔ B); {@code false} for FOLLOWEDBY (A → B only)
 */
record ProximityMatch(String left, String right, int distance, boolean bidirectional) {}
