package dev.luizloyola.anima.mod.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.luizloyola.anima.core.agent.AgentModifiers;
import dev.luizloyola.anima.core.agent.AspectModifier;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The modifier codec round-trips, over the two cases otherwise discovered in a world: a body
 * carrying a shift across a restart, and a build that no longer declares the aspect it names.
 *
 * <p>Through {@code JsonOps}: the codec is ops-agnostic, so the test needs no Minecraft.
 */
class ModifiersTest {

    private static final ProfileAspect ASPECT = ProfileAspect.values()[0];

    @Test
    void oneModifierSurvivesTheRoundTrip() {
        AspectModifier before = AspectModifier.add("debug", ASPECT, 5.0);
        var encoded = Modifiers.CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        AspectModifier after = Modifiers.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(before, after);
    }

    @Test
    void everyOperationRoundTrips() {
        // The op decides how a shift combines with its fellows, so losing it silently would change
        // what a body is rather than failing loudly.
        for (AspectModifier.Op op : AspectModifier.Op.values()) {
            AspectModifier before = new AspectModifier("job:x", ASPECT, op, 0.25);
            var encoded = Modifiers.CODEC.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
            assertEquals(before, Modifiers.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow(),
                    "op " + op + " must survive");
        }
    }

    @Test
    void aWholeSetRoundTripsInOrder() {
        List<AspectModifier> before = List.of(
                AspectModifier.add("a", ASPECT, 1.0),
                AspectModifier.fractionOfBase("b", ASPECT, 0.1),
                AspectModifier.fractionOfTotal("c", ASPECT, 0.2));
        var encoded = Modifiers.LIST.encodeStart(JsonOps.INSTANCE, before).getOrThrow();
        assertEquals(before, Modifiers.LIST.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void anUnknownAspectFailsThatEntryRatherThanGuessing() {
        // A renamed or removed aspect must not be silently mapped onto whichever knob sorts first
        // — that would quietly change a body rather than losing one shift.
        var broken = com.google.gson.JsonParser.parseString(
                "{\"id\":\"debug\",\"aspect\":\"no.such.aspect\",\"op\":\"ADD\",\"amount\":5.0}");
        assertTrue(Modifiers.CODEC.parse(JsonOps.INSTANCE, broken).isError(),
                "an aspect this build does not declare has to be an error");
    }

    @Test
    void reapplyingAnIdReplacesRatherThanStacks() {
        // The property that lets a body carry a modifier and a consumer re-derive the same one on
        // load without the two adding up — see the note on Modifiers.
        AgentModifiers set = new AgentModifiers();
        set.applyAll(List.of(AspectModifier.add("job:lumberjack", ASPECT, 4.0)));
        set.applyAll(List.of(AspectModifier.add("job:lumberjack", ASPECT, 4.0)));
        assertEquals(1, set.all().size(), "the same id twice is one modifier, not two");
        assertEquals(4.0, set.on(ASPECT).get(0).amount());
    }
}
