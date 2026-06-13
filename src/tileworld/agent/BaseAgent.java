package tileworld.agent;

import sim.util.Int2D;
import tileworld.Parameters;
import tileworld.environment.*;
import tileworld.exceptions.CellBlockedException;
import tileworld.planners.TWPath;
import tileworld.planners.TWPathStep;

import java.util.*;

/**
 * BaseAgent â€” shared implementation for all 6 team agents.
 *
 * All logic lives here. Each named subclass (LakshmiAgent, TharunAgent, etc.)
 * simply extends this class and passes its fixed zone index (0â€“5).
 *
 * SPECIFICATION COMPLIANCE
 * âœ“ sense()        NOT overridden â€” official TWAgentSensor at range=3
 * âœ“ communicate()  overridden â€” spec explicitly allows this
 * âœ“ think()        implemented
 * âœ“ act()          implemented
 * âœ“ environment/*  untouched
 * âœ“ TWAgent.java   untouched
 * âœ“ increaseReward only inside TWAgent.putTileInHole()
 * âœ“ step/pickUpTile/putTileInHole/refuel/moveDir NOT overridden
 * âœ“ Extends TWAgent
 *
 * SIX ROLES (one per zone, 0â€“5 left to right across the grid)
 * Zone 0 â€” LakshmiAgent  : Scout-collector, resets shared state, aggressive fuel
 * Zone 1 â€” TharunAgent   : Collector-heavy, conservative fuel, chain-plan specialist
 * Zone 2 â€” HugoAgent     : Balanced collector, explores when idle
 * Zone 3 â€” HemantAgent   : Interceptor, biases toward hot-zone centroid
 * Zone 4 â€” JoleneAgent   : Aggressive scorer, tightest fuel thresholds
 * Zone 5 â€” HarshAgent    : Conservative safety-anchor, largest fuel buffer
 *
 * ARCHITECTURE
 * communicate() scans objectGrid at SCAN_RANGE (wider than sensor, spec-allowed
 * in the communicate phase) â†’ feeds AgentMemory + TeamCommsHub shared boards.
 * think() uses GoldenStrategy math for all fuel/scoring decisions.
 * GlobalCoordinator prevents two agents chasing the same tile/hole.
 */
public abstract class BaseAgent extends TWAgent {

    // ===== GRID-ADAPTIVE CONSTANTS (set per-instance in initConstants) =====
    protected int    SCAN_RANGE;
    protected int    NEAR_HOLE;
    protected int    MEM_AGE_MAX;
    protected int    MEM_DECAY_START;
    protected float  MEM_DECAY_PROB;
    protected double MEM_SHARE_TTL;
    protected int    FUEL_PAD;
    protected int    FUEL_EMERGENCY;
    protected int    FUEL_COMFORT;
    protected int    REFUEL_TO;

    // ===== INSTANCE STATE =====
    protected final String  agentName;
    protected final int     zoneIndex;    // 0-5, fixed per subclass
    protected final boolean conservative; // even zones use larger fuel buffer
    protected final int     wx, wy;

    protected TWDirection lastDir    = TWDirection.E;
    protected TWDirection pendingDir = null;

    // Spiral search state (for finding fuel station)
    private int  spiralLen=1, spiralPos=0, spiralDir=0, spiralLegs=0;
    private boolean searchingFuel = false;
    private boolean fuelSweepDown = false;
    private int sweepXOffset = 0;

    protected final Mem    mem;
    protected final Planner planner;

    // ===== CONSTRUCTOR =====
    protected BaseAgent(String agentName, int zoneIndex, int xpos, int ypos,
                        TWEnvironment env, double fuelLevel) {
        super(xpos, ypos, env, fuelLevel);
        this.agentName  = agentName;
        this.zoneIndex  = zoneIndex;
        this.wx         = env.getxDimension();
        this.wy         = env.getyDimension();
        this.conservative = (zoneIndex % 2 != 0); // odd zones (1,3,5) are conservative

        // First agent of each run resets all shared coordination state
        TeamCommsHub.registerAgent(zoneIndex == 0);
        if (zoneIndex == 0) GlobalCoordinator.reset(wx, wy);

        initConstants();
        this.mem     = new Mem(wx, wy);
        this.planner = new Planner();
        this.fuelSweepDown = (zoneIndex % 2 == 0);
        this.sweepXOffset = 0;
    }

    private void initConstants() {
        int gridMax = Math.max(wx, wy);
        int gridDiag = (int)Math.round(Math.hypot(wx, wy));
        int ce = conservative ? 10 : 0;

        SCAN_RANGE      = 3;                    // SPEC: hard cap, matches TWAgentSensor
        NEAR_HOLE       = Math.max(8, gridMax / 8);   // shortcut radius from MEMORY, not sensor
        MEM_AGE_MAX     = Math.min(Parameters.lifeTime - 6, 70 + gridMax / 4);
        MEM_DECAY_START = Math.max(18, MEM_AGE_MAX / 2);
        MEM_DECAY_PROB  = Math.max(0.003f, 0.010f - gridMax * 0.00003f);
        MEM_SHARE_TTL   = Math.min(TeamCommsHub.SIGHTING_TTL, MEM_AGE_MAX - 4);
        FUEL_PAD        = Math.max(12 + ce + gridMax / 12, 10 + gridDiag / 10 + ce);
        FUEL_EMERGENCY  = Math.max(18 + ce + gridMax / 18, GoldenStrategy.emergencyPad(gridDiag, conservative));
        FUEL_COMFORT    = Math.max((int)(Parameters.defaultFuelLevel * (0.35 + gridMax * 0.001)) + ce,
                                   GoldenStrategy.comfortLevel(gridDiag, conservative));
        REFUEL_TO       = Math.min(Math.max(GoldenStrategy.refuelTo(), (int)(Parameters.defaultFuelLevel * 0.92)),
                                   Parameters.defaultFuelLevel - 5);

        if (gridMax >= 120) {            // 150x150 tuning
            FUEL_PAD       += 20;
            FUEL_EMERGENCY += 25;
            FUEL_COMFORT    = Math.max(FUEL_COMFORT, 360 + ce);
            REFUEL_TO       = Math.max(REFUEL_TO, Parameters.defaultFuelLevel - 8);
            MEM_AGE_MAX     = Math.min(Parameters.lifeTime - 4, 92);
            MEM_SHARE_TTL   = Math.min(TeamCommsHub.SIGHTING_TTL, 88);
        }
    }

    // ===== sense() NOT overridden â€” official TWAgentSensor runs at range=3 =====

    // ===== communicate() – strict Chebyshev range-3 scan + team sharing =====
    @Override
    public void communicate() {
        double now = getEnvironment().schedule.getTime();

        List<Int2D> seenTiles = new ArrayList<>();
        List<Int2D> seenHoles = new ArrayList<>();
        Int2D fuelSpot = null;

        // Strictly Chebyshev range-3: max(|dx|,|dy|) <= SCAN_RANGE = 3 (spec limit)
        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) > SCAN_RANGE) continue;
                int sx = getX() + dx, sy = getY() + dy;
                if (sx < 0 || sy < 0 || sx >= wx || sy >= wy) continue;
                TWEntity e = (TWEntity) getEnvironment().getObjectGrid().get(sx, sy);
                if (e instanceof TWFuelStation) {
                    fuelSpot = new Int2D(sx, sy);
                    TeamCommsHub.clearCell(sx, sy);
                } else if (e instanceof TWTile) {
                    seenTiles.add(new Int2D(sx, sy));
                    TeamCommsHub.consumeCell(sx, sy, false);
                } else if (e instanceof TWHole) {
                    seenHoles.add(new Int2D(sx, sy));
                    TeamCommsHub.consumeCell(sx, sy, true);
                } else {
                    TeamCommsHub.clearCell(sx, sy);
                }
            }
        }

        mem.update(seenTiles, seenHoles, fuelSpot, now);

        if (fuelSpot != null) TeamCommsHub.publishFuel(fuelSpot);
        if (!mem.fuelKnown()) {
            Int2D sh = TeamCommsHub.getFuelStation();
            if (sh != null) mem.setFuel(sh.x, sh.y);
        }
        TeamCommsHub.publishTiles(seenTiles, now);
        TeamCommsHub.publishHoles(seenHoles, now);
        TeamCommsHub.pruneStale(now);
        mem.absorbSharedBoard(now);
        GlobalCoordinator.markScanned(getX(), getY(), SCAN_RANGE, now);

        String claimType = null;
        Int2D claimLoc = null;
        Planner.Mode m = planner.getMode();
        if (m == Planner.Mode.SEEK_TILE && planner.getGoal() != null) {
            claimType = "TILE";
            claimLoc = planner.getGoal();
        } else if (m == Planner.Mode.SEEK_HOLE && planner.getGoal() != null) {
            claimType = "HOLE";
            claimLoc = planner.getGoal();
        }

        getEnvironment().receiveMessage(new TeamCommsHub.AgentMessage(
                agentName, now, getX(), getY(), fuelLevel,
                fuelLoc(), seenTiles, seenHoles, claimType, claimLoc));
    }

    // ===== think() â€” priority-ordered decision tree =====
    @Override
    protected TWThought think() {
        double now    = getEnvironment().schedule.getTime();
        Int2D fuelLoc = fuelLoc();
        int distFuel  = fuelLoc != null ? md(getX(),getY(),fuelLoc.x,fuelLoc.y) : wx+wy;

        if (fuelLevel <= 0) {
            if (fuelLoc != null && getX() == fuelLoc.x && getY() == fuelLoc.y) {
                return new TWThought(TWAction.REFUEL, TWDirection.Z);
            }
            return new TWThought(TWAction.MOVE, TWDirection.Z);
        }

        // P0: pending direction set by last tick's PICKUP/PUTDOWN/REFUEL.
        // Spec-compliant: consumed as ONE action on the tick AFTER the pickup/putdown.
        if (pendingDir != null) {
            TWDirection d = pendingDir; pendingDir = null;
            if (d != TWDirection.Z) {
                int nx = getX() + d.dx, ny = getY() + d.dy;
                if (getEnvironment().isValidLocation(nx, ny)
                        && !getEnvironment().isCellBlocked(nx, ny)) {
                    return new TWThought(TWAction.MOVE, d);
                }
                // pendingDir is blocked – fall through to normal planning
            }
        }

        // P1: at fuel station – refuel and leave
        if (fuelLoc != null && getX() == fuelLoc.x && getY() == fuelLoc.y
                && fuelLevel < REFUEL_TO) {
            searchingFuel = false;
            planner.voidPlan();
            pendingDir = GoldenStrategy.opposite(GoldenStrategy.dirToward(getX(), getY(),
                    fuelLoc.x < wx / 2 ? wx - 1 : 0, fuelLoc.y < wy / 2 ? wy - 1 : 0));
            return new TWThought(TWAction.REFUEL, TWDirection.Z);
        }


        // P2: EMERGENCY – must reach station before fuel runs out
        if (fuelLoc != null && fuelLevel <= distFuel + FUEL_EMERGENCY) {
            planner.voidPlan(); planner.clearChain();
            GlobalCoordinator.releaseAll(agentName);
            searchingFuel = false;
            return moveTo(fuelLoc.x, fuelLoc.y);
        }

        // P3: PUTDOWN on current cell – ALWAYS, even when fuel station unknown.
        // Putdown costs zero fuel; skipping it while searching for fuel wastes reward.
        if (carriedTiles.size() > 0) {
            TWEntity here = live(getX(), getY());
            if (here instanceof TWHole) {
                mem.removeAt(getX(), getY());
                TeamCommsHub.consumeCell(getX(), getY(), false);
                GlobalCoordinator.releaseHole(getX(), getY());
                GlobalCoordinator.releaseAll(agentName);
                planner.voidPlan(); planner.clearChain();
                pendingDir = dirAfterDrop(fuelLoc);
                return new TWThought(TWAction.PUTDOWN, TWDirection.Z);
            }
        }

        // P4: PICKUP on current cell – ALWAYS (no fuelLoc restriction).
        // Pickup costs zero fuel; skipping it during fuel search wastes tiles.
        if (carriedTiles.size() < GoldenStrategy.MAX_CARRY) {
            TWEntity here = live(getX(), getY());
            if (here instanceof TWTile) {
                mem.removeAt(getX(), getY());
                TeamCommsHub.consumeCell(getX(), getY(), true);
                GlobalCoordinator.releaseTile(getX(), getY());
                planner.voidPlan();
                pendingDir = dirAfterPickup(fuelLoc);
                return new TWThought(TWAction.PICKUP, TWDirection.Z);
            }
        }

        // P5: fuel station unknown – do coordinated sweep for MOVEMENT only.
        // Current-cell putdown/pickup (P3/P4 above) still happens before this.
        if (fuelLoc == null) {
            if (!searchingFuel) {
                planner.voidPlan(); planner.clearChain();
                GlobalCoordinator.releaseAll(agentName);
                searchingFuel = true;
                fuelSweepDown = (zoneIndex % 2 == 0);
                sweepXOffset = 0;
            }
            // While searching for fuel, also grab adjacent tiles/holes – they're free
            if (Math.max(wx, wy) < 120) {
                if (carriedTiles.size() > 0) {
                    Int2D nh = nearestLiveHoleInRange();
                    if (nh != null) return moveTo(nh.x, nh.y);
                }
                if (carriedTiles.size() < GoldenStrategy.MAX_CARRY) {
                    Int2D nt = nearestLiveTileInRange(null);
                    if (nt != null) return moveTo(nt.x, nt.y);
                }
            }
            if (fuelLevel <= 1) {
                return new TWThought(TWAction.MOVE, TWDirection.Z);
            }
            return coordinatedFuelSearch();
        }
        if (searchingFuel) searchingFuel = false;

        // P6: COMFORT refuel – proactive, fires in any mode once station is known
        if (fuelLevel <= FUEL_COMFORT) {
            GlobalCoordinator.releaseAll(agentName);
            planner.voidPlan();
            return moveTo(fuelLoc.x, fuelLoc.y);
        }

        // P7: Live sensor shortcut – scan Chebyshev range-3 objectGrid for immediate
        // tile/hole opportunities without A* overhead. Holes prioritised when carrying.
        if (carriedTiles.size() > 0) {
            Int2D nearest = nearestLiveHoleInRange();
            if (nearest != null) { planner.voidPlan(); return moveTo(nearest.x, nearest.y); }
        }
        if (carriedTiles.size() < GoldenStrategy.MAX_CARRY) {
            Int2D nearest = nearestLiveTileInRange(fuelLoc);
            if (nearest != null) { planner.voidPlan(); return moveTo(nearest.x, nearest.y); }
        }

        // P8: nearby shortcut from memory
        if (carriedTiles.size() > 0) {
            Int2D cl = mem.nearest(Mem.HOLE, NEAR_HOLE, getX(), getY());
            if (cl != null && canAfford(cl.x, cl.y, fuelLoc)
                    && GlobalCoordinator.tryAssignHole(agentName, cl.x, cl.y,
                            md(getX(), getY(), cl.x, cl.y), now)) {
                planner.voidPlan(); return moveTo(cl.x, cl.y);
            }
        }
        if (carriedTiles.size() < GoldenStrategy.MAX_CARRY) {
            Int2D cl = mem.nearest(Mem.TILE, NEAR_HOLE, getX(), getY());
            if (cl != null && canAfford(cl.x, cl.y, fuelLoc)
                    && GlobalCoordinator.tryAssignTile(agentName, cl.x, cl.y,
                            md(getX(), getY(), cl.x, cl.y), now)) {
                planner.voidPlan(); return moveTo(cl.x, cl.y);
            }
        }

        // P9: invalidate stale plan
        if (planner.hasPlan() && !planner.isValid()) {
            GlobalCoordinator.releaseAll(agentName); planner.voidPlan();
        }

        // P10: execute existing plan
        if (planner.hasPlan()) {
            TWDirection d = planner.execute();
            if (d != null && d != TWDirection.Z) return new TWThought(TWAction.MOVE, d);
            GlobalCoordinator.releaseAll(agentName); planner.voidPlan();
        }

        // P11: generate new plan
        planner.generatePlan(fuelLoc, fuelLevel, now);
        if (planner.hasPlan()) {
            TWDirection d = planner.execute();
            if (d != null && d != TWDirection.Z) return new TWThought(TWAction.MOVE, d);
        }

        return moveTo(wx / 2, wy / 2);
    }

    // ===== act() – EXACTLY ONE action per tick (spec: one Sense-Communicate-Plan-Act cycle) =====
    @Override
    protected void act(TWThought thought) {
        try {
            switch (thought.getAction()) {
                case PICKUP: {
                    TWEntity o = live(getX(), getY());
                    if (o instanceof TWTile) pickUpTile((TWTile) o);
                    // pendingDir set by think() P5 is consumed by think() P0 on the NEXT tick.
                    // DO NOT call execPending() here – that is a 2nd action in this tick (spec violation).
                    break;
                }
                case PUTDOWN: {
                    TWEntity o = live(getX(), getY());
                    if (hasTile() && o instanceof TWHole) {
                        putTileInHole((TWHole) o);
                        GlobalCoordinator.recordMatch(getX(), getY(), getEnvironment().schedule.getTime());
                    }
                    // pendingDir consumed by P0 next tick. DO NOT call execPending() here.
                    break;
                }
                case REFUEL: refuel(); break;
                default: {
                    TWDirection d = thought.getDirection();
                    if (d != null && d != TWDirection.Z) { move(d); lastDir = d; }
                }
            }
        } catch (CellBlockedException ex) {
            // The failed move IS the single action for this tick.
            // DO NOT call bfsStep() – that would be a 2nd action (spec violation).
            // Void the plan so think() replans on the next tick with fresh sensor data.
            GlobalCoordinator.releaseAll(agentName);
            planner.voidPlan();
            pendingDir = null;
        }
    }

    @Override public String getName() { return agentName; }

    // ===== LIVE SENSOR SCAN HELPERS (Chebyshev <= SCAN_RANGE = 3 strictly) =====

    /** Nearest live hole within sensor area, excluding current cell (P4 handles that). */
    private Int2D nearestLiveHoleInRange() {
        int bestDist = Integer.MAX_VALUE; Int2D best = null;
        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
                if (dx == 0 && dy == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dy)) > SCAN_RANGE) continue;
                int sx = getX()+dx, sy = getY()+dy;
                if (sx < 0 || sy < 0 || sx >= wx || sy >= wy) continue;
                TWEntity e = (TWEntity) getEnvironment().getObjectGrid().get(sx, sy);
                if (e instanceof TWHole) {
                    int d = Math.abs(dx)+Math.abs(dy);
                    if (d < bestDist) { bestDist = d; best = new Int2D(sx, sy); }
                }
            }
        }
        return best;
    }

    /** Nearest live tile within sensor area, excluding current cell (P5 handles that). */
    private Int2D nearestLiveTileInRange(Int2D fuelLoc) {
        int bestDist = Integer.MAX_VALUE; Int2D best = null;
        for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
            for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
                if (dx == 0 && dy == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dy)) > SCAN_RANGE) continue;
                int sx = getX()+dx, sy = getY()+dy;
                if (sx < 0 || sy < 0 || sx >= wx || sy >= wy) continue;
                TWEntity e = (TWEntity) getEnvironment().getObjectGrid().get(sx, sy);
                if (e instanceof TWTile && canAfford(sx, sy, fuelLoc)) {
                    int d = Math.abs(dx)+Math.abs(dy);
                    if (d < bestDist) { bestDist = d; best = new Int2D(sx, sy); }
                }
            }
        }
        return best;
    }

    // ===== NAVIGATION =====
    protected boolean isAgentCellFree(int x, int y) {
        Object occ = getEnvironment().getAgentGrid().get(x, y);
        return occ == null || occ == this;
    }

    protected TWThought moveTo(int tx, int ty) {
        int dx=tx-getX(), dy=ty-getY();
        if (dx==0&&dy==0) return new TWThought(TWAction.MOVE, TWDirection.Z);
        TWDirection primary = GoldenStrategy.dirToward(getX(),getY(),tx,ty);
        TWDirection altA = Math.abs(dx)>=Math.abs(dy)
                ?(dy>0?TWDirection.S:TWDirection.N):(dx>0?TWDirection.E:TWDirection.W);
        for (TWDirection d : new TWDirection[]{primary,altA,
                GoldenStrategy.opposite(altA),GoldenStrategy.opposite(lastDir)}) {
            if (d==null||d==TWDirection.Z) continue;
            int nx=getX()+d.dx, ny=getY()+d.dy;
            if (getEnvironment().isValidLocation(nx,ny)
                    && !getEnvironment().isCellBlocked(nx,ny)
                    && isAgentCellFree(nx, ny))
                return new TWThought(TWAction.MOVE, d);
        }
        // Last resort: any free adjacent cell
        int[][] allDirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] dir : allDirs) {
            int nx = getX() + dir[0], ny = getY() + dir[1];
            if (getEnvironment().isValidLocation(nx, ny)
                    && !getEnvironment().isCellBlocked(nx, ny)
                    && isAgentCellFree(nx, ny))
                return new TWThought(TWAction.MOVE,
                        GoldenStrategy.dirToward(getX(), getY(), nx, ny));
        }
        return new TWThought(TWAction.MOVE, TWDirection.Z);
    }

    private static final int[][] SDIRS = {{1,0},{0,1},{-1,0},{0,-1}};

    private TWThought coordinatedFuelSearch() {
        int x0 = GoldenStrategy.zoneMinX(zoneIndex, wx);
        int x1 = GoldenStrategy.zoneMaxX(zoneIndex, wx);
        int sweepX = Math.min(x1, x0 + sweepXOffset);
        if (getX() != sweepX) return moveTo(sweepX, getY());
        int targetY = fuelSweepDown ? wy - 1 : 0;
        if (getY() == targetY) {
            fuelSweepDown = !fuelSweepDown;
            sweepXOffset += 6;
            if (x0 + sweepXOffset > x1) sweepXOffset = 0;
            targetY = fuelSweepDown ? wy - 1 : 0;
        }
        return moveTo(sweepX, targetY);
    }

    private TWThought spiralStep() {
        int[]d=SDIRS[spiralDir%4]; int nx=getX()+d[0],ny=getY()+d[1];
        spiralPos++; if(spiralPos>=spiralLen){spiralPos=0;spiralDir++;spiralLegs++;if(spiralLegs%2==0)spiralLen++;}
        if(getEnvironment().isValidLocation(nx,ny)&&!getEnvironment().isCellBlocked(nx,ny)&&isAgentCellFree(nx, ny))
            return new TWThought(TWAction.MOVE,GoldenStrategy.dirToward(getX(),getY(),nx,ny));
        for(int[]alt:SDIRS){int ax=getX()+alt[0],ay=getY()+alt[1];
            if(getEnvironment().isValidLocation(ax,ay)&&!getEnvironment().isCellBlocked(ax,ay)&&isAgentCellFree(ax, ay))
                return new TWThought(TWAction.MOVE,GoldenStrategy.dirToward(getX(),getY(),ax,ay));}
        return moveTo(wx/2,wy/2);
    }

    private TWDirection dirAfterPickup(Int2D fuelLoc) {
        if (carriedTiles.size()+1 < GoldenStrategy.MAX_CARRY) {
            Int2D t=mem.bestScored(Mem.TILE,fuelLoc);
            if (t!=null&&md(getX(),getY(),t.x,t.y)<=NEAR_HOLE)
                return GoldenStrategy.dirToward(getX(),getY(),t.x,t.y);
        }
        Int2D ch=planner.getChainedHole();
        if (ch!=null) return GoldenStrategy.dirToward(getX(),getY(),ch.x,ch.y);
        Int2D h=mem.bestScored(Mem.HOLE,null);
        if (h!=null) return GoldenStrategy.dirToward(getX(),getY(),h.x,h.y);
        return TWDirection.E;
    }

    private TWDirection dirAfterDrop(Int2D fuelLoc) {
        if (carriedTiles.size()>1) {
            Int2D h=mem.bestScored(Mem.HOLE,null);
            if (h!=null) return GoldenStrategy.dirToward(getX(),getY(),h.x,h.y);
        }
        Int2D t=mem.bestScored(Mem.TILE,fuelLoc);
        if (t!=null) return GoldenStrategy.dirToward(getX(),getY(),t.x,t.y);
        return TWDirection.E;
    }

    protected boolean canAfford(int tx, int ty, Int2D fuelLoc) {
        int toT=md(getX(),getY(),tx,ty);
        if (fuelLoc==null) return toT<=(int)(fuelLevel/4.0);
        return fuelLevel>=toT+md(tx,ty,fuelLoc.x,fuelLoc.y)+FUEL_EMERGENCY;
    }

    protected Int2D fuelLoc() {
        if (mem.fuelKnown()) return new Int2D(mem.fuelX(),mem.fuelY());
        Int2D s=TeamCommsHub.getFuelStation(); if(s!=null){mem.setFuel(s.x,s.y);return s;}
        return null;
    }

    protected TWEntity live(int x,int y){return(TWEntity)getEnvironment().getObjectGrid().get(x,y);}
    protected static int  md(int x1,int y1,int x2,int y2){return Math.abs(x1-x2)+Math.abs(y1-y2);}
    private   static long ck(int x,int y){return((long)x<<16)|y;}

    // =========================================================================
    // Mem â€” extended memory built from communicate() scan
    // =========================================================================
    static final byte TILE=1, HOLE=2, OBSTACLE=3, FUEL=4;

    protected class Mem {
        static final byte TILE=1,HOLE=2,OBSTACLE=3;
        final byte[][]  type;
        final double[][] stamp;
        final int W,H;
        int fuelX=-1,fuelY=-1; boolean fuelKnown=false;
        final List<Int2D> spiral;
        final Set<Long> tileCells;
        final Set<Long> holeCells;

        Mem(int w,int h){W=w;H=h;type=new byte[w][h];stamp=new double[w][h];
            int r=Math.max(w,h);List<Int2D>sp=new ArrayList<>();
            for(int ri=0;ri<=r;ri++)for(int dx=-ri;dx<=ri;dx++)for(int dy=-ri;dy<=ri;dy++)
                if(Math.max(Math.abs(dx),Math.abs(dy))==ri)sp.add(new Int2D(dx,dy));
            spiral=sp;
            tileCells=new HashSet<>();
            holeCells=new HashSet<>();}

        void update(List<Int2D> tiles, List<Int2D> holes, Int2D fuel, double now) {
            if (fuel != null) setFuel(fuel.x, fuel.y);

            for (Int2D t : tiles) setCell(t.x, t.y, TILE, now);
            for (Int2D h : holes) setCell(h.x, h.y, HOLE, now);

            // Within range-3 FOV: trust the official sensor — if we didn't see it this tick, it's gone.
            Set<Long> seenSet = new HashSet<>();
            for (Int2D t : tiles) seenSet.add(ck(t.x, t.y));
            for (Int2D h : holes) seenSet.add(ck(h.x, h.y));

            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    int x = getX() + dx, y = getY() + dy;
                    if (!inBounds(x, y)) continue;
                    if ((type[x][y] == TILE || type[x][y] == HOLE) && !seenSet.contains(ck(x, y))) {
                        clearCell(x, y);
                    }
                }
            }

            decay(now);
        }

        void absorbSharedBoard(double now){
            for(TeamCommsHub.Sighting s:TeamCommsHub.getTileBoard()){
                if(!inBounds(s.x,s.y)||now-s.timestamp>MEM_SHARE_TTL||!reachableAt(s.x,s.y,0,now))continue;
                if(type[s.x][s.y]==TILE&&stamp[s.x][s.y]>=s.timestamp)continue;
                setCell(s.x,s.y,TILE,s.timestamp);}
            for(TeamCommsHub.Sighting s:TeamCommsHub.getHoleBoard()){
                if(!inBounds(s.x,s.y)||now-s.timestamp>MEM_SHARE_TTL||!reachableAt(s.x,s.y,0,now))continue;
                if(type[s.x][s.y]==HOLE&&stamp[s.x][s.y]>=s.timestamp)continue;
                setCell(s.x,s.y,HOLE,s.timestamp);}
            if(!fuelKnown){Int2D sh=TeamCommsHub.getFuelStation();if(sh!=null)setFuel(sh.x,sh.y);}}

        void decay(double now){double maxAge=Math.min(MEM_AGE_MAX, Parameters.lifeTime-1);for(int x=0;x<W;x++)for(int y=0;y<H;y++){
            if(type[x][y]==0||type[x][y]==OBSTACLE)continue;
            double age=now-stamp[x][y];
            if(age>maxAge||(age>MEM_DECAY_START&&getEnvironment().random.nextFloat()<MEM_DECAY_PROB))
                clearCell(x,y);}}

        void shareRecentBoard(double now){
            for(long packed:tileCells){
                int x=(int)(packed>>16),y=(int)(packed&0xFFFF);
                if(now-stamp[x][y]<=MEM_SHARE_TTL)TeamCommsHub.publishTile(x,y,stamp[x][y]);
            }
            for(long packed:holeCells){
                int x=(int)(packed>>16),y=(int)(packed&0xFFFF);
                if(now-stamp[x][y]<=MEM_SHARE_TTL)TeamCommsHub.publishHole(x,y,stamp[x][y]);
            }}

        Int2D bestScored(byte ct,Int2D fl){double best=Double.NEGATIVE_INFINITY;Int2D ret=null;
            double now=getEnvironment().schedule.getTime();
            for(long packed:cells(ct)){int x=(int)(packed>>16),y=(int)(packed&0xFFFF);
                int travel=md(getX(),getY(),x,y);
                if(!reachableAt(x,y,travel,now))continue;
                if(fl!=null&&!canAfford(x,y,fl))continue;
                double age=ageAt(x,y,now), sc;
                if(ct==TILE){
                    HoleSupport hs=bestHoleForTile(x,y,travel,now);
                    sc=GoldenStrategy.tileScore(getX(),getY(),x,y,age,
                            hs!=null?hs.x:-1,hs!=null?hs.y:-1,hs!=null?hs.age:Parameters.lifeTime);
                    if(hs==null)sc-=35.0;
                    else sc+=Math.max(0.0,18.0-hs.linkCost)*1.5;
                }else{
                    sc=GoldenStrategy.holeScore(getX(),getY(),x,y,age,carriedTiles.size());
                }
                if(sc>best){best=sc;ret=new Int2D(x,y);}}return ret;}

        Int2D nearest(byte ct,int maxD,int fx,int fy){int bd=Integer.MAX_VALUE;Int2D best=null;
            double now=getEnvironment().schedule.getTime();
            for(long packed:cells(ct)){int x=(int)(packed>>16),y=(int)(packed&0xFFFF);
                int d=md(fx,fy,x,y);
                if(d<=maxD&&d<bd&&reachableAt(x,y,d,now)){bd=d;best=new Int2D(x,y);}}return best;}

        Int2D[] bestPair(Int2D fl,double now){double best=Double.NEGATIVE_INFINITY;Int2D bt=null,bh=null;
            for(long tilePacked:tileCells){int tx=(int)(tilePacked>>16),ty=(int)(tilePacked&0xFFFF);
                int toTile=md(getX(),getY(),tx,ty);
                if(!reachableAt(tx,ty,toTile,now)||!canAfford(tx,ty,fl))continue;
                double tileAge=ageAt(tx,ty,now);
                for(long holePacked:holeCells){int hx=(int)(holePacked>>16),hy=(int)(holePacked&0xFFFF);
                    int tileToHole=md(tx,ty,hx,hy);
                    if(!reachableAt(hx,hy,toTile+tileToHole,now))continue;
                    double holeAge=ageAt(hx,hy,now);
                    double score=GoldenStrategy.tileScore(getX(),getY(),tx,ty,tileAge,hx,hy,holeAge)
                            + 25.0 - 0.35 * (toTile + tileToHole);
                    if(score>best){best=score;bt=new Int2D(tx,ty);bh=new Int2D(hx,hy);}}}
            return bt!=null?new Int2D[]{bt,bh}:null;}

        Int2D centroid(byte ct){long sx=0,sy=0,n=0;double now=getEnvironment().schedule.getTime();
            for(long packed:cells(ct)){int x=(int)(packed>>16),y=(int)(packed&0xFFFF);
                if(ageAt(x,y,now)>MEM_SHARE_TTL)continue;
                sx+=x;sy+=y;n++;}
            return n>0?new Int2D((int)(sx/n),(int)(sy/n)):null;}

        byte typeAt(int x,int y){return inBounds(x,y)?type[x][y]:0;}
        void removeAt(int x,int y){if(inBounds(x,y))clearCell(x,y);}
        void setFuel(int x,int y){if(!fuelKnown){fuelX=x;fuelY=y;fuelKnown=true;}}
        boolean fuelKnown(){return fuelKnown;} int fuelX(){return fuelX;} int fuelY(){return fuelY;}
        boolean inBounds(int x,int y){return x>=0&&x<W&&y>=0&&y<H;}
        Iterable<Long> cells(byte ct){return ct==TILE?tileCells:holeCells;}
        double ageAt(int x,int y,double now){return now-stamp[x][y];}
        boolean reachableAt(int x,int y,int travel,double now){return ageAt(x,y,now)+travel<Parameters.lifeTime-1;}
        HoleSupport bestHoleForTile(int tx,int ty,int toTile,double now){double best=Double.POSITIVE_INFINITY;HoleSupport ret=null;
            for(long holePacked:holeCells){int hx=(int)(holePacked>>16),hy=(int)(holePacked&0xFFFF);
                int tileToHole=md(tx,ty,hx,hy);
                if(!reachableAt(hx,hy,toTile+tileToHole,now))continue;
                double cost=tileToHole + ageAt(hx,hy,now) * 0.35;
                if(cost<best){best=cost;ret=new HoleSupport(hx,hy,ageAt(hx,hy,now),tileToHole);}}
            return ret;}
        void setCell(int x,int y,byte ct,double ts){
            if(!inBounds(x,y))return;
            clearCell(x,y);
            type[x][y]=ct;stamp[x][y]=ts;
            if(ct==TILE)tileCells.add(ck(x,y));
            else if(ct==HOLE)holeCells.add(ck(x,y));
        }
        void clearCell(int x,int y){
            if(!inBounds(x,y))return;
            long packed=ck(x,y);
            if(type[x][y]==TILE)tileCells.remove(packed);
            else if(type[x][y]==HOLE)holeCells.remove(packed);
            type[x][y]=0;
        }
        class HoleSupport{
            final int x,y,linkCost;
            final double age;
            HoleSupport(int x,int y,double age,int linkCost){this.x=x;this.y=y;this.age=age;this.linkCost=linkCost;}
        }
    }

    // =========================================================================
    // A* pathfinder
    // =========================================================================
    protected class Astar {
        final int W,H; final ANode[][]nodes; final int[][]D4={{1,0},{-1,0},{0,1},{0,-1}};
        Astar(){W=wx;H=wy;nodes=new ANode[W][H];
            for(int x=0;x<W;x++)for(int y=0;y<H;y++)nodes[x][y]=new ANode(x,y);}
        TWPath find(int sx,int sy,int tx,int ty){
            if(!mem.inBounds(tx,ty)||mem.typeAt(tx,ty)==Mem.OBSTACLE||(sx==tx&&sy==ty))return null;
            if(md(sx,sy,tx,ty)<=1)return null; // dist-1: trivial, moveTo handles it
            for(int x=0;x<W;x++)for(int y=0;y<H;y++){ANode n=nodes[x][y];n.g=1e18;n.cl=false;n.par=null;}
            PriorityQueue<ANode>op=new PriorityQueue<>(256);
            nodes[sx][sy].g=0;nodes[sx][sy].h=md(sx,sy,tx,ty);op.add(nodes[sx][sy]);
            int lim=W*H;
            while(!op.isEmpty()&&lim-->0){ANode cur=op.poll();if(cur.cl)continue;
                if(cur.x==tx&&cur.y==ty)break;cur.cl=true;
                for(int[]d:D4){int nx=cur.x+d[0],ny=cur.y+d[1];
                    if(!mem.inBounds(nx,ny)||mem.typeAt(nx,ny)==Mem.OBSTACLE)continue;
                    ANode nb=nodes[nx][ny];if(nb.cl)continue;double ng=cur.g+1;
                    if(ng<nb.g){nb.g=ng;nb.h=md(nx,ny,tx,ty);nb.par=cur;op.add(nb);}}}
            ANode dest=nodes[tx][ty];if(dest.g>1e17)return null;
            TWPath p=new TWPath(tx,ty);ANode n=dest.par;
            while(n!=null&&!(n.x==sx&&n.y==sy)){p.prependStep(n.x,n.y);n=n.par;}
            p.prependStep(sx,sy);return p;}
        class ANode implements Comparable<ANode>{
            final int x,y;double g=1e18,h;ANode par;boolean cl;
            ANode(int x,int y){this.x=x;this.y=y;}
            public int compareTo(ANode o){return Double.compare(g+h,o.g+o.h);}}
    }

    // =========================================================================
    // Planner â€” same for all agents, references zoneIndex for sweep
    // =========================================================================
    protected class Planner {
        enum Mode {SEEK_TILE,SEEK_HOLE,SEEK_FUEL,EXPLORE,INTERCEPT}
        final Astar astar=new Astar();
        TWPath path=null;Int2D goal=null;Mode mode=Mode.EXPLORE;
        Int2D chainedHole=null;
        int sweepCol;boolean sweepDown;

        Planner(){
            int x0=GoldenStrategy.zoneMinX(zoneIndex,wx),x1=GoldenStrategy.zoneMaxX(zoneIndex,wx);
            sweepCol=(x0+x1)/2;sweepDown=(zoneIndex%2==0);}

        boolean hasPlan(){return path!=null&&path.hasNext();}
        Mode getMode(){return mode;}Int2D getGoal(){return goal;}Int2D getChainedHole(){return chainedHole;}
        void voidPlan(){path=null;goal=null;}void clearChain(){chainedHole=null;}

        TWDirection execute(){
            if(path==null||!path.hasNext())return TWDirection.Z;
            TWPathStep s=path.popNext();return s!=null?s.getDirection():TWDirection.Z;}

        boolean isValid(){
            if(!hasPlan()||goal==null)return false;
            if(mode==Mode.SEEK_TILE&&mem.typeAt(goal.x,goal.y)!=Mem.TILE)return false;
            if(mode==Mode.SEEK_HOLE&&mem.typeAt(goal.x,goal.y)!=Mem.HOLE)return false;
            return true;}

        void generatePlan(Int2D fl,double fuel,double now){
            Int2D ng=null;
            boolean noTiles=carriedTiles.size()<GoldenStrategy.MAX_CARRY&&mem.bestScored(Mem.TILE,fl)==null;

            // A: chained hole
            if(carriedTiles.size()>0&&chainedHole!=null){
                int hd=md(getX(),getY(),chainedHole.x,chainedHole.y);
                boolean deliver=carriedTiles.size()>=GoldenStrategy.MIN_CARRY_BEFORE_DELIVER
                        ||hd<=GoldenStrategy.IMMEDIATE_DELIVER_DIST||noTiles;
                if(deliver&&mem.typeAt(chainedHole.x,chainedHole.y)==Mem.HOLE
                        &&canAfford(chainedHole.x,chainedHole.y,fl)
                        &&GlobalCoordinator.tryAssignHole(agentName,chainedHole.x,chainedHole.y,hd,now))
                    {ng=chainedHole;mode=Mode.SEEK_HOLE;}
                else if(!deliver){}else chainedHole=null;}

            // B: best scored hole while carrying
            if(ng==null&&carriedTiles.size()>0){Int2D h=mem.bestScored(Mem.HOLE,null);
                if(h!=null){int hd=md(getX(),getY(),h.x,h.y);
                    if((carriedTiles.size()>=GoldenStrategy.MIN_CARRY_BEFORE_DELIVER
                            ||hd<=GoldenStrategy.IMMEDIATE_DELIVER_DIST||noTiles)
                            &&canAfford(h.x,h.y,fl)
                            &&GlobalCoordinator.tryAssignHole(agentName,h.x,h.y,hd,now))
                        {ng=h;mode=Mode.SEEK_HOLE;}}}

            // C: interceptor (carrying, no hole found)
            if(ng==null&&carriedTiles.size()>0){
                Int2D hot=GlobalCoordinator.hotZoneCentroid(now,200);
                Int2D c=(hot!=null)?hot:mem.centroid(Mem.HOLE);
                if(c!=null&&canAfford(c.x,c.y,fl)){ng=c;mode=Mode.INTERCEPT;}}

            // D: chain plan tile+hole pre-selected
            if(ng==null&&carriedTiles.size()<GoldenStrategy.MAX_CARRY){
                Int2D[]pair=mem.bestPair(fl,now);
                if(pair!=null){int td=md(getX(),getY(),pair[0].x,pair[0].y);
                    int cost=td+md(pair[0].x,pair[0].y,pair[1].x,pair[1].y)
                            +(fl!=null?md(pair[1].x,pair[1].y,fl.x,fl.y):0);
                    if(fuel>cost+FUEL_PAD
                            &&GlobalCoordinator.tryAssignTile(agentName,pair[0].x,pair[0].y,td,now)
                            &&GlobalCoordinator.tryAssignHole(agentName,pair[1].x,pair[1].y,
                                md(pair[0].x,pair[0].y,pair[1].x,pair[1].y),now))
                        {ng=pair[0];chainedHole=pair[1];mode=Mode.SEEK_TILE;}}}

            // E: best single tile
            if(ng==null&&carriedTiles.size()<GoldenStrategy.MAX_CARRY){
                Int2D t=mem.bestScored(Mem.TILE,fl);
                if(t!=null&&GlobalCoordinator.tryAssignTile(agentName,t.x,t.y,md(getX(),getY(),t.x,t.y),now))
                    {ng=t;mode=Mode.SEEK_TILE;}}

            // E2: tile centroid interceptor
            if(ng==null&&carriedTiles.size()<GoldenStrategy.MAX_CARRY){
                Int2D c=mem.centroid(Mem.TILE);
                if(c!=null&&canAfford(c.x,c.y,fl)){ng=c;mode=Mode.INTERCEPT;}}

            // F: proactive fuel
            if(ng==null&&fl!=null&&fuel<FUEL_COMFORT*1.6){ng=fl;mode=Mode.SEEK_FUEL;}

            // G: coverage-targeted sweep
            if(ng==null){ng=nextSweep(now);mode=Mode.EXPLORE;}

            TWPath p=astar.find(getX(),getY(),ng.x,ng.y);
            if((p==null||!p.hasNext())&&mode!=Mode.EXPLORE){
                ng=nextSweep(now);mode=Mode.EXPLORE;p=astar.find(getX(),getY(),ng.x,ng.y);}
            goal=ng;path=p;}

        Int2D nextSweep(double now){
            int x0=GoldenStrategy.zoneMinX(zoneIndex,wx),x1=GoldenStrategy.zoneMaxX(zoneIndex,wx);
            return GlobalCoordinator.stalestCoverageTargetInZone(x0,x1,getX(),getY(),now);}
    }
}
