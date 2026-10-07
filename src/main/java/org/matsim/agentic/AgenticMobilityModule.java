package org.matsim.agentic;

import org.matsim.core.controler.AbstractModule;

/**
 * Binds the toll-aware car travel disutility so background re-routing sees the
 * current policy (informed) or the person's own observations (uninformed).
 * LLM travelers are frozen by subpopulation and never re-route, so this only
 * affects the background population.
 */
public final class AgenticMobilityModule extends AbstractModule {

    private final TollAwareTravelDisutilityFactory tollAwareFactory;

    public AgenticMobilityModule(TollAwareTravelDisutilityFactory tollAwareFactory) {
        this.tollAwareFactory = tollAwareFactory;
    }

    @Override
    public void install() {
        bindCarTravelDisutilityFactory().toInstance(tollAwareFactory);
    }
}
