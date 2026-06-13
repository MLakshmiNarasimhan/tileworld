package tileworld.agent;

import sim.util.Int2D;
import tileworld.Parameters;
import tileworld.environment.TWDirection;

/**
 * GoldenStrategy
 * ==============
 * The single source of truth for all decision-making mathematics shared
 * across all 6 LakshmiAgents. Stateless — every method is static.
 *
 * This file solves the three core problems that limit score below 700:
 *
 * PROBLEM 1 — FUEL DEATHS (costs ~430 pts each, ~10% of runs)
 * Solution: multi-stage fuel safety with grid-adaptive thresholds.
 *   Stage 1 (COMFORT): proactive refuel when below COMFORT_RATIO of max fuel.
 *                      Fires in ANY mode — not just during exploration.
 *   Stage 2 (EMERGENCY): hard bailout when fuel ≤ dist_to_station + EMERGENCY_PAD.
 *                         EMERGENCY_PAD is large enough to account for sensor delay
 *                         (we can't see the station until we're 3 steps away).
 *   Stage 3 (UNKNOWN STATION): when station not yet found, cap all commitments to
 *                         fuelLevel / UNKNOWN_DIVISOR to keep reserve for searching.
 *
 * PROBLEM 2 — PAIRING EFFICIENCY (~53% of steps wasted)
 * Solution: score every tile by expected net value:
 *   tileScore = BASE - dist_to_tile - dist_tile_to_best_hole - age_penalty
 * Agents pick the tile with the highest score, pre-select the best hole at
 * pickup time, then deliver directly with no replanning needed.
 *
 * PROBLEM 3 — COMMUNICATION UNDERUSE
 * Solution: share the FULL memory snapshot each tick, not just this-tick sightings.
 * All known tiles and holes are published to TeamCommsHub every communicate().
 * Teammates can act on shared info immediately the next tick.
 *
 * Grid-adaptive constants
 * -----------------------
 * All thresholds scale with the grid diagonal so the agent behaves correctly
 * on 50×50, 80×80, and 150×150 without manual re-tuning.
 *
 *   gridDiag = sqrt(wx² + wy²)
 *   50×50  → diag ≈  71
 *   80×80  → diag ≈ 113
 *   150×150 → diag ≈ 212
 *
 * Fuel constants grow with diag because the fuel station can be up to diag
 * steps from any corner of the grid.
 */
public final class GoldenStrategy {

    // =========================================================================
    // GRID-INDEPENDENT CONSTANTS
    // =========================================================================

    /** Legal sensor range as per specification. */
    public static final int SENSOR_RANGE = Parameters.defaultSensorRange; // 3

    /** Maximum tiles an agent can carry (hard limit in TWAgent). */
    public static final int MAX_CARRY = 3;

    /** Number of agents / zones. */
    public static final int NUM_ZONES = 6;

    /** Tile/hole object lifetime in ticks. */
    public static final int OBJECT_LIFETIME = Parameters.lifeTime; // 100

    /**
     * When fuel station is unknown, only commit to a target if its Manhattan
     * distance is ≤ fuelLevel / UNKNOWN_STATION_DIVISOR.
     * Value of 4 keeps 3/4 of fuel in reserve for finding and returning to station.
     */
    public static final int UNKNOWN_STATION_DIVISOR = 4;

    /**
     * Fraction of max fuel at which to trigger comfort refuel.
     * 0.38 means refuel when below 38% of 500 = 190 fuel on 50x50.
     * This fires in ANY mode (not just explore) to prevent ever getting close
     * to the emergency threshold during productive work.
     */
    public static final double COMFORT_RATIO = 0.38;

    /**
     * Fraction of max fuel at which to stop refuelling and leave the station.
     * 0.92 = 460/500 — fill up well but leave sooner than full to save time.
     */
    public static final double REFUEL_RATIO = 0.92;

    /**
     * Minimum carry count before delivering to a hole, unless hole is very close.
     * Set to 2: carry at least 2 tiles before delivery to amortize travel cost.
     */
    public static final int MIN_CARRY_BEFORE_DELIVER = 2;

    /**
     * A hole closer than this many steps is considered "very close" and triggers
     * immediate delivery even if carrying fewer than MIN_CARRY_BEFORE_DELIVER tiles.
     */
    public static final int IMMEDIATE_DELIVER_DIST = 6;

    /**
     * TTL for shared sightings in memory. Objects seen by teammates are trusted
     * for this many ticks. Set to 80 — longer than OBJECT_LIFETIME/2 so agents
     * can still act on info even after some travel.
     */
    public static final int SHARED_MEMORY_TTL = 80;

    /**
     * Age penalty coefficient for scoring tile candidates.
     * Each tick a tile has been in memory costs this many "utility points".
     * Set low so distance matters more than age (preventing premature dismissal).
     */
    public static final double AGE_PENALTY_PER_TICK = 0.3;

    /**
     * Distance cost coefficient for scoring tile candidates.
     * Each step of travel costs this many utility points.
     */
    public static final double DIST_COST_PER_STEP = 1.0;

    /**
     * Task assignment TTL in the GlobalCoordinator.
     * If an agent claims a tile but fails to reach it within this many ticks,
     * the claim expires and another agent can take over.
     */
    public static final int ASSIGN_TTL_TICKS = 40;

    // =========================================================================
    // GRID-ADAPTIVE FUEL CONSTANTS (computed per grid instance)
    // =========================================================================

    /**
     * Compute the absolute fuel emergency threshold for a given grid.
     * The agent MUST return to the fuel station when:
     *   fuelLevel ≤ distToStation + emergencyPad(gridDiag, conservative)
     *
     * emergencyPad = SENSOR_RANGE (min detection delay)
     *              + gridDiag/8   (proportional buffer for travel variance)
     *              + conservativeBonus (extra safety for even-numbered agents)
     *
     * On 50×50 (diag=71):  pad ≈ 3 + 9 + bonus  = 12–22
     * On 80×80 (diag=113): pad ≈ 3 + 14 + bonus = 17–27
     * On 150×150 (diag=212): pad ≈ 3 + 26 + bonus = 29–39
     */
    public static int emergencyPad(int gridDiag, boolean conservative) {
        int base = SENSOR_RANGE + gridDiag / 8;
        return conservative ? base + 10 : base;
    }

    /**
     * Absolute fuel level below which proactive comfort refuel fires.
     * Comfort threshold = max(COMFORT_RATIO * defaultFuelLevel,
     *                        emergencyPad + gridDiag/4)
     * The second term ensures there's always enough headroom above emergency.
     */
    public static int comfortLevel(int gridDiag, boolean conservative) {
        int comfortFromRatio = (int)(Parameters.defaultFuelLevel * COMFORT_RATIO);
        int comfortFromGeometry = emergencyPad(gridDiag, conservative) + gridDiag / 4 + 20;
        return Math.max(comfortFromRatio, comfortFromGeometry);
    }

    /**
     * Fuel level to stop refuelling at (fill-to level).
     * Slightly below max so agents don't waste time on the last few percent.
     */
    public static int refuelTo() {
        return (int)(Parameters.defaultFuelLevel * REFUEL_RATIO);
    }

    // =========================================================================
    // ZONE ALLOCATION
    // =========================================================================

    /** Inclusive left edge of the zone assigned to agentIndex. */
    public static int zoneMinX(int agentIndex, int envWidth) {
        return agentIndex * (envWidth / NUM_ZONES);
    }

    /** Inclusive right edge of the zone assigned to agentIndex. */
    public static int zoneMaxX(int agentIndex, int envWidth) {
        int zw = envWidth / NUM_ZONES;
        return (agentIndex == NUM_ZONES - 1) ? envWidth - 1 : (agentIndex + 1) * zw - 1;
    }

    /** Parse 1-based agent number from name string → 0-based zone index. */
    public static int agentIndex(String name) {
        if (name == null) return 0;
        String digits = name.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            try { return Math.max(0, Math.min(NUM_ZONES-1, Integer.parseInt(digits)-1)); }
            catch (NumberFormatException ignored) {}
        }
        return Math.abs(name.hashCode()) % NUM_ZONES;
    }

    /** Even-numbered agents (agent2, agent4, agent6) are "conservative" — larger fuel buffer. */
    public static boolean isConservative(String name) {
        if (name == null) return false;
        String digits = name.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            try { return Integer.parseInt(digits) % 2 == 0; }
            catch (NumberFormatException ignored) {}
        }
        return false;
    }

    // =========================================================================
    // FUEL FEASIBILITY
    // =========================================================================

    /**
     * Can the agent afford to travel to {@code target} and return to fuel station?
     *
     * @param currentFuel    agent's current fuel
     * @param agentX/Y       agent's current position
     * @param targetX/Y      candidate target position
     * @param fuelStation    known fuel station location (null = unknown)
     * @param gridDiag       sqrt(wx²+wy²) for this grid
     * @param conservative   whether this agent uses conservative fuel buffer
     * @return true if the round trip is safe
     */
    public static boolean canAfford(double currentFuel,
                                     int agentX, int agentY,
                                     int targetX, int targetY,
                                     Int2D fuelStation,
                                     int gridDiag, boolean conservative) {
        int toTarget = manhattan(agentX, agentY, targetX, targetY);
        if (fuelStation == null) {
            // Station unknown: only commit if trip ≤ fuelLevel / UNKNOWN_STATION_DIVISOR
            return toTarget <= (int)(currentFuel / UNKNOWN_STATION_DIVISOR);
        }
        int fromTargetToStation = manhattan(targetX, targetY, fuelStation.x, fuelStation.y);
        int pad = emergencyPad(gridDiag, conservative);
        return currentFuel >= toTarget + fromTargetToStation + pad;
    }

    /**
     * Must the agent return to the fuel station immediately?
     * Returns true when fuel would run out before reaching the station with pad.
     */
    public static boolean mustRefuelNow(double currentFuel,
                                         int agentX, int agentY,
                                         Int2D fuelStation,
                                         int gridDiag, boolean conservative) {
        if (fuelStation == null) return false; // handled by UNKNOWN_STATION_DIVISOR in canAfford
        int distToStation = manhattan(agentX, agentY, fuelStation.x, fuelStation.y);
        return currentFuel <= distToStation + emergencyPad(gridDiag, conservative);
    }

    /**
     * Should the agent proactively head to refuel before fuel gets critical?
     */
    public static boolean shouldComfortRefuel(double currentFuel,
                                               int gridDiag, boolean conservative) {
        return currentFuel <= comfortLevel(gridDiag, conservative);
    }

    // =========================================================================
    // TARGET SCORING
    // =========================================================================

    /**
     * Score for a tile candidate. Higher = better.
     * Accounts for: distance to tile, distance tile→nearest-hole, age of memory.
     *
     * score = BASE_SCORE
     *       - DIST_COST_PER_STEP * distToTile
     *       - DIST_COST_PER_STEP * distTileToHole  (0 if no hole known)
     *       - AGE_PENALTY_PER_TICK * memoryAge
     *
     * @param agentX/Y      agent position
     * @param tileX/Y       candidate tile position
     * @param tileAge       ticks since tile was last seen
     * @param holeX/Y       best known hole position (-1 if none)
     * @param holeAge       ticks since hole was last seen (ignored if no hole)
     */
    public static double tileScore(int agentX, int agentY,
                                    int tileX, int tileY, double tileAge,
                                    int holeX, int holeY, double holeAge) {
        final double BASE = 200.0;
        double score = BASE;
        score -= DIST_COST_PER_STEP * manhattan(agentX, agentY, tileX, tileY);
        if (holeX >= 0) {
            score -= DIST_COST_PER_STEP * manhattan(tileX, tileY, holeX, holeY);
            score -= AGE_PENALTY_PER_TICK * holeAge * 0.5; // holes age matters less
        }
        score -= AGE_PENALTY_PER_TICK * tileAge;
        return score;
    }

    /**
     * Score for a hole candidate when agent is carrying tiles and looking to deliver.
     */
    public static double holeScore(int agentX, int agentY,
                                    int holeX, int holeY, double holeAge,
                                    int tilesCarried) {
        final double BASE = 200.0;
        double score = BASE;
        score -= DIST_COST_PER_STEP * manhattan(agentX, agentY, holeX, holeY);
        score -= AGE_PENALTY_PER_TICK * holeAge;
        score += 20.0 * tilesCarried; // bonus for delivering more tiles in one trip
        return score;
    }

    // =========================================================================
    // DIRECTION HELPERS
    // =========================================================================

    /** Cardinal direction that best reduces Manhattan distance toward (toX, toY). */
    public static TWDirection dirToward(int fromX, int fromY, int toX, int toY) {
        int dx = toX - fromX, dy = toY - fromY;
        if (dx == 0 && dy == 0) return TWDirection.Z;
        return Math.abs(dx) >= Math.abs(dy)
                ? (dx > 0 ? TWDirection.E : TWDirection.W)
                : (dy > 0 ? TWDirection.S : TWDirection.N);
    }

    /** Opposite of a direction. */
    public static TWDirection opposite(TWDirection d) {
        if (d == null) return TWDirection.N;
        switch (d) {
            case N: return TWDirection.S; case S: return TWDirection.N;
            case E: return TWDirection.W; case W: return TWDirection.E;
            default: return TWDirection.N;
        }
    }

    // =========================================================================
    // GEOMETRY
    // =========================================================================

    /** Manhattan distance. */
    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1-x2) + Math.abs(y1-y2);
    }

    /** Grid diagonal length. */
    public static int gridDiag(int wx, int wy) {
        return (int) Math.sqrt((double)wx*wx + (double)wy*wy);
    }

    // No instantiation
    private GoldenStrategy() {}
}
