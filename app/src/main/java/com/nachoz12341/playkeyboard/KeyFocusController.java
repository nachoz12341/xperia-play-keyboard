package com.nachoz12341.playkeyboard;

import android.inputmethodservice.Keyboard;

import java.util.List;

/**
 * Tracks which {@link Keyboard.Key} currently has D-pad focus and moves that focus
 * in response to directional input. Keyboard.Key objects sit on a non-uniform grid
 * (space/shift/enter are wider than letter keys), so focus movement is a
 * nearest-key-in-direction search over key center coordinates rather than a simple
 * row/column walk.
 *
 * Left/Right and Up/Down use different strategies, but both wrap at the edge
 * rather than clamping:
 *  - Left/Right must stay within the current row. QWERTY rows are staggered, so a
 *    key in the row above/below can sit almost directly over the current key
 *    (near-zero horizontal offset); blending in a vertical penalty is not enough
 *    to stop Left/Right from "snaking" into the next row, so candidates outside a
 *    same-row band are excluded outright instead of merely penalized. At the end
 *    of a row, focus wraps to the far edge of that same row.
 *  - Up/Down naturally stays in-column-ish already, since same-row keys share the
 *    exact same center Y and are excluded by the epsilon check; a weighted nearest
 *    search (closest row primarily, closest column as a tie-breaker) works fine.
 *    At the top/bottom row, focus wraps to the farthest row on the opposite side
 *    (nearest column within it), rather than staying put.
 */
public class KeyFocusController {

    public enum Direction {
        UP, DOWN, LEFT, RIGHT
    }

    /** Pixel tolerance so a same-row/same-column key isn't picked as its own neighbor. */
    private static final int EPSILON = 4;

    /** Weight applied to the off-axis distance when scoring a Up/Down candidate key. */
    private static final double SECONDARY_AXIS_WEIGHT = 0.25;

    /** Fraction of the current key's height used as the same-row tolerance band for Left/Right. */
    private static final double SAME_ROW_TOLERANCE_FACTOR = 0.6;

    private Keyboard keyboard;
    private Keyboard.Key focusedKey;

    /**
     * Switches to a new keyboard (e.g. on a page or shift toggle), re-picking the
     * key on the new keyboard whose position is nearest to the previous focus.
     */
    public void setKeyboard(Keyboard newKeyboard) {
        Keyboard.Key previousFocus = focusedKey;
        this.keyboard = newKeyboard;

        List<Keyboard.Key> keys = newKeyboard.getKeys();
        if (keys.isEmpty()) {
            focusedKey = null;
            return;
        }
        if (previousFocus == null) {
            focusedKey = keys.get(0);
            return;
        }

        int prevCx = centerX(previousFocus);
        int prevCy = centerY(previousFocus);

        Keyboard.Key nearest = keys.get(0);
        long bestDistSq = Long.MAX_VALUE;
        for (Keyboard.Key key : keys) {
            long dx = centerX(key) - prevCx;
            long dy = centerY(key) - prevCy;
            long distSq = dx * dx + dy * dy;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = key;
            }
        }
        focusedKey = nearest;
    }

    public void setFocusedKey(Keyboard.Key key) {
        this.focusedKey = key;
    }

    public Keyboard.Key getFocusedKey() {
        return focusedKey;
    }

    public void moveFocus(Direction direction) {
        if (keyboard == null || focusedKey == null) {
            return;
        }
        Keyboard.Key best = (direction == Direction.LEFT || direction == Direction.RIGHT)
                ? findNearestInRow(focusedKey, direction, keyboard.getKeys())
                : findNearestByRow(focusedKey, direction, keyboard.getKeys());
        if (best != null) {
            focusedKey = best;
        }
    }

    /** Left/Right: restricted to candidates within a same-row vertical band, closest wins. */
    private static Keyboard.Key findNearestInRow(
            Keyboard.Key current, Direction direction, List<Keyboard.Key> candidates) {
        int cx = centerX(current);
        int cy = centerY(current);
        double rowTolerance = current.height * SAME_ROW_TOLERANCE_FACTOR;

        Keyboard.Key best = null;
        int bestDelta = Integer.MAX_VALUE;

        // Tracks the farthest key on the opposite side of the row, in case there's no
        // candidate in the requested direction - e.g. pressing Right at the rightmost
        // key of a row. That farthest-opposite key is where wraparound lands.
        Keyboard.Key farthestOpposite = null;
        int farthestOppositeDelta = Integer.MIN_VALUE;

        for (Keyboard.Key candidate : candidates) {
            if (candidate == current) {
                continue;
            }
            if (Math.abs(centerY(candidate) - cy) > rowTolerance) {
                continue; // different row entirely - never a Left/Right candidate
            }
            int kx = centerX(candidate);
            int delta = (direction == Direction.RIGHT) ? (kx - cx) : (cx - kx);
            if (delta > EPSILON) {
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = candidate;
                }
            } else if (delta < -EPSILON) {
                int oppositeDelta = -delta;
                if (oppositeDelta > farthestOppositeDelta) {
                    farthestOppositeDelta = oppositeDelta;
                    farthestOpposite = candidate;
                }
            }
        }

        // Wrap to the far edge of the row when there's nowhere left to go this way.
        return best != null ? best : farthestOpposite;
    }

    /** Up/Down: weighted nearest-in-direction search (closest row, then closest column). */
    private static Keyboard.Key findNearestByRow(
            Keyboard.Key current, Direction direction, List<Keyboard.Key> candidates) {
        int cx = centerX(current);
        int cy = centerY(current);

        Keyboard.Key best = null;
        double bestScore = Double.MAX_VALUE;

        for (Keyboard.Key candidate : candidates) {
            if (candidate == current) {
                continue;
            }
            double score = scoreCandidate(cx, cy, centerX(candidate), centerY(candidate), direction);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        // No row further in this direction - wrap to the farthest row on the
        // opposite side (e.g. Up at the top row wraps to the bottom row).
        return best != null ? best : findWrapRowCandidate(current, direction, candidates);
    }

    private static Keyboard.Key findWrapRowCandidate(
            Keyboard.Key current, Direction direction, List<Keyboard.Key> candidates) {
        int cx = centerX(current);
        int cy = centerY(current);

        // First pass: find how far away the most extreme row on the opposite side is.
        int extremeOppositeDelta = Integer.MIN_VALUE;
        for (Keyboard.Key candidate : candidates) {
            if (candidate == current) {
                continue;
            }
            int oppositeDelta = (direction == Direction.UP)
                    ? centerY(candidate) - cy
                    : cy - centerY(candidate);
            if (oppositeDelta > EPSILON && oppositeDelta > extremeOppositeDelta) {
                extremeOppositeDelta = oppositeDelta;
            }
        }
        if (extremeOppositeDelta == Integer.MIN_VALUE) {
            return null; // no row on the opposite side either (single-row keyboard)
        }

        // Second pass: among keys in that farthest row, pick the closest column.
        Keyboard.Key best = null;
        int bestColumnDelta = Integer.MAX_VALUE;
        for (Keyboard.Key candidate : candidates) {
            if (candidate == current) {
                continue;
            }
            int oppositeDelta = (direction == Direction.UP)
                    ? centerY(candidate) - cy
                    : cy - centerY(candidate);
            if (Math.abs(oppositeDelta - extremeOppositeDelta) > EPSILON) {
                continue; // not part of the farthest row
            }
            int columnDelta = Math.abs(centerX(candidate) - cx);
            if (columnDelta < bestColumnDelta) {
                bestColumnDelta = columnDelta;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Pure geometry scoring for Up/Down, independent of any Keyboard/Key instance so
     * it's trivially unit-testable. Returns a negative value if the candidate is not
     * in the requested direction at all; otherwise a lower score is a better match
     * (row distance dominates, column distance is a tie-breaker).
     */
    static double scoreCandidate(int cx, int cy, int kx, int ky, Direction direction) {
        int primaryDelta;
        int secondaryDelta;

        switch (direction) {
            case UP:
                primaryDelta = cy - ky;
                secondaryDelta = Math.abs(kx - cx);
                break;
            case DOWN:
                primaryDelta = ky - cy;
                secondaryDelta = Math.abs(kx - cx);
                break;
            default:
                return -1;
        }

        if (primaryDelta <= EPSILON) {
            return -1;
        }
        return primaryDelta + SECONDARY_AXIS_WEIGHT * secondaryDelta;
    }

    private static int centerX(Keyboard.Key key) {
        return key.x + key.width / 2;
    }

    private static int centerY(Keyboard.Key key) {
        return key.y + key.height / 2;
    }
}
