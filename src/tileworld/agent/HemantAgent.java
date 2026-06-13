package tileworld.agent;
import tileworld.environment.TWEnvironment;

/**
 * HemantAgent — Zone 3 (middle-right column strip).
 * Role: Hot-Zone Interceptor. Conservative fuel (odd zone).
 * Biases toward the GlobalCoordinator hot-zone centroid when idle.
 * Spec: extends TWAgent via BaseAgent. sense() not overridden.
 */
public class HemantAgent extends BaseAgent {
    public HemantAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, 3, xpos, ypos, env, fuelLevel);
    }
    @Override public String getName() { return agentName; }
}
