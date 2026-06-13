Patch summary
=============

Changed files:
- src/tileworld/agent/BaseAgent.java
- src/tileworld/agent/GlobalCoordinator.java

What changed:
- Kept strict legal sensing range 3 and one action per tick.
- Replaced column-only exploration freshness with a coarser 2D shared coverage grid.
- Made tile/hole memory scoring lifetime-aware so agents stop chasing objects that cannot still exist by arrival time.
- Preserved fuel sharing, claim coordination, and local stale-cell cleanup.

Verification summary
====================
The current source was tested in temporary benchmark copies where only Parameters.xDimension and Parameters.yDimension were changed for evaluation.

Runs per size: 10 random seeds
Steps per run: 5000

50x50:
- average reward: 426.9
- fuel-death messages observed: 0

80x80:
- average reward: 241.1
- fuel-death messages observed: 0

150x150:
- average reward: 34.9
- fuel-death messages observed: 0

Notes:
- The production project in this folder still keeps Parameters at the original 50x50 size.
- On the tested 10-seed sample, 150x150 no longer produced fuel-runout messages, but its reward is still much lower than 50x50 and 80x80 under the unchanged simulator rules.
