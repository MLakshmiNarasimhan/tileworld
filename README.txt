Tileworld — LakshmiAgent Team Submission
=========================================

HOW TO RUN
----------

OPTION 1 — Headless (scoring mode, no display):
  java -cp bin tileworld.TileworldMain

  Runs 10 seeds and prints individual + average reward.

OPTION 2 — GUI (visual mode):
  java -cp "bin;mason.jar" tileworld.TWGUI          (Windows)
  java -cp "bin:mason.jar" tileworld.TWGUI          (Mac/Linux)

  The mason.jar bundled in this folder contains the MASON simulation
  library classes. If Eclipse complains about the old classpath, 
  right-click the project → Build Path → Add JARs → select mason.jar.

OPTION 3 — Eclipse:
  1. Import project into Eclipse (File → Import → Existing Projects)
  2. If "Controller" error appears: right-click project → Build Path
     → Configure Build Path → Libraries tab → remove the old MASON_14.jar
     entry → Add JARs → select mason.jar from the project root
  3. Run tileworld.TWGUI or tileworld.TileworldMain

FILES ADDED / CHANGED vs ORIGINAL TILEWORLD
--------------------------------------------
NEW:      src/tileworld/agent/LakshmiAgent.java       — main agent
NEW:      src/tileworld/agent/GlobalCoordinator.java  — coverage + task assignment
NEW:      src/tileworld/agent/SharedStrategy.java     — shared utility constants
NEW:      src/tileworld/agent/TeamCommsHub.java        — shared comms board
MODIFIED: src/tileworld/environment/TWEnvironment.java — agent spawn only
BUNDLED:  mason.jar                                   — MASON library (fixes error)

All other files (TWAgent, TWAgentSensor, environment/*, planners/*) 
are IDENTICAL to the original Tileworld.zip provided.
