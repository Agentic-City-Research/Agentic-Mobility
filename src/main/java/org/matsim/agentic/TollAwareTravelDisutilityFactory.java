package org.matsim.agentic;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Wraps the standard car travel disutility and adds the toll the person
 * perceives for the link: current policy for informed background, personal
 * observation for uninformed background, and zero for LLM travelers (who choose
 * from static candidates rather than re-routing).
 */
public final class TollAwareTravelDisutilityFactory implements TravelDisutilityFactory {

    private final Config config;
    private final TollSystem tollSystem;

    public TollAwareTravelDisutilityFactory(Config config, TollSystem tollSystem) {
        this.config = config;
        this.tollSystem = tollSystem;
    }

    @Override
    public TravelDisutility createTravelDisutility(TravelTime travelTime) {
        TravelDisutility base = new RandomizingTimeDistanceTravelDisutilityFactory("car", config)
                .createTravelDisutility(travelTime);
        double marginalUtilityOfMoney = config.scoring().getMarginalUtilityOfMoney();
        return new TollAwareTravelDisutility(base, tollSystem, marginalUtilityOfMoney);
    }

    private static final class TollAwareTravelDisutility implements TravelDisutility {
        private final TravelDisutility base;
        private final TollSystem tollSystem;
        private final double marginalUtilityOfMoney;

        TollAwareTravelDisutility(TravelDisutility base, TollSystem tollSystem, double marginalUtilityOfMoney) {
            this.base = base;
            this.tollSystem = tollSystem;
            this.marginalUtilityOfMoney = marginalUtilityOfMoney;
        }

        @Override
        public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
            double baseCost = base.getLinkTravelDisutility(link, time, person, vehicle);
            double toll = tollSystem.routingToll(person.getId(), link.getId(), time);
            return baseCost + toll * marginalUtilityOfMoney;
        }

        @Override
        public double getLinkMinimumTravelDisutility(Link link) {
            return base.getLinkMinimumTravelDisutility(link);
        }
    }
}
