package tileworld.agent;
import tileworld.environment.TWEnvironment;

/**
 * LakshmiAgent — Zone 0 (leftmost column strip).
 * Role: Scout-Collector. Resets shared coordination state at run start.
 * Aggressive fuel thresholds (zoneIndex=0, non-conservative).
 * Spec: extends TWAgent via BaseAgent. sense() not overridden.
 */
public class LakshmiAgent extends BaseAgent {
    public LakshmiAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, 0, xpos, ypos, env, fuelLevel);
    }
    @Override public String getName() { return agentName; }
}
