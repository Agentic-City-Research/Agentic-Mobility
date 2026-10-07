package org.matsim.agentic;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.util.HashSet;
import java.util.Set;

/**
 * Which information group each person belongs to. The LLM subset is drawn
 * first, then informed/uninformed membership is sampled (round(share) count)
 * inside the LLM subset and inside the background population, both held fixed
 * for the experiment.
 */
public final class InformationModel {

    public enum Group { LLM_INFORMED, LLM_UNINFORMED, BG_INFORMED, BG_UNINFORMED }

    private final Set<Id<Person>> llmIds = new HashSet<>();
    private final Set<Id<Person>> llmInformed = new HashSet<>();
    private final Set<Id<Person>> bgInformed = new HashSet<>();

    public void addLlm(Id<Person> id) {
        llmIds.add(id);
    }

    public void addLlmInformed(Id<Person> id) {
        llmInformed.add(id);
    }

    public void addBgInformed(Id<Person> id) {
        bgInformed.add(id);
    }

    public boolean isLlm(Id<Person> id) {
        return llmIds.contains(id);
    }

    public boolean isInformed(Id<Person> id) {
        if (llmIds.contains(id)) return llmInformed.contains(id);
        return bgInformed.contains(id);
    }

    public Group groupOf(Id<Person> id) {
        if (llmIds.contains(id)) {
            return llmInformed.contains(id) ? Group.LLM_INFORMED : Group.LLM_UNINFORMED;
        }
        return bgInformed.contains(id) ? Group.BG_INFORMED : Group.BG_UNINFORMED;
    }

    public Set<Id<Person>> llmIds() {
        return llmIds;
    }

    public int llmCount() {
        return llmIds.size();
    }

    public int llmInformedCount() {
        return llmInformed.size();
    }

    public int bgInformedCount() {
        return bgInformed.size();
    }
}
