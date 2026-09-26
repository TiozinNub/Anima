package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.knowledge.BlockKind;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.FakeProbe;
import dev.luizloyola.anima.core.brain.sense.Surroundings;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Drop;
import dev.luizloyola.anima.core.brain.sense.FoodLookup;
import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.inv.ItemStack;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
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
    /**
     * The same world in the pathfinder's vocabulary — settable; defaults to flat dry ground whose
     * floor is directly under {@link #position}, open air above, everywhere in bounds.
     *
     * <p>Written against the live field rather than pinned at a y, because {@code position} is
     * mutable and several suites stand their body somewhere other than the default. A grid fixed at
     * y 63 would leave those bodies nowhere to stand, so a wander over it idles every beat and
     * still passes the weaker assertions — green while testing nothing.
     */
    public NavGrid terrain = new NavGrid() {
        @Override
        public CellType cell(int x, int y, int z) {
            return y < position.y() ? CellType.GROUND : CellType.PASSABLE;
        }
    };
    public List<Drop> drops = List.of();
    /** The game clock — settable; tests that price staleness advance it. */
    public long time;
    /** The sky, hour and light — null, the default, is a rig with no world to read. */
    public Surroundings surroundings;
    /** What the legs last found out about being shut in — settable; defaults to nothing known. */
    public Confinement confinement = Confinement.NONE;
    /** Whether walks keep failing stranded from here — settable; defaults to no. */
    public boolean strandedHere;
    /** A settler's eyes and arm, unless a test says otherwise. */
    public double eyeHeight = 1.62;
    public double crouchedEyeHeight = 1.27;
    public double reach = 4.5;
    /** Who this fake body has called lately — seeded by guardrail tests. */
    public final java.util.Set<BeingId> called = new java.util.HashSet<>();
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

    @Override
    public boolean calledLately(BeingId whom) {
        return called.contains(whom);
    }

    /** An identified, aggressive, bare-handed zombie (danger weight 1.0) at this range —
     *  the standard test threat; {@code approaching} maps to the old targeting bonus. */
    public static Being monsterAt(Pos pos, double distance, boolean approaching) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.MONSTER, "zombie", "",
                null, pos, distance, Being.HUMANOID_EYE_HEIGHT, false, 1, 0, false, List.of(),
                Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, approaching, true,
                Being.Gear.NONE, Being.Identified.SPECIES, Being.Awareness.SEEN);
    }

    /**
     * The one {@link Being} literal for a person-kind track — every person-shaped fixture goes
     * through this instead of copying the constructor call, so growing {@code Being} by a field
     * means one edit here rather than one per fixture.
     */
    private static Being personTrack(BeingId id, String name, Pos pos, double distance,
            boolean hailing, boolean playerControlled, Being.Identified identified,
            Being.Awareness awareness) {
        return personTrack(id, name, pos, distance, hailing, playerControlled, identified,
                awareness, Being.Activity.IDLE, Being.Locomotion.STILL);
    }

    private static Being personTrack(BeingId id, String name, Pos pos, double distance,
            boolean hailing, boolean playerControlled, Being.Identified identified,
            Being.Awareness awareness, Being.Activity activity, Being.Locomotion locomotion) {
        return new Being(id, Being.Kind.AGENT, "person", name,
                null, pos, distance, Being.HUMANOID_EYE_HEIGHT, playerControlled, 1, 0, false,
                List.of(), activity, locomotion,
                false, false, false, hailing, false, false, Being.Gear.NONE,
                identified, awareness);
    }

    /** A person-shaped track that is calling out — the hail percept, for instinct tests. */
    public static Being hailingPersonAt(Pos pos, double distance) {
        return personTrack(BeingId.of(UUID.randomUUID()), "", pos, distance, true, false,
                Being.Identified.SPECIES, Being.Awareness.HEARD);
    }

    /** A person-shaped track, seen and quiet. An empty name is a stranger — somebody whose name
     *  we were never told; a filled one is somebody the contact book already knows. */
    public static Being personAt(Pos pos, double distance, String name) {
        return personTrack(BeingId.of(UUID.randomUUID()), name, pos, distance, false, false,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    /**
     * The same, but at a caller-chosen {@code id} — for a test that needs to move the SAME
     * counterpart between ticks (a fresh random id each call would read as a new stranger
     * arriving, not the same one taking a step closer).
     */
    public static Being personAt(BeingId id, Pos pos, double distance, String name) {
        return personTrack(id, name, pos, distance, false, false, Being.Identified.INDIVIDUAL,
                Being.Awareness.SEEN);
    }

    /** A seen stranger with {@code held} (an item id) in hand. */
    public static Being personHolding(BeingId id, Pos pos, double distance, String held) {
        Being plain = personAt(id, pos, distance, "");
        return new Being(plain.id(), plain.kind(), plain.species(), plain.name(), plain.profession(),
                plain.pos(), plain.distance(), plain.eyeHeight(), plain.playerControlled(),
                plain.count(), plain.spread(), plain.herdAnimal(), plain.members(), plain.activity(),
                plain.locomotion(), plain.sneaking(), plain.watching(), plain.aimedAt(),
                plain.hailing(), plain.approaching(), plain.aggressive(), plain.gear(),
                plain.identified(), plain.awareness(), held);
    }

    /** A seen stranger visibly doing something — swinging, eating, running. */
    public static Being personDoing(Pos pos, double distance, Being.Activity activity,
            Being.Locomotion locomotion) {
        return personTrack(BeingId.of(UUID.randomUUID()), "", pos, distance, false, false,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN, activity, locomotion);
    }

    /**
     * A live PLAYER's body: {@code Kind.AGENT} exactly as a Person's is, and told apart only by
     * {@link Being#playerControlled()} — which is the whole point of the axis, so a fixture that
     * differed in any other way would test the wrong thing.
     */
    public static Being playerAt(Pos pos, double distance) {
        return personTrack(BeingId.of(UUID.randomUUID()), "", pos, distance, false, true,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    @Override
    public BlockProbe blocks() {
        return blocks;
    }

    @Override
    public NavGrid terrain() {
        return terrain;
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
    public java.util.Optional<Surroundings> surroundings() {
        return java.util.Optional.ofNullable(surroundings);
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

    @Override
    public boolean strandedHere() {
        return strandedHere;
    }

    @Override
    public Confinement confinement() {
        return confinement;
    }

    @Override
    public double eyeHeight() {
        return eyeHeight;
    }

    @Override
    public double crouchedEyeHeight() {
        return crouchedEyeHeight;
    }

    @Override
    public double reach() {
        return reach;
    }
}
