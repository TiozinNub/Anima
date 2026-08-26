package dev.luizloyola.anima.core.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AgentLookupTest {

    private static final AgentId ADA =
            new AgentId(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
    private static final AgentId BRAM =
            new AgentId(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"));
    private static final AgentId THIRD =
            new AgentId(UUID.fromString("cccccccc-0000-0000-0000-000000000003"));

    private static Map<AgentId, String> two() {
        Map<AgentId, String> all = new LinkedHashMap<>();
        all.put(ADA, "Ada");
        all.put(BRAM, "Bram");
        return all;
    }

    @Test
    void matchesAnExactName() {
        assertEquals(new AgentLookup.Found(ADA), AgentLookup.match(two(), "Ada"));
    }

    @Test
    void matchesANameIgnoringCase() {
        assertEquals(new AgentLookup.Found(BRAM), AgentLookup.match(two(), "bRaM"));
    }

    @Test
    void trimsTheToken() {
        assertEquals(new AgentLookup.Found(ADA), AgentLookup.match(two(), "  Ada "));
    }

    @Test
    void matchesAShortIdPrefix() {
        assertEquals(new AgentLookup.Found(BRAM), AgentLookup.match(two(), "bbbbbbbb"));
    }

    @Test
    void matchesAFullId() {
        assertEquals(new AgentLookup.Found(ADA), AgentLookup.match(two(), ADA.toString()));
    }

    // An id prefix beats a name. Without this, an agent NAMED like another's short id would
    // shadow the agent that id actually belongs to.
    @Test
    void prefersAnIdPrefixOverAName() {
        Map<AgentId, String> all = two();
        all.put(THIRD, "bbbbbbbb");
        assertEquals(new AgentLookup.Found(BRAM), AgentLookup.match(all, "bbbbbbbb"));
    }

    @Test
    void reportsNothingMatched() {
        assertInstanceOf(AgentLookup.None.class, AgentLookup.match(two(), "Nobody"));
    }

    // Ambiguity FAILS rather than guessing (decision: Luiz): a name collides across kinds once
    // several mods share a world, and picking the nearer of a settler and a wolf is a worse
    // answer than asking.
    @Test
    void refusesAnAmbiguousName() {
        Map<AgentId, String> all = two();
        all.put(THIRD, "Ada");
        AgentLookup.Result result = AgentLookup.match(all, "Ada");
        assertInstanceOf(AgentLookup.Ambiguous.class, result);
        assertEquals(2, ((AgentLookup.Ambiguous) result).candidates().size());
    }

    @Test
    void reportsNothingForAnEmptyRoster() {
        assertInstanceOf(AgentLookup.None.class, AgentLookup.match(Map.of(), "Ada"));
    }

    // An empty token must not prefix-match the whole roster into an ambiguity report: every id
    // starts with "". Two names would otherwise read as "which of these did you mean".
    @Test
    void refusesAnEmptyToken() {
        assertInstanceOf(AgentLookup.None.class, AgentLookup.match(two(), "   "));
    }
}
