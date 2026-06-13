package tileworld.agent;
import tileworld.environment.TWEnvironment;

/**
 * JoleneAgent — Zone 4 (second-from-right column strip).
 * Role: Aggressive Scorer. Non-conservative, maximises tile collection rate.
 * Spec: extends TWAgent via BaseAgent. sense() not overridden.
 */
public class JoleneAgent extends BaseAgent {
    public JoleneAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, 4, xpos, ypos, env, fuelLevel);
    }
    @Override public String getName() { return agentName; }
}
