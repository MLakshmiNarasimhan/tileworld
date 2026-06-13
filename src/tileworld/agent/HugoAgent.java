package tileworld.agent;
import tileworld.environment.TWEnvironment;

/**
 * HugoAgent — Zone 2 (middle-left column strip).
 * Role: Balanced Collector. Non-conservative, steady sweeper.
 * Spec: extends TWAgent via BaseAgent. sense() not overridden.
 */
public class HugoAgent extends BaseAgent {
    public HugoAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, 2, xpos, ypos, env, fuelLevel);
    }
    @Override public String getName() { return agentName; }
}
