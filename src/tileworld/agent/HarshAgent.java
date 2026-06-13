package tileworld.agent;
import tileworld.environment.TWEnvironment;

/**
 * HarshAgent — Zone 5 (rightmost column strip).
 * Role: Safety Anchor. Conservative fuel (odd zone), largest emergency buffer.
 * Ensures the right-side zone is always covered even when fuel management
 * requires frequent station visits.
 * Spec: extends TWAgent via BaseAgent. sense() not overridden.
 */
public class HarshAgent extends BaseAgent {
    public HarshAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, 5, xpos, ypos, env, fuelLevel);
    }
    @Override public String getName() { return agentName; }
}
