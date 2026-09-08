package org.matsim.agentic.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import java.nio.file.Path;
import java.util.*;

/** Versioned experiment permissions, never supplied or expanded by the model. */
public record ManagerSettings(String mode, String provider, List<String> candidateLinks,
        int startTimeSeconds, int endTimeSeconds, int timeBinSeconds,
        List<Double> priceLevels, int warmupIterations, int updateInterval,
        int freezeLastIterations, int minimumObservations, double raiseDelaySeconds,
        double lowerDelaySeconds, int fixedLevel, int memoryCycles) {
    public static ManagerSettings load(Path path) throws java.io.IOException {
        return new ObjectMapper().readValue(path.toFile(), ManagerSettings.class);
    }
    public ManagerSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(provider);
        candidateLinks = List.copyOf(candidateLinks);
        priceLevels = List.copyOf(priceLevels);
        if (!Set.of("off", "shadow", "active").contains(mode)
                || !Set.of("fixed", "rule", "llm").contains(provider))
            throw new IllegalArgumentException("Invalid manager mode/provider");
        if (startTimeSeconds < 0 || endTimeSeconds <= startTimeSeconds || timeBinSeconds <= 0
                || (endTimeSeconds-startTimeSeconds)%timeBinSeconds != 0
                || warmupIterations < 0 || updateInterval < 1 || freezeLastIterations < 0
                || minimumObservations < 1 || memoryCycles < 1)
            throw new IllegalArgumentException("Invalid manager times or observation settings");
        if (!Double.isFinite(raiseDelaySeconds) || !Double.isFinite(lowerDelaySeconds)
                || lowerDelaySeconds < 0 || raiseDelaySeconds <= lowerDelaySeconds)
            throw new IllegalArgumentException("Delay thresholds must be finite with raise > lower >= 0");
        if (priceLevels.isEmpty() || priceLevels.getFirst() != 0.0)
            throw new IllegalArgumentException("Price levels must start at zero");
        for (int i=0;i<priceLevels.size();i++) {
            double v=priceLevels.get(i);
            if (!Double.isFinite(v) || v<0 || (i>0 && v<=priceLevels.get(i-1)))
                throw new IllegalArgumentException("Price levels must be finite and strictly increasing");
        }
        if (fixedLevel<0 || fixedLevel>=priceLevels.size()
                || candidateLinks.stream().anyMatch(String::isBlank)
                || new HashSet<>(candidateLinks).size()!=candidateLinks.size())
            throw new IllegalArgumentException("Invalid fixed level or duplicate/blank candidate links");
        if (!mode.equals("off") && candidateLinks.isEmpty())
            throw new IllegalArgumentException("Set candidateLinks from your no-toll baseline before enabling manager");
    }
    public int bins() { return (endTimeSeconds-startTimeSeconds)/timeBinSeconds; }
    public int bin(double time) {
        return time<startTimeSeconds || time>=endTimeSeconds ? -1
                : (int)((time-startTimeSeconds)/timeBinSeconds);
    }
    public void validate(Network network) {
        for (String id : candidateLinks) {
            var link=network.getLinks().get(Id.createLinkId(id));
            if (link==null || !link.getAllowedModes().contains("car"))
                throw new IllegalArgumentException("Candidate is not a car link: "+id);
        }
    }
}
