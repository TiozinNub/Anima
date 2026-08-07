package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.agent.FoodValue;
import dev.luizloyola.anima.core.agent.Metabolism;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.agent.need.Company;
import dev.luizloyola.anima.core.agent.need.FoodNeed;
import dev.luizloyola.anima.core.agent.need.Needs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Test double for the {@link Percepts} bundle: Real core {@link Inventory} and {@link Metabolism}
 * (already pure and headless), plus a map-backed {@link FoodLookup} standing in for compat's
 * registry+recipe read — {@link #food} registers what counts as food, {@link #cooked} what cooking
 * would improve, and nothing is cookable by default, mirroring compat finding no bettering recipe.
 */
public final class FakePercepts implements Percepts {
    public final Inventory inventory = new Inventory();
    public final Metabolism metabolism = new Metabolism();
    /** The company gauge, on the test biped's band — settable through its own typed calls. */
    public final Company company = new Company(() -> TestSpecies.PROFILE);
    /** The real roster over the two above: food is a view, so hunger stays one number here too. */
    public final Needs needs = new Needs().add(new FoodNeed(metabolism, () -> TestSpecies.PROFILE)).add(company);
    /** The feet cell — settable; defaults to a plausible stance so wander targets are sane. */
    public Pos position = new Pos(0, 64, 0);
    public List<Being> beings = List.of();
    /** The block world — a real {@link FakeProbe} (flat ground at y 63, sparse blocks on top). */
    public final FakeProbe blocks = new FakeProbe();
    public List<Drop> drops = List.of();
    /** The game clock — settable; tests that price staleness advance it. */
    public long time;
    private final Map<String, FoodValue> foodById = new HashMap<>();
    private final Map<String, FoodValue> cookedById = new HashMap<>();

    /** Register item {@code id} as edible with the given value — the test's food registry. */
    public void food(String id, FoodValue value) {
        foodById.put(id, value);
    }

    /** Register {@code id}'s strictly-better one-step cooked form — the test's recipe book. */
    public void cooked(String id, FoodValue cookedValue) {
        cookedById.put(id, cookedValue);
    }

    @Override
    public Inventory inventory() {
        return inventory;
    }

    @Override
    public Metabolism metabolism() {
        return metabolism;
    }

    @Override
    public Needs needs() {
        return needs;
    }

    @Override
    public Pos position() {
        return position;
    }

    @Override
    public List<Being> beings() {
        return beings;
    }

    /** An identified, aggressive, bare-handed zombie (danger weight 1.0) at this range —
     *  the standard test threat; {@code approaching} maps to the old targeting bonus. */
    public static Being monsterAt(Pos pos, double distance, boolean approaching) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.MONSTER, "zombie", "",
                null, pos, distance, 1, 0, false, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, approaching, true,
                Being.Gear.NONE, Being.Identified.SPECIES, Being.Awareness.SEEN);
    }

    @Override
    public BlockProbe blocks() {
        return blocks;
    }

    @Override
    public List<Drop> drops() {
        return drops;
    }

    @Override
    public long time() {
        return time;
    }

    @Override
    public FoodLookup foods() {
        // An empty stack's id is "" and never registered, so it reads as inedible (and
        // uncookable) for free.
        return new FoodLookup() {
            @Override
            public Optional<FoodValue> of(ItemStack stack) {
                return Optional.ofNullable(foodById.get(stack.id()));
            }

            @Override
            public Optional<FoodValue> cookedForm(ItemStack stack) {
                return Optional.ofNullable(cookedById.get(stack.id()));
            }
        };
    }
}
