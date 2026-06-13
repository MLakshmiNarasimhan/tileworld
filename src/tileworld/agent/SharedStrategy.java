package tileworld.agent;

import sim.util.Int2D;
import tileworld.Parameters;
import tileworld.environment.*;
import tileworld.planners.AstarPathGenerator;
import tileworld.planners.TWPath;

import java.util.List;

/**
 * SharedStrategy v3 — tuned for 900+ average reward across all grid sizes.
 *
 * Key changes vs v2
 * -----------------
 * - REFUEL_TARGET: 380 → 325 (65%). Agents leave the station much sooner.
 * - FUEL_COMFORT_RATIO: 0.20 → 0.15. Proactive refuel only at 15% fuel.
 * - FUEL_SAFETY_PAD: 10 → 8. Agents pursue slightly farther targets.
 * - FUEL_EMERGENCY_PAD: 3 → 3 (unchanged — safety floor kept).
 * - MEMORY_TTL: 60 → 80. Shared info trusted longer on all grid sizes.
 * - CLAIM_TTL: 18 → 20. Claims honoured longer, fewer conflicts.
 * - U_HOLE: 200 → 240. Delivery is paramount.
 * - U_EXPLORE: 3 → 2. Exploration is truly last resort.
 * - MAX_CARRY: 3 (hard limit in TWAgent — unchanged).
 * - NUM_ZONES: 6 (one per agent — unchanged).
 */
public final class SharedStrategy {

    // =========================================================================
    // GLOBAL TUNING CONSTANTS
    // =========================================================================

    // Utility values
    public static final double U_HOLE          = 240.0;
    public static final double U_TILE          = 130.0;
    public static final double U_EXPLORE       = 2.0;
    public static final double U_REFUEL        = 70.0;
    public static final double U_DIST_COST     = 1.0;
    public static final double U_CARRIED_BONUS = 25.0;
    public static final double U_CHAIN_BONUS   = 18.0;
    public static final double U_STICKY        = 5.0;
    public static final double U_NEARBY        = 4.5;
    public static final double U_ZONE          = 8.0;
    public static final double U_HEATMAP       = 3.5;
    public static final double U_CLAIM_STRONG  = 40.0;
    public static final double U_CLAIM_WEAK    = 16.0;
    public static final double U_SHARE_DECAY   = 0.60;
    public static final double U_FUEL_DANGER   = 380.0;
    public static final double U_SUPPORT_HOLE  = 16.0;

    // Fuel safety
    public static final int    FUEL_SAFETY_PAD      = 8;
    public static final int    FUEL_COMMIT_MARGIN   = 5;
    public static final int    FUEL_EMERGENCY_PAD   = 3;
    public static final int    REFUEL_TARGET        = 325;  // 65% of 500
    public static final double FUEL_COMFORT_RATIO   = 0.15; // 15%
    public static final int    CONSERVATIVE_EXTRA   = 8;

    // Memory / comms
    public static final int    MEMORY_TTL    = 80;
    public static final int    CLAIM_TTL     = 20;
    public static final int    FAILED_TTL    = 22;
    public static final int    MAX_CARRY     = 3;

    // Exploration
    public static final int    NUM_ZONES     = 6;
    public static final int    SENSOR        = Parameters.defaultSensorRange;

    // =========================================================================
    // ZONE ALLOCATION
    // =========================================================================

    public static int getZoneMinX(int agentIndex, int envWidth) {
        return agentIndex * (envWidth / NUM_ZONES);
    }

    public static int getZoneMaxX(int agentIndex, int envWidth) {
        int zw = envWidth / NUM_ZONES;
        return (agentIndex == NUM_ZONES - 1) ? envWidth - 1 : (agentIndex + 1) * zw - 1;
    }

    public static boolean isInZone(Int2D loc, int agentIndex, int envWidth) {
        return loc.x >= getZoneMinX(agentIndex, envWidth)
                && loc.x <= getZoneMaxX(agentIndex, envWidth);
    }

    public static int parseAgentIndex(String name) {
        if (name == null) return 0;
        String digits = name.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            try { return Math.max(0, Math.min(NUM_ZONES-1, Integer.parseInt(digits)-1)); }
            catch (NumberFormatException ignored) {}
        }
        return Math.abs(name.hashCode()) % NUM_ZONES;
    }

    // =========================================================================
    // FUEL FEASIBILITY
    // =========================================================================

    public static boolean isFuelFeasible(double currentFuel, int distToTarget,
                                          Int2D target, Int2D fuelStation, boolean conservative) {
        if (fuelStation == null) return true;
        int reserve = conservative ? FUEL_SAFETY_PAD + CONSERVATIVE_EXTRA : FUEL_SAFETY_PAD;
        return currentFuel >= distToTarget + manhattan(target.x, target.y, fuelStation.x, fuelStation.y) + reserve;
    }

    public static boolean mustRefuelNow(double currentFuel, int agentX, int agentY,
                                         Int2D fuelStation, boolean committed, boolean conservative) {
        if (fuelStation == null) return false;
        int margin = FUEL_EMERGENCY_PAD + (committed ? FUEL_COMMIT_MARGIN : 0);
        int reserve = conservative ? margin + CONSERVATIVE_EXTRA : margin;
        return currentFuel <= manhattan(agentX, agentY, fuelStation.x, fuelStation.y) + reserve;
    }

    public static boolean shouldSeekFuel(double currentFuel, boolean committed) {
        return committed || (currentFuel / Parameters.defaultFuelLevel) < FUEL_COMFORT_RATIO;
    }

    public static int refuelTarget(int carriedTiles, boolean conservative, boolean committed) {
        int target = REFUEL_TARGET;
        if (carriedTiles >= 1) target += 5 * carriedTiles;
        if (conservative) target += 5;
        if (committed)    target += 8;
        return Math.min(target, (int) Parameters.defaultFuelLevel);
    }

    public static boolean canAffordPickupHere(double currentFuel, int agentX, int agentY,
                                               Int2D fuelStation, boolean conservative) {
        if (fuelStation == null) return currentFuel > 30;
        int reserve = conservative ? FUEL_SAFETY_PAD + CONSERVATIVE_EXTRA : FUEL_SAFETY_PAD;
        return currentFuel > manhattan(agentX, agentY, fuelStation.x, fuelStation.y) + reserve + 2;
    }

    // =========================================================================
    // CLAIM PENALTY
    // =========================================================================

    public static double claimPenalty(int myDist, TeamCommsHub.ClaimRecord claim,
                                       int targetX, int targetY, String myName, double now) {
        if (claim == null || myName.equals(claim.sender) || now - claim.timestamp > CLAIM_TTL) return 0.0;
        if (myDist <= SENSOR) return 0.0;
        int teamDist = claim.distanceTo(targetX, targetY);
        if (teamDist + 2 < myDist) return U_CLAIM_STRONG;
        if (teamDist <= myDist)    return U_CLAIM_WEAK;
        return 0.0;
    }

    // =========================================================================
    // SHARED-INFO AGE PENALTY
    // =========================================================================

    public static double sharedAgePenalty(double now, double sightingTimestamp) {
        return Math.min(30.0, (now - sightingTimestamp) * U_SHARE_DECAY);
    }

    // =========================================================================
    // SUPPORTING-HOLE BONUS
    // =========================================================================

    public static double supportingHoleBonus(Int2D tileLoc, List<Int2D> knownHoles) {
        if (knownHoles == null || knownHoles.isEmpty()) return 0.0;
        int best = Integer.MAX_VALUE;
        for (Int2D h : knownHoles) { int d = manhattan(tileLoc.x, tileLoc.y, h.x, h.y); if (d < best) best = d; }
        return best == Integer.MAX_VALUE ? 0.0 : Math.max(0.0, U_SUPPORT_HOLE - best);
    }

    // =========================================================================
    // FAILED-GOAL HELPERS
    // =========================================================================

    public static boolean isBlacklisted(Int2D failedLocation, String failedFamily,
                                         int failedCooldown, Int2D candidateLoc, String candidateFamily) {
        if (failedCooldown <= 0 || failedLocation == null || !candidateFamily.equals(failedFamily)) return false;
        return failedLocation.x == candidateLoc.x && failedLocation.y == candidateLoc.y;
    }

    // =========================================================================
    // PATH HELPERS
    // =========================================================================

    public static int pathCost(TWPath path) {
        if (path == null || path.getpath() == null) return Integer.MAX_VALUE;
        return Math.max(0, path.getpath().size() - 1);
    }

    public static int realPathCost(AstarPathGenerator generator, int sx, int sy, int tx, int ty) {
        TWPath path = generator.findPath(sx, sy, tx, ty);
        return (path == null || path.getpath() == null) ? manhattan(sx, sy, tx, ty) : pathCost(path);
    }

    // =========================================================================
    // CONSERVATIVE ROLE
    // =========================================================================

    public static boolean isConservativeAgent(String agentName) {
        if (agentName == null) return false;
        String d = agentName.replaceAll("[^0-9]", "");
        if (!d.isEmpty()) { try { return Integer.parseInt(d) % 2 == 0; } catch (NumberFormatException ignored) {} }
        return false;
    }

    // =========================================================================
    // DIRECTION HELPERS
    // =========================================================================

    public static TWDirection directionToward(int fromX, int fromY, int toX, int toY) {
        int dx = toX - fromX, dy = toY - fromY;
        if (dx == 0 && dy == 0) return TWDirection.Z;
        return Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? TWDirection.E : TWDirection.W)
                                             : (dy > 0 ? TWDirection.S : TWDirection.N);
    }

    public static TWDirection oppositeOf(TWDirection d) {
        if (d == null) return TWDirection.N;
        switch (d) { case N: return TWDirection.S; case S: return TWDirection.N;
                     case E: return TWDirection.W; case W: return TWDirection.E; default: return TWDirection.N; }
    }

    // =========================================================================
    // SCAN HELPER
    // =========================================================================

    public static void scanSensorRange(TWEnvironment env, int agentX, int agentY,
                                        List<Int2D> seenTiles, List<Int2D> seenHoles, Int2D[] fuelHolder) {
        seenTiles.clear(); seenHoles.clear();
        int minX=Math.max(0,agentX-SENSOR), maxX=Math.min(env.getxDimension()-1,agentX+SENSOR);
        int minY=Math.max(0,agentY-SENSOR), maxY=Math.min(env.getyDimension()-1,agentY+SENSOR);
        for (int sx=minX;sx<=maxX;sx++) for (int sy=minY;sy<=maxY;sy++) {
            if (Math.max(Math.abs(sx-agentX),Math.abs(sy-agentY)) > SENSOR) continue;
            TWEntity e=(TWEntity)env.getObjectGrid().get(sx,sy);
            if      (e instanceof TWFuelStation) fuelHolder[0]=new Int2D(sx,sy);
            else if (e instanceof TWTile)        seenTiles.add(new Int2D(sx,sy));
            else if (e instanceof TWHole)        seenHoles.add(new Int2D(sx,sy));
        }
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    public static int manhattan(int x1, int y1, int x2, int y2) {
        return Math.abs(x1-x2) + Math.abs(y1-y2);
    }

    private SharedStrategy() {}
}
