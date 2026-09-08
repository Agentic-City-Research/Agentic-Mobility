# Congestion pricing knowledge v1

K1. A trip can delay other users. Pricing seeks to change travel choices to account for
that external cost. Personal experienced delay is not automatically marginal external cost.
Source: Vickrey (1969), Congestion Theory and Transport Investment, AER 59(2), 251-260.

K2. Departing earlier or later trades queue time against schedule and activity costs.
Eliminating queues does not by itself establish maximum welfare.
Source: Arnott, de Palma and Lindsey (1993), A Structural Model of Peak-Period Congestion,
AER 83(1), 161-179. Simplified bottleneck assumptions do not fully describe MATSim.

K3. Keep a price table fixed during a traveler-adaptation interval. Excessively strong
adjustment can cause oscillations and activity losses. List prices can be more stable
than individual queue-based charges in an iterative simulation.
Source: Kaddoura and Nagel (2019), sections 2.2.2 and 5,
https://doi.org/10.1016/j.retrec.2019.01.002 . No numerical gains or calibrated prices transferred.

K4. Local improvements can shift congestion elsewhere. Inspect network-level delay and
previous policy outcomes. Sato et al. used shared rewards and low-wait switching.
Source: Sato, Seo and Fuse (2022), sections IV-V, https://arxiv.org/abs/2206.12188 .
Their point-queue model is not MATSim. Do not copy its road IDs or fitted parameters.

K5. Population averages can hide losses. Revenue and travel time are distinct quantities.
Source: He et al. (2021), section 5, https://doi.org/10.1016/j.tranpol.2020.12.011 .
This manager receives no income or satisfaction evidence; do not claim distributional fairness.

Experimental conventions (not literature findings): discrete price levels, candidates,
window sizes, thresholds and update cadence are researcher settings in the supplied permissions.
Unknown delay is null, not zero. Snapshot observations are descriptive, not causal effects.
