package tileworld.agent;
import tileworld.environment.TWEnvironment;

/**
 * TharunAgent — Zone 1 (second column strip).
 * Role: Chain-Plan Specialist. Conservative fuel (odd zone index).
 * Prioritises carrying 3 tiles before delivery for maximum throughput.
 * Spec: extends TWAgent via BaseAgent. sense() not overridden.
 */
public class TharunAgent extends BaseAgent {
    public TharunAgent(String name, int xpos, int ypos, TWEnvironment env, double fuelLevel) {
        super(name, 1, xpos, ypos, env, fuelLevel);
    }
    @Override public String getName() { return agentName; }
}
