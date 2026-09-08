package org.matsim.agentic.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.*;
import org.matsim.core.controler.listener.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real QSim + scoring: the same forced route must lose exactly toll * money utility. */
class ManagerIntegrationTest {
    @TempDir Path root;
    record Result(double score,String payments,String policies) {}
    private Result run(String mode) throws Exception {
        var config=ConfigUtils.createConfig();
        config.controller().setOutputDirectory(root.resolve(mode).toString());
        config.controller().setLastIteration(1);
        config.controller().setCreateGraphs(false);
        config.qsim().setEndTime(1000);
        config.scoring().setMarginalUtilityOfMoney(0.5);
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(3600));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(3600));
        var strategy=new ReplanningConfigGroup.StrategySettings();
        strategy.setStrategyName("KeepLastSelected"); strategy.setWeight(1);
        config.replanning().addStrategySettings(strategy);
        var scenario=ScenarioUtils.createScenario(config); var network=scenario.getNetwork();
        for(int i=0;i<4;i++) NetworkUtils.createAndAddNode(network,Id.createNodeId(i),new Coord(i*100,0));
        var names=List.of("origin","tolled","destination");
        for(int i=0;i<3;i++) NetworkUtils.createAndAddLink(network,Id.createLinkId(names.get(i)),
                network.getNodes().get(Id.createNodeId(i)),network.getNodes().get(Id.createNodeId(i+1)),100,10,10000,1);
        NetworkUtils.createAndAddLink(network,Id.createLinkId("return"),network.getNodes().get(Id.createNodeId(3)),
                network.getNodes().get(Id.createNodeId(0)),300,10,10000,1);
        var f=scenario.getPopulation().getFactory(); var person=f.createPerson(Id.createPersonId("p"));
        var plan=f.createPlan(); var home=f.createActivityFromLinkId("home",Id.createLinkId("origin"));
        home.setEndTime(100); plan.addActivity(home);
        var leg=f.createLeg("car");
        var route=RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("origin"),List.of(Id.createLinkId("tolled")),Id.createLinkId("destination"));
        route.setDistance(300); leg.setRoute(route); plan.addLeg(leg);
        plan.addActivity(f.createActivityFromLinkId("work",Id.createLinkId("destination")));
        person.addPlan(plan); scenario.getPopulation().addPerson(person);
        var controler=new Controler(scenario);
        var settings=new ManagerSettings(mode,"fixed",List.of("tolled"),0,1000,1000,
                List.of(0.0,2.0),1,1,0,1,30,10,1,3);
        var manager=ManagerRuntime.configure(controler,settings,ManagerDecisionProvider.fixed());
        if(manager!=null) {
            class Lifecycle implements StartupListener,IterationEndsListener,ShutdownListener {
                public void notifyStartup(StartupEvent e) {manager.start(Path.of(config.controller().getOutputDirectory()));}
                public void notifyIterationEnds(IterationEndsEvent e) {manager.iterationEnded(e.getIteration(),1);}
                public void notifyShutdown(ShutdownEvent e) {manager.close();}
            }
            controler.addControllerListener(new Lifecycle());
        }
        controler.run();
        var out=Path.of(config.controller().getOutputDirectory());
        return new Result(person.getSelectedPlan().getScore(),manager==null?"":Files.readString(out.resolve("toll-payments.csv")),
                manager==null?"":Files.readString(out.resolve("toll-policies.jsonl")));
    }
    @Test void actualScoringAndShadowEquivalence() throws Exception {
        var off=run("off"); var shadow=run("shadow"); var active=run("active");
        assertEquals(off.score(),shadow.score(),1e-9);
        assertEquals(off.score()-1.0,active.score(),1e-9);
        assertEquals(2,active.payments().lines().count(),"header + exactly one payment, in iteration 1");
        assertTrue(active.payments().contains("1,1,\"p\""));
        assertEquals(1,shadow.payments().lines().count());
        assertFalse(Files.exists(root.resolve("off/manager-manifest.json")));
    }
}
