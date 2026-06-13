package tileworld.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import tileworld.environment.TWDirection;
import tileworld.environment.TWEnvironment;
import tileworld.environment.TWFuelStation;
import tileworld.environment.TWHole;
import tileworld.environment.TWTile;
import tileworld.exceptions.CellBlockedException;
import tileworld.planners.AstarPathGenerator;
import tileworld.planners.TWPath;

/**
 * Spec-safe Tileworld agent.
 *
 * Keeps the same overall strategy as the previous version:
 * - local sensing only (Chebyshev distance <= 3)
 * - memory-backed planning
 * - message broadcast via TWEnvironment.receiveMessage(...)
 * - up to 3 carried tiles
 * - strong fuel-safety and patrol exploration
 *
 * Main compliance fix in this version:
 * - a blocked move no longer triggers a second move in the same act cycle
 */
public class SimpleTWAgent extends TWAgent {

    private static final int SENSOR_RANGE = 3;
    private static final int TILE_CAPACITY = 3;
    private static final int MEMORY_TTL = 35;
    private static final int LOW_FUEL_BUFFER = 18;
    private static final int EXPLORE_FUEL_BUFFER = 28;
    private static final int REFUEL_TARGET = 490;

    private final String name;
    private final AstarPathGenerator planner;

    private int internalStep = 0;
    private int fuelX = -1;
    private int fuelY = -1;

    private final ArrayList<int[]> patrolPoints = new ArrayList<int[]>();
    private int patrolIndex = 0;
    private int patrolStep = 1;

    private static boolean spawnedPrinted = false;

    private static class Observation {
        String type; // TILE, HOLE, FUEL
        int x;
        int y;
        int lastSeenStep;

        Observation(String type, int x, int y, int lastSeenStep) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.lastSeenStep = lastSeenStep;
        }
    }

    private final Map<String, Observation> memoryMap = new HashMap<String, Observation>();

    public SimpleTWAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(xpos, ypos, env, fuelLevel);
        this.name = name;

        if (!spawnedPrinted) {
            System.out.println("[SPAWNED] SimpleTWAgent");
            spawnedPrinted = true;
        }

        this.planner = new AstarPathGenerator(
                this.getEnvironment(),
                this,
                this.getEnvironment().getxDimension() + this.getEnvironment().getyDimension()
        );

        buildPatrolPoints();
        patrolIndex = getNearestPatrolIndex();
        patrolStep = isSecondAgent() ? -1 : 1;
    }

    @Override
    public void communicate() {
        scanVisibleArea();

        Observation fuelObs = getBestRemembered("FUEL");
        if (fuelObs != null) {
            this.getEnvironment().receiveMessage(
                    new Message(this.name, "all", encodeMessage("FUEL", fuelObs.x, fuelObs.y, fuelObs.lastSeenStep))
            );
        }

        Observation tileObs = getBestRemembered("TILE");
        if (tileObs != null) {
            this.getEnvironment().receiveMessage(
                    new Message(this.name, "all", encodeMessage("TILE", tileObs.x, tileObs.y, tileObs.lastSeenStep))
            );
        }

        Observation holeObs = getBestRemembered("HOLE");
        if (holeObs != null) {
            this.getEnvironment().receiveMessage(
                    new Message(this.name, "all", encodeMessage("HOLE", holeObs.x, holeObs.y, holeObs.lastSeenStep))
            );
        }
    }

    @Override
    protected TWThought think() {
        internalStep++;
        readMessages();
        scanVisibleArea();
        pruneStaleMemory();

        Object realHere = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());

        if (realHere instanceof TWTile && this.carriedTiles.size() < TILE_CAPACITY) {
            return new TWThought(TWAction.PICKUP, TWDirection.Z);
        }

        if (realHere instanceof TWHole && this.carriedTiles.size() > 0) {
            return new TWThought(TWAction.PUTDOWN, TWDirection.Z);
        }

        if (realHere instanceof TWFuelStation && shouldRefuelOnCurrentCell()) {
            return new TWThought(TWAction.REFUEL, TWDirection.Z);
        }

        if (mustHeadToFuelNow()) {
            return moveThought(directionTo(fuelX, fuelY));
        }

        Observation chosenGoal = chooseGoal();
        if (chosenGoal != null) {
            return moveThought(directionTo(chosenGoal.x, chosenGoal.y));
        }

        int[] patrol = getCurrentPatrolTarget();
        if (patrol[0] != this.getX() || patrol[1] != this.getY()) {
            return moveThought(directionTo(patrol[0], patrol[1]));
        }

        return waitThought();
    }

    @Override
    protected void act(TWThought thought) {
        if (thought == null) {
            return;
        }

        switch (thought.getAction()) {
            case PICKUP: {
                Object realHere = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());
                if (realHere instanceof TWTile && this.carriedTiles.size() < TILE_CAPACITY) {
                    TWTile tile = (TWTile) realHere;
                    pickUpTile(tile);
                    forget("TILE", tile.getX(), tile.getY());
                    this.getMemory().removeObject(tile);
                }
                return;
            }

            case PUTDOWN: {
                Object realHere = this.getEnvironment().getObjectGrid().get(this.getX(), this.getY());
                if (realHere instanceof TWHole && this.carriedTiles.size() > 0) {
                    TWHole hole = (TWHole) realHere;
                    putTileInHole(hole);
                    forget("HOLE", hole.getX(), hole.getY());
                    this.getMemory().removeObject(hole);
                }
                return;
            }

            case REFUEL: {
                if (this.getEnvironment().inFuelStation(this)) {
                    this.refuel();
                }
                return;
            }

            default: {
                if (thought.getDirection() == null || thought.getDirection() == TWDirection.Z) {
                    return;
                }

                if (this.getFuelLevel() <= 0) {
                    return;
                }

                try {
                    this.move(thought.getDirection());
                } catch (CellBlockedException ex) {
                    // Stay still. The next sense/communicate cycle updates memory and the
                    // planner replans on the following step. This preserves one-action-per-step behaviour.
                    return;
                }
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    private Observation chooseGoal() {
        Observation bestHole = bestReachableObservation("HOLE");
        Observation bestTile = bestReachableObservation("TILE");

        int carried = this.carriedTiles.size();

        if (carried >= TILE_CAPACITY) {
            return bestHole;
        }

        if (carried > 0) {
            if (bestHole != null && bestTile != null) {
                int distHole = pathLength(this.getX(), this.getY(), bestHole.x, bestHole.y);
                int distTile = pathLength(this.getX(), this.getY(), bestTile.x, bestTile.y);
                if (distTile >= 0 && distHole >= 0 && distTile + 2 < distHole && canSafelyChainTileThenHole(bestTile, bestHole)) {
                    return bestTile;
                }
                return bestHole;
            }
            if (bestHole != null) {
                return bestHole;
            }
            return bestTile;
        }

        return bestTile;
    }

    private Observation bestReachableObservation(String type) {
        Observation best = null;
        int bestScore = Integer.MIN_VALUE;

        Iterator<Observation> it = memoryMap.values().iterator();
        while (it.hasNext()) {
            Observation obs = it.next();
            if (!type.equals(obs.type)) {
                continue;
            }

            if (!isSafeToPursue(obs)) {
                continue;
            }

            int dist = pathLength(this.getX(), this.getY(), obs.x, obs.y);
            if (dist < 0) {
                continue;
            }

            int freshness = Math.max(0, MEMORY_TTL - (internalStep - obs.lastSeenStep));
            int score = freshness - (3 * dist);

            if (best == null || score > bestScore) {
                best = obs;
                bestScore = score;
            }
        }

        return best;
    }

    private boolean isSafeToPursue(Observation obs) {
        int toGoal = pathLength(this.getX(), this.getY(), obs.x, obs.y);
        if (toGoal < 0) {
            return false;
        }

        if (!knowsFuel()) {
            return this.getFuelLevel() > toGoal + EXPLORE_FUEL_BUFFER;
        }

        int goalToFuel = pathLength(obs.x, obs.y, fuelX, fuelY);
        if (goalToFuel < 0) {
            return false;
        }

        return this.getFuelLevel() > toGoal + goalToFuel + LOW_FUEL_BUFFER;
    }

    private boolean canSafelyChainTileThenHole(Observation tile, Observation hole) {
        int toTile = pathLength(this.getX(), this.getY(), tile.x, tile.y);
        int tileToHole = pathLength(tile.x, tile.y, hole.x, hole.y);
        if (toTile < 0 || tileToHole < 0) {
            return false;
        }

        if (!knowsFuel()) {
            return this.getFuelLevel() > toTile + tileToHole + EXPLORE_FUEL_BUFFER;
        }

        int holeToFuel = pathLength(hole.x, hole.y, fuelX, fuelY);
        if (holeToFuel < 0) {
            return false;
        }

        return this.getFuelLevel() > toTile + tileToHole + holeToFuel + LOW_FUEL_BUFFER;
    }

    private boolean mustHeadToFuelNow() {
        if (!knowsFuel()) {
            return false;
        }

        int toFuel = pathLength(this.getX(), this.getY(), fuelX, fuelY);
        if (toFuel < 0) {
            return false;
        }

        return this.getFuelLevel() <= toFuel + LOW_FUEL_BUFFER;
    }

    private boolean shouldRefuelOnCurrentCell() {
        return this.getFuelLevel() < REFUEL_TARGET;
    }

    private void scanVisibleArea() {
        int minX = Math.max(0, this.getX() - SENSOR_RANGE);
        int maxX = Math.min(this.getEnvironment().getxDimension() - 1, this.getX() + SENSOR_RANGE);
        int minY = Math.max(0, this.getY() - SENSOR_RANGE);
        int maxY = Math.min(this.getEnvironment().getyDimension() - 1, this.getY() + SENSOR_RANGE);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (!isInSensorRange(x, y)) {
                    continue;
                }

                Object obj = this.getEnvironment().getObjectGrid().get(x, y);

                if (obj instanceof TWFuelStation) {
                    remember("FUEL", x, y);
                    fuelX = x;
                    fuelY = y;
                } else if (obj instanceof TWTile) {
                    remember("TILE", x, y);
                    forget("HOLE", x, y);
                } else if (obj instanceof TWHole) {
                    remember("HOLE", x, y);
                    forget("TILE", x, y);
                } else {
                    forget("TILE", x, y);
                    forget("HOLE", x, y);
                }
            }
        }
    }

    private void readMessages() {
        for (Message m : this.getEnvironment().getMessages()) {
            if (m == null || m.getFrom() == null || m.getMessage() == null) {
                continue;
            }

            if (this.name.equals(m.getFrom())) {
                continue;
            }

            String[] parts = m.getMessage().trim().split(" ");
            if (parts.length != 5 || !"OBS".equals(parts[0])) {
                continue;
            }

            try {
                String type = parts[1];
                int x = Integer.parseInt(parts[2]);
                int y = Integer.parseInt(parts[3]);
                int seenStep = Integer.parseInt(parts[4]);

                if ("FUEL".equals(type) || "TILE".equals(type) || "HOLE".equals(type)) {
                    remember(type, x, y, seenStep);
                    if ("FUEL".equals(type)) {
                        fuelX = x;
                        fuelY = y;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void pruneStaleMemory() {
        Iterator<Map.Entry<String, Observation>> it = memoryMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Observation> entry = it.next();
            Observation obs = entry.getValue();

            if ("FUEL".equals(obs.type)) {
                continue;
            }

            if (internalStep - obs.lastSeenStep > MEMORY_TTL) {
                it.remove();
            }
        }
    }

    private void remember(String type, int x, int y) {
        remember(type, x, y, internalStep);
    }

    private void remember(String type, int x, int y, int seenStep) {
        String key = makeKey(type, x, y);
        Observation existing = memoryMap.get(key);
        if (existing == null) {
            memoryMap.put(key, new Observation(type, x, y, seenStep));
        } else {
            existing.lastSeenStep = Math.max(existing.lastSeenStep, seenStep);
        }
    }

    private void forget(String type, int x, int y) {
        memoryMap.remove(makeKey(type, x, y));
    }

    private Observation getBestRemembered(String type) {
        Observation best = null;
        int bestDist = Integer.MAX_VALUE;

        Iterator<Observation> it = memoryMap.values().iterator();
        while (it.hasNext()) {
            Observation obs = it.next();
            if (!type.equals(obs.type)) {
                continue;
            }

            int dist = Math.abs(this.getX() - obs.x) + Math.abs(this.getY() - obs.y);
            if (dist < bestDist) {
                best = obs;
                bestDist = dist;
            }
        }

        return best;
    }

    private String makeKey(String type, int x, int y) {
        return type + "@" + x + "," + y;
    }

    private void buildPatrolPoints() {
        patrolPoints.clear();

        int width = this.getEnvironment().getxDimension();
        int height = this.getEnvironment().getyDimension();

        int step = (SENSOR_RANGE * 2) + 1;
        boolean leftToRight = true;

        for (int y = clampY(SENSOR_RANGE); y < height; y += step) {
            if (leftToRight) {
                for (int x = clampX(SENSOR_RANGE); x < width; x += step) {
                    patrolPoints.add(new int[] { clampX(x), clampY(y) });
                }
            } else {
                int lastX = clampX(SENSOR_RANGE);
                for (int x = clampX(SENSOR_RANGE); x < width; x += step) {
                    lastX = x;
                }
                for (int x = lastX; x >= clampX(SENSOR_RANGE); x -= step) {
                    patrolPoints.add(new int[] { clampX(x), clampY(y) });
                }
            }
            leftToRight = !leftToRight;
        }

        if (patrolPoints.isEmpty()) {
            patrolPoints.add(new int[] { clampX(width / 2), clampY(height / 2) });
        }
    }

    private int[] getCurrentPatrolTarget() {
        if (patrolPoints.isEmpty()) {
            return new int[] { this.getX(), this.getY() };
        }

        for (int tries = 0; tries < patrolPoints.size(); tries++) {
            int[] p = patrolPoints.get(patrolIndex);

            if (this.getX() == p[0] && this.getY() == p[1]) {
                advancePatrolIndex();
                continue;
            }

            int travel = pathLength(this.getX(), this.getY(), p[0], p[1]);
            if (travel < 0) {
                advancePatrolIndex();
                continue;
            }

            if (!knowsFuel()) {
                if (this.getFuelLevel() > travel + EXPLORE_FUEL_BUFFER) {
                    return p;
                }
            } else {
                int patrolToFuel = pathLength(p[0], p[1], fuelX, fuelY);
                if (patrolToFuel >= 0 && this.getFuelLevel() > travel + patrolToFuel + LOW_FUEL_BUFFER) {
                    return p;
                }
            }

            advancePatrolIndex();
        }

        if (knowsFuel()) {
            return new int[] { fuelX, fuelY };
        }

        return new int[] { this.getX(), this.getY() };
    }

    private void advancePatrolIndex() {
        if (patrolPoints.isEmpty()) {
            return;
        }

        patrolIndex += patrolStep;
        if (patrolIndex >= patrolPoints.size()) {
            patrolIndex = 0;
        } else if (patrolIndex < 0) {
            patrolIndex = patrolPoints.size() - 1;
        }
    }

    private int getNearestPatrolIndex() {
        if (patrolPoints.isEmpty()) {
            return 0;
        }

        int bestIndex = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < patrolPoints.size(); i++) {
            int[] p = patrolPoints.get(i);
            int d = Math.abs(this.getX() - p[0]) + Math.abs(this.getY() - p[1]);
            if (d < bestDist) {
                bestDist = d;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private TWThought moveThought(TWDirection direction) {
        if (direction == null || direction == TWDirection.Z) {
            return waitThought();
        }
        return new TWThought(TWAction.MOVE, direction);
    }

    private TWThought waitThought() {
        return new TWThought(TWAction.MOVE, TWDirection.Z);
    }

    private TWDirection directionTo(int tx, int ty) {
        TWPath path = planner.findPath(this.getX(), this.getY(), tx, ty);
        if (path == null || path.getpath() == null || path.getpath().size() == 0) {
            return TWDirection.Z;
        }
        return path.getStep(0).getDirection();
    }

    private int pathLength(int sx, int sy, int tx, int ty) {
        TWPath path = planner.findPath(sx, sy, tx, ty);
        if (path == null || path.getpath() == null) {
            return -1;
        }
        return path.getpath().size();
    }

    private boolean isInSensorRange(int x, int y) {
        return Math.max(Math.abs(this.getX() - x), Math.abs(this.getY() - y)) <= SENSOR_RANGE;
    }

    private boolean knowsFuel() {
        return fuelX >= 0 && fuelY >= 0;
    }

    private boolean isSecondAgent() {
        return this.name != null && this.name.toLowerCase().endsWith("2");
    }

    private String encodeMessage(String type, int x, int y, int step) {
        return "OBS " + type + " " + x + " " + y + " " + step;
    }

    private int clampX(int x) {
        if (x < 0) {
            return 0;
        }
        int max = this.getEnvironment().getxDimension() - 1;
        if (x > max) {
            return max;
        }
        return x;
    }

    private int clampY(int y) {
        if (y < 0) {
            return 0;
        }
        int max = this.getEnvironment().getyDimension() - 1;
        if (y > max) {
            return max;
        }
        return y;
    }
}
