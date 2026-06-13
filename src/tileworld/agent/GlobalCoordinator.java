package tileworld.agent;

import sim.util.Int2D;
import tileworld.Parameters;

import java.util.HashMap;
import java.util.Map;

/**
 * GlobalCoordinator
 * =================
 * Shared singleton that provides three services no existing class offers:
 *
 * 1. COVERAGE MAP
 *    A per-column timestamp grid recording when each column was last scanned
 *    by any agent. Agents query stalestColumnInZone() during exploration so
 *    their sweep always targets the column least recently seen by the whole
 *    team — eliminating redundant overlapping passes.
 *
 * 2. CONFLICT-FREE TASK ASSIGNMENT
 *    A first-come-first-served assignment table for tiles and holes.
 *    tryAssignTile() / tryAssignHole() atomically reserve a target for one
 *    agent and reject farther competitors before they waste any steps.
 *    Assignments expire after ASSIGN_TTL ticks so stale locks never block
 *    the team permanently.
 *
 * 3. HOT-ZONE TRACKER
 *    Records where successful tile-hole matches happen. Returns the centroid
 *    of recent match locations so idle agents can bias their sweep toward
 *    the most productive area of the map rather than returning to their
 *    static home zone.
 *
 * Thread safety: all state is guarded by the single LOCK object, consistent
 * with the pattern used by TeamCommsHub.
 *
 * Lifecycle: reset() is called by the first LakshmiAgent constructor each
 * run (same guard as TeamCommsHub.registerAgent).
 */
public final class GlobalCoordinator {

    // =========================================================================
    // LOCK
    // =========================================================================
    public  static final Object LOCK = new Object();

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    /** Ticks after which a task assignment auto-expires. */
    public static final int ASSIGN_TTL    = 30;

    /** Number of recent matches kept for hot-zone calculation. */
    private static final int MATCH_HISTORY = 40;

    /** Ticks a coverage cell timestamp is considered fresh. */
    public static final int COVERAGE_TTL  = 60;

    /** 7 = 2 * sensorRange + 1, the natural non-overlapping coverage stride. */
    private static final int COVERAGE_STRIDE = Parameters.defaultSensorRange * 2 + 1;

    // =========================================================================
    // SHARED STATE — Coverage Map
    // =========================================================================

    /**
     * lastCoverage[bx][by] = simulation tick at which this coarse 7x7 band
     * was last covered by any agent's legal sensor sweep.
     */
    private static double[][] lastCoverage;
    private static int gridW = 0;
    private static int gridH = 0;
    private static int bandW = 0;
    private static int bandH = 0;

    // =========================================================================
    // SHARED STATE — Task Assignment
    // =========================================================================

    /** Key = packed cell (x<<16|y), Value = assignment record. */
    private static final Map<Long, Assignment> tileAssignments = new HashMap<>();
    private static final Map<Long, Assignment> holeAssignments = new HashMap<>();

    // =========================================================================
    // SHARED STATE — Hot-Zone Tracker
    // =========================================================================

    /** Ring buffer of recent match locations. */
    private static final int[]    matchX  = new int[MATCH_HISTORY];
    private static final int[]    matchY  = new int[MATCH_HISTORY];
    private static final double[] matchT  = new double[MATCH_HISTORY];
    private static int matchHead  = 0;
    private static int matchCount = 0;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    /**
     * Must be called once per simulation run before any other method.
     * LakshmiAgent calls this from the agent-index-0 constructor path.
     *
     * @param wx grid width
     * @param wy grid height
     */
    public static void reset(int wx, int wy) {
        synchronized (LOCK) {
            gridW      = wx;
            gridH      = wy;
            bandW = Math.max(1, (wx + COVERAGE_STRIDE - 1) / COVERAGE_STRIDE);
            bandH = Math.max(1, (wy + COVERAGE_STRIDE - 1) / COVERAGE_STRIDE);
            lastCoverage = new double[bandW][bandH];
            for (int bx = 0; bx < bandW; bx++) {
                for (int by = 0; by < bandH; by++) {
                    lastCoverage[bx][by] = -COVERAGE_TTL;
                }
            }
            tileAssignments.clear();
            holeAssignments.clear();
            matchHead  = 0;
            matchCount = 0;
        }
    }

    // =========================================================================
    // 1. COVERAGE MAP
    // =========================================================================

    /**
     * Record that the agent centred at (cx, cy) scanned all coarse coverage
     * cells touched by its legal Chebyshev range.
     *
     * @param cx    agent x position
     * @param cy    agent y position
     * @param range Chebyshev scan radius (SCAN_RANGE)
     * @param now   current simulation time
     */
    public static void markScanned(int cx, int cy, int range, double now) {
        synchronized (LOCK) {
            if (lastCoverage == null) return;
            int bx0 = bandIndex(Math.max(0, cx - range));
            int bx1 = bandIndex(Math.min(gridW - 1, cx + range));
            int by0 = bandIndex(Math.max(0, cy - range));
            int by1 = bandIndex(Math.min(gridH - 1, cy + range));
            for (int bx = bx0; bx <= bx1; bx++) {
                for (int by = by0; by <= by1; by++) {
                    if (now > lastCoverage[bx][by]) lastCoverage[bx][by] = now;
                }
            }
        }
    }

    /**
     * Returns the centre of the stalest coarse coverage cell within the
     * agent's x-zone. This avoids treating an entire column as freshly seen
     * when the team only passed through one small row segment.
     *
     * @param zoneMin inclusive left edge of this agent's zone
     * @param zoneMax inclusive right edge of this agent's zone
     * @param fromX   current x position of the querying agent
     * @param fromY   current y position of the querying agent
     * @param now     current simulation time
     * @return centre of the stalest coarse band in this zone
     */
    public static Int2D stalestCoverageTargetInZone(int zoneMin, int zoneMax,
                                                    int fromX, int fromY, double now) {
        synchronized (LOCK) {
            if (lastCoverage == null) {
                return new Int2D((zoneMin + zoneMax) / 2, gridH / 2);
            }
            int bx0 = bandIndex(zoneMin);
            int bx1 = bandIndex(zoneMax);
            double bestScore = Double.NEGATIVE_INFINITY;
            Int2D best = new Int2D((zoneMin + zoneMax) / 2, gridH / 2);
            for (int bx = bx0; bx <= bx1; bx++) {
                int cx = bandCenterX(bx, zoneMin, zoneMax);
                for (int by = 0; by < bandH; by++) {
                    int cy = bandCenterY(by);
                    double age = now - lastCoverage[bx][by];
                    int travel = Math.abs(cx - fromX) + Math.abs(cy - fromY);
                    double score = age * 10.0 - travel;
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Int2D(cx, cy);
                    }
                }
            }
            return best;
        }
    }

    /**
     * Compatibility helper retained for older heuristics that still reason in
     * terms of x-columns.
     *
     * @param zoneMin inclusive left edge
     * @param zoneMax inclusive right edge
     * @param now     current simulation time
     * @return x-coordinate nearest the stalest coarse coverage target
     */
    public static int stalestColumnInZone(int zoneMin, int zoneMax, double now) {
        return stalestCoverageTargetInZone(zoneMin, zoneMax,
                (zoneMin + zoneMax) / 2, gridH / 2, now).x;
    }

    // =========================================================================
    // 2. CONFLICT-FREE TASK ASSIGNMENT
    // =========================================================================

    /**
     * Attempt to claim a tile at (tx, ty) for agent {@code name}.
     *
     * Returns {@code true} if the assignment succeeded — the agent should
     * pursue this tile.  Returns {@code false} if another agent is already
     * assigned and is closer — the caller should skip this tile entirely.
     *
     * Stale assignments (older than ASSIGN_TTL) are silently evicted.
     *
     * @param name   calling agent name
     * @param tx     tile x
     * @param ty     tile y
     * @param myDist caller's Manhattan distance to (tx, ty)
     * @param now    current simulation time
     * @return true  if this agent now owns the assignment
     */
    public static boolean tryAssignTile(String name, int tx, int ty,
                                         int myDist, double now) {
        return tryAssign(tileAssignments, name, tx, ty, myDist, now);
    }

    /**
     * Attempt to claim a hole at (hx, hy) for agent {@code name}.
     * Same semantics as tryAssignTile.
     */
    public static boolean tryAssignHole(String name, int hx, int hy,
                                         int myDist, double now) {
        return tryAssign(holeAssignments, name, hx, hy, myDist, now);
    }

    /**
     * Release all tile and hole assignments held by {@code name}.
     * Called when an agent successfully picks up a tile or puts one down,
     * or when its plan is voided.
     *
     * @param name agent name
     */
    public static void releaseAll(String name) {
        synchronized (LOCK) {
            tileAssignments.values().removeIf(a -> name.equals(a.owner));
            holeAssignments.values().removeIf(a -> name.equals(a.owner));
        }
    }

    /**
     * Release a specific tile assignment (e.g., after the tile vanished).
     */
    public static void releaseTile(int tx, int ty) {
        synchronized (LOCK) { tileAssignments.remove(pack(tx, ty)); }
    }

    /**
     * Release a specific hole assignment (e.g., after the hole was filled).
     */
    public static void releaseHole(int hx, int hy) {
        synchronized (LOCK) { holeAssignments.remove(pack(hx, hy)); }
    }

    // =========================================================================
    // 3. HOT-ZONE TRACKER
    // =========================================================================

    /**
     * Record a successful tile-hole match at (x, y).
     * Called from LakshmiAgent.act() immediately after putTileInHole().
     *
     * @param x match x coordinate
     * @param y match y coordinate
     * @param now current simulation time
     */
    public static void recordMatch(int x, int y, double now) {
        synchronized (LOCK) {
            matchX[matchHead] = x;
            matchY[matchHead] = y;
            matchT[matchHead] = now;
            matchHead = (matchHead + 1) % MATCH_HISTORY;
            if (matchCount < MATCH_HISTORY) matchCount++;
        }
    }

    /**
     * Returns the centroid of recent match locations, weighted by recency,
     * or {@code null} if no matches have been recorded yet.
     *
     * Agents use this as an "interceptor target" when in EXPLORE mode:
     * heading toward the hot zone puts them in the area with the highest
     * historical density of tile-hole co-occurrence.
     *
     * @param now   current simulation time
     * @param ttl   how far back (in ticks) to include matches
     * @return weighted centroid, or null
     */
    public static Int2D hotZoneCentroid(double now, double ttl) {
        synchronized (LOCK) {
            if (matchCount == 0) return null;
            double wx = 0, wy = 0, wSum = 0;
            for (int i = 0; i < matchCount; i++) {
                double age = now - matchT[i];
                if (age > ttl) continue;
                double w = 1.0 / (age + 1);   // recency weight
                wx   += w * matchX[i];
                wy   += w * matchY[i];
                wSum += w;
            }
            if (wSum == 0) return null;
            return new Int2D((int)(wx / wSum), (int)(wy / wSum));
        }
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private static boolean tryAssign(Map<Long, Assignment> table,
                                      String name, int x, int y,
                                      int myDist, double now) {
        synchronized (LOCK) {
            long key = pack(x, y);
            Assignment existing = table.get(key);

            // Evict stale assignment
            if (existing != null && now - existing.timestamp > ASSIGN_TTL) {
                existing = null;
                table.remove(key);
            }

            if (existing == null) {
                // No owner — claim it
                table.put(key, new Assignment(name, myDist, now));
                return true;
            }

            if (name.equals(existing.owner)) {
                // Refresh our own claim
                existing.timestamp = now;
                existing.dist      = myDist;
                return true;
            }

            // Another agent owns it — yield only if they are strictly closer
            if (existing.dist <= myDist) return false;

            // We are closer — steal the assignment
            table.put(key, new Assignment(name, myDist, now));
            return true;
        }
    }

    private static long pack(int x, int y) { return ((long) x << 16) | (y & 0xFFFF); }

    private static int bandIndex(int coord) {
        return Math.max(0, coord / COVERAGE_STRIDE);
    }

    private static int bandCenterX(int bx, int zoneMin, int zoneMax) {
        int x = bx * COVERAGE_STRIDE + COVERAGE_STRIDE / 2;
        return Math.max(zoneMin, Math.min(zoneMax, Math.min(gridW - 1, x)));
    }

    private static int bandCenterY(int by) {
        int y = by * COVERAGE_STRIDE + COVERAGE_STRIDE / 2;
        return Math.max(0, Math.min(gridH - 1, y));
    }

    // =========================================================================
    // NESTED: Assignment record
    // =========================================================================

    private static final class Assignment {
        String owner;
        int    dist;
        double timestamp;

        Assignment(String owner, int dist, double timestamp) {
            this.owner     = owner;
            this.dist      = dist;
            this.timestamp = timestamp;
        }
    }

    // Prevent instantiation
    private GlobalCoordinator() {}
}
