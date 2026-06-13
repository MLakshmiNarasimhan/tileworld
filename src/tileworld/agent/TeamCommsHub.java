package tileworld.agent;

import sim.util.Int2D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * TeamCommsHub
 * ============
 * Central communication infrastructure shared by ALL agents in the same JVM.
 *
 * Responsibilities
 * ----------------
 * 1. Static shared board  — instant cross-agent tile/hole/fuel sighting sharing
 *    (Lakshmi pattern, extended to all agents).
 * 2. AgentMessage class   — single typed message replacing the 5 different
 *    string/object protocols used by the original agents:
 *      - Harsh's  AgentBroadcast   (sightings + structured target)
 *      - Hemath's "FUEL/CLAIM/SEEN" strings
 *      - Hugo's   "FUEL_LOC/TILE_FOUND" strings
 *      - Jolene's CoordinationMessage
 *      - Apt's    "OBS TYPE X Y STEP" strings
 * 3. ClaimRecord          — unified claim representation (Harsh + Jolene).
 * 4. Sighting             — timed observation entry on the shared board.
 * 5. Lifecycle helpers    — reset on new run, prune stale entries.
 *
 * Thread safety: all static state is guarded by LOCK.
 */
public final class TeamCommsHub {

    // =========================================================================
    // LOCK + STATIC SHARED STATE
    // =========================================================================

    /** Single lock for all static shared state. */
    public static final Object LOCK = new Object();

    /** Shared tile sightings visible to every agent this run. */
    private static final List<Sighting> BOARD_TILES = new ArrayList<>();

    /** Shared hole sightings visible to every agent this run. */
    private static final List<Sighting> BOARD_HOLES = new ArrayList<>();

    /** Permanently remembered fuel station (never forgotten once discovered). */
    private static Int2D sharedFuelStation = null;

    /** How many agents have been constructed this run. */
    private static int agentCount = 0;

    /** Total agents constructed (set after first tick, stable thereafter). */
    public static int totalAgents = 0;

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    /** Ticks after which a shared sighting is considered stale and pruned. */
    public static final int SIGHTING_TTL = 90;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    /**
     * Must be called from every agent's constructor.
     * The first agent to call this in a new run resets all shared state.
     *
     * @param isFirstAgent pass {@code true} only for the agent with index 0 /
     *                     name "agent1"; all others pass {@code false}.
     *                     Alternatively every agent passes {@code false} and
     *                     the hub auto-resets when agentCount wraps back to 1.
     */
    public static void registerAgent(boolean resetRun) {
        synchronized (LOCK) {
            if (resetRun || agentCount == 0) {
                BOARD_TILES.clear();
                BOARD_HOLES.clear();
                sharedFuelStation = null;
                agentCount = 0;
            }
            agentCount++;
            totalAgents = agentCount;
        }
    }

    // =========================================================================
    // FUEL STATION
    // =========================================================================

    /** Publish the fuel station location. Ignored if already known. */
    public static void publishFuel(Int2D location) {
        if (location == null) return;
        synchronized (LOCK) {
            if (sharedFuelStation == null) {
                sharedFuelStation = new Int2D(location.x, location.y);
            }
        }
    }

    /**
     * Retrieve the shared fuel station location.
     *
     * @return the location, or {@code null} if not yet discovered.
     */
    public static Int2D getFuelStation() {
        synchronized (LOCK) {
            return sharedFuelStation;
        }
    }

    // =========================================================================
    // SHARED SIGHTINGS BOARD
    // =========================================================================

    /**
     * Publish a list of tile locations observed at timestamp {@code ts}.
     * Existing entries for the same cell are refreshed; new ones are added.
     */
    public static void publishTiles(List<Int2D> locations, double ts) {
        if (locations == null || locations.isEmpty()) return;
        synchronized (LOCK) {
            publishToBoard(BOARD_TILES, locations, ts);
        }
    }

    /**
     * Publish a list of hole locations observed at timestamp {@code ts}.
     */
    public static void publishHoles(List<Int2D> locations, double ts) {
        if (locations == null || locations.isEmpty()) return;
        synchronized (LOCK) {
            publishToBoard(BOARD_HOLES, locations, ts);
        }
    }

    /** Publish one tile sighting with its original timestamp. */
    public static void publishTile(int x, int y, double ts) {
        synchronized (LOCK) {
            publishSighting(BOARD_TILES, x, y, ts);
        }
    }

    /** Publish one hole sighting with its original timestamp. */
    public static void publishHole(int x, int y, double ts) {
        synchronized (LOCK) {
            publishSighting(BOARD_HOLES, x, y, ts);
        }
    }

    /**
     * Returns a snapshot of the current shared tile board.
     * Callers receive a copy; modifications do not affect the board.
     */
    public static List<Sighting> getTileBoard() {
        synchronized (LOCK) {
            return new ArrayList<>(BOARD_TILES);
        }
    }

    /**
     * Returns a snapshot of the current shared hole board.
     */
    public static List<Sighting> getHoleBoard() {
        synchronized (LOCK) {
            return new ArrayList<>(BOARD_HOLES);
        }
    }

    /**
     * Remove a specific cell from the shared boards after it has been consumed.
     *
     * @param x      grid x coordinate
     * @param y      grid y coordinate
     * @param isTile {@code true} to remove from tile board, {@code false} for hole board
     */
    public static void consumeCell(int x, int y, boolean isTile) {
        synchronized (LOCK) {
            List<Sighting> board = isTile ? BOARD_TILES : BOARD_HOLES;
            board.removeIf(s -> s.x == x && s.y == y);
        }
    }

    /** Remove any shared tile/hole sightings for a cell that is currently known empty. */
    public static void clearCell(int x, int y) {
        synchronized (LOCK) {
            BOARD_TILES.removeIf(s -> s.x == x && s.y == y);
            BOARD_HOLES.removeIf(s -> s.x == x && s.y == y);
        }
    }

    /**
     * Prune sightings older than {@link #SIGHTING_TTL} ticks.
     * Each agent should call this once per tick in {@code communicate()}.
     *
     * @param now current simulation time
     */
    public static void pruneStale(double now) {
        synchronized (LOCK) {
            pruneBoard(BOARD_TILES, now);
            pruneBoard(BOARD_HOLES, now);
        }
    }

    // =========================================================================
    // INTERNAL BOARD HELPERS
    // =========================================================================

    private static void publishToBoard(List<Sighting> board,
                                        List<Int2D> locations, double ts) {
        for (Int2D loc : locations) {
            publishSighting(board, loc.x, loc.y, ts);
        }
    }

    private static void publishSighting(List<Sighting> board, int x, int y, double ts) {
        boolean found = false;
        for (Sighting s : board) {
            if (s.x == x && s.y == y) {
                if (ts > s.timestamp) s.timestamp = ts;
                found = true;
                break;
            }
        }
        if (!found) board.add(new Sighting(x, y, ts));
    }

    private static void pruneBoard(List<Sighting> board, double now) {
        Iterator<Sighting> it = board.iterator();
        while (it.hasNext()) {
            if (now - it.next().timestamp > SIGHTING_TTL) it.remove();
        }
    }

    // =========================================================================
    // NESTED: Sighting  (timed board entry)
    // =========================================================================

    /**
     * A timed observation of a tile or hole on the shared board.
     */
    public static final class Sighting {
        public final int x;
        public final int y;
        /** Last simulation tick at which this cell was observed. */
        public volatile double timestamp;

        public Sighting(int x, int y, double timestamp) {
            this.x = x;
            this.y = y;
            this.timestamp = timestamp;
        }

        /** Convenience constructor from Int2D. */
        public Sighting(Int2D loc, double timestamp) {
            this(loc.x, loc.y, timestamp);
        }

        public Int2D toInt2D() { return new Int2D(x, y); }

        @Override
        public String toString() {
            return "Sighting(" + x + "," + y + "@" + timestamp + ")";
        }
    }

    // =========================================================================
    // NESTED: ClaimRecord  (unified claim from Harsh + Jolene)
    // =========================================================================

    /**
     * Records which agent is pursuing a particular target, along with the
     * agent's position at broadcast time (used for proximity-weighted penalty).
     */
    public static final class ClaimRecord {
        public final String sender;
        public final int    senderX;
        public final int    senderY;
        public final double timestamp;

        public ClaimRecord(String sender, int senderX, int senderY,
                           double timestamp) {
            this.sender    = sender;
            this.senderX   = senderX;
            this.senderY   = senderY;
            this.timestamp = timestamp;
        }

        /** Manhattan distance from sender to the claimed target. */
        public int distanceTo(int tx, int ty) {
            return Math.abs(senderX - tx) + Math.abs(senderY - ty);
        }
    }

    // =========================================================================
    // NESTED: AgentMessage  (replaces ALL prior message formats)
    // =========================================================================

    /**
     * Single typed message broadcast by every agent each tick.
     *
     * Carries:
     *  - sender identity + position + fuel level
     *  - fuel station location (if known)
     *  - lists of tiles and holes seen this tick
     *  - optional claim: the type ("TILE"/"HOLE") and location being pursued
     *
     * Replaces:
     *  Harsh   → AgentBroadcast
     *  Hemath  → "FUEL:x:y", "CLAIM:TILE:x:y", "SEEN:TILE:x:y" strings
     *  Hugo    → "FUEL_LOC:x,y", "TILE_FOUND:x,y" strings
     *  Jolene  → CoordinationMessage
     *  Apt     → "OBS FUEL x y step" strings
     */
    public static final class AgentMessage extends Message {

        public final String      from;
        public final double      timestamp;
        public final int         senderX;
        public final int         senderY;
        public final double      senderFuel;
        public final Int2D       fuelStation;   // null if unknown
        public final List<Int2D> seenTiles;     // tiles in sensor range this tick
        public final List<Int2D> seenHoles;     // holes in sensor range this tick
        public final String      claimType;     // "TILE", "HOLE", or null
        public final Int2D       claimLocation; // null if no active claim

        public AgentMessage(String from,
                            double timestamp,
                            int senderX, int senderY,
                            double senderFuel,
                            Int2D fuelStation,
                            List<Int2D> seenTiles,
                            List<Int2D> seenHoles,
                            String claimType,
                            Int2D claimLocation) {
            // Use the parent Message constructor: (from, to, message-string)
            super(from, "*", "AGENT_MSG");
            this.from          = from;
            this.timestamp     = timestamp;
            this.senderX       = senderX;
            this.senderY       = senderY;
            this.senderFuel    = senderFuel;
            this.fuelStation   = fuelStation;
            this.seenTiles     = seenTiles  != null
                    ? Collections.unmodifiableList(seenTiles)
                    : Collections.<Int2D>emptyList();
            this.seenHoles     = seenHoles  != null
                    ? Collections.unmodifiableList(seenHoles)
                    : Collections.<Int2D>emptyList();
            this.claimType     = claimType;
            this.claimLocation = claimLocation;
        }

        /** @return true if this message carries a valid claim. */
        public boolean hasClaim() {
            return claimType != null && claimLocation != null;
        }

        /** @return true if the claim targets a tile. */
        public boolean claimIsTile() {
            return "TILE".equals(claimType);
        }

        /** @return true if the claim targets a hole. */
        public boolean claimIsHole() {
            return "HOLE".equals(claimType);
        }

        @Override
        public String toString() {
            return "AgentMessage{from=" + from
                    + ", pos=(" + senderX + "," + senderY + ")"
                    + ", fuel=" + (int) senderFuel
                    + ", tiles=" + seenTiles.size()
                    + ", holes=" + seenHoles.size()
                    + ", claim=" + claimType + "@" + claimLocation + "}";
        }
    }

    // =========================================================================
    // Private constructor — utility class, never instantiated
    // =========================================================================
    private TeamCommsHub() {}
}
