package dev.luizloyola.anima.core.brain.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.task.FakeContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A deed names a person the way its teller knows them, never by what their body is. */
class WhomTest {

    private final FakeContext ctx = new FakeContext();

    private static Being being(Being.Kind kind, String species, String name, Being.Identified tier) {
        return new Being(BeingId.of(UUID.randomUUID()), kind, species, name, null,
                new Pos(0, 64, 0), 4.0, Being.HUMANOID_EYE_HEIGHT, false, 1, 0, false, List.of(),
                Being.Activity.IDLE, Being.Locomotion.STILL, false, false, false, false, false,
                true, Being.Gear.NONE, tier, Being.Awareness.SEEN);
    }

    @Test
    void aPlayerTheyKnowIsTheirName() {
        assertEquals(Slot.name("Luiz"), Whom.of(ctx,
                being(Being.Kind.AGENT, "player", "Luiz", Being.Identified.INDIVIDUAL)));
    }

    @Test
    void aPersonNeverIntroducedIsAStrangerAndOneOnlyHeardIsSomeone() {
        assertEquals(Whom.STRANGER, Whom.of(ctx,
                being(Being.Kind.AGENT, "player", "", Being.Identified.INDIVIDUAL)));
        assertEquals(Whom.SOMEONE, Whom.of(ctx,
                being(Being.Kind.AGENT, "player", "", Being.Identified.SPECIES)));
    }

    @Test
    void aCreatureIsItsSpeciesAndNothingMadeOutIsSomething() {
        assertEquals(Slot.entity("zombie"), Whom.of(ctx,
                being(Being.Kind.MONSTER, "zombie", "", Being.Identified.SPECIES)));
        assertEquals(Doings.SOMETHING, Whom.of(ctx,
                being(Being.Kind.UNKNOWN, "", "", Being.Identified.NONE)));
        assertEquals(Doings.SOMETHING, Whom.of(ctx, null));
    }

    @Test
    void aConsumersTellerIsAskedFirst() {
        Being raider = being(Being.Kind.AGENT, "player", "", Being.Identified.INDIVIDUAL);
        Slot word = Slot.lang("test.whom.raider");
        Whom.register((c, b) -> b.id().equals(raider.id()) ? Optional.of(word) : Optional.empty());

        assertEquals(word, Whom.of(ctx, raider));
        assertEquals(Whom.STRANGER, Whom.of(ctx,
                being(Being.Kind.AGENT, "player", "", Being.Identified.INDIVIDUAL)),
                "a teller with nothing to say leaves the rest to the defaults");
    }
}
