package org.matsim.agentic;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.router.costcalculators.*;
import org.matsim.core.router.util.*;
import org.matsim.vehicles.Vehicle;

/** Native other trips respect the person's announcement visibility too. */
final class MorningTollDisutilityFactory implements TravelDisutilityFactory {
    private final Config config;
    private final MorningTollSystem tolls;
    private final MorningTravelerInformation information;
    MorningTollDisutilityFactory(Config config, MorningTollSystem tolls) { this(config, tolls, null); }
    MorningTollDisutilityFactory(Config config, MorningTollSystem tolls, MorningTravelerInformation information) {
        this.config = config; this.tolls = tolls; this.information = information;
    }
    @Override public TravelDisutility createTravelDisutility(TravelTime travelTime) {
        TravelTime visibleTimes = (link, time, person, vehicle) -> information != null && person != null
                && information.participates(person.getId().toString()) ? MatsimOdCandidates.freeFlow(link)
                : travelTime.getLinkTravelTime(link, time, person, vehicle);
        var base = new RandomizingTimeDistanceTravelDisutilityFactory("car", config).createTravelDisutility(visibleTimes);
        double moneyUtility = config.scoring().getMarginalUtilityOfMoney();
        return new TravelDisutility() {
            @Override public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
                double perceived = information == null || person == null ? tolls.amountAt(link.getId(), time)
                        : information.routingToll(person.getId().toString(), link.getId(), time, tolls);
                return base.getLinkTravelDisutility(link, time, person, vehicle) + moneyUtility * perceived;
            }
            @Override public double getLinkMinimumTravelDisutility(Link link) { return base.getLinkMinimumTravelDisutility(link); }
        };
    }
}
