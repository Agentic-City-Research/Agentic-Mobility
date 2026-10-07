import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.agentic.CarScenario;
import org.matsim.agentic.RouteCandidates;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

/** Read-only scenario checks, with an optional small baseline execution smoke test. */
public class ValidateMorningScenario {
    public static void main(String[] args) {
        var config = ConfigUtils.loadConfig(args[0]);
        var scenario = ScenarioUtils.loadScenario(config);
        var candidates = new RouteCandidates(scenario.getNetwork());
        var links = new CarScenario(scenario, candidates);
        int checked = 0;
        var purposes = new java.util.TreeMap<String, Integer>();
        for (var person : scenario.getPopulation().getPersons().values()) {
            var plan = person.getSelectedPlan();
            if (plan == null || plan.getPlanElements().size() != 3
                    || !(plan.getPlanElements().get(0) instanceof Activity origin)
                    || !(plan.getPlanElements().get(1) instanceof Leg leg)
                    || !(plan.getPlanElements().get(2) instanceof Activity destination)
                    || !"car".equals(leg.getMode())
                    || !origin.getEndTime().isDefined() || destination.getEndTime().isDefined()) {
                throw new IllegalStateException("Invalid single-trip plan: " + person.getId());
            }
            var from = links.resolveLink(origin);
            var to = links.resolveLink(destination);
            if (from == null || to == null || !candidates.isReachable(from, to)
                    || candidates.compute(from, to).isEmpty()) {
                throw new IllegalStateException("Unreachable car OD: " + person.getId());
            }
            purposes.merge(origin.getType() + "->" + destination.getType(), 1, Integer::sum);
            checked++;
        }
        System.out.println("VALIDATED single-trip car persons=" + checked + " purposes=" + purposes);
        if (args.length > 1) {
            // Explicit optional smoke output path; retain 300 persons in memory only.
            var ids = new java.util.ArrayList<>(scenario.getPopulation().getPersons().keySet());
            ids.sort(java.util.Comparator.comparing(Object::toString));
            for (int i = 300; i < ids.size(); i++) scenario.getPopulation().removePerson(ids.get(i));
            config.controller().setOutputDirectory(args[1]);
            config.controller().setLastIteration(0);
            new Controler(scenario).run();
            System.out.println("SMOKE COMPLETE persons=" + scenario.getPopulation().getPersons().size());
        }
    }
}
