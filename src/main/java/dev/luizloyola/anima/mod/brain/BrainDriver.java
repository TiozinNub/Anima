package dev.luizloyola.anima.mod.brain;

import dev.luizloyola.anima.core.brain.Arbiter;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.board.WorkItem;
import dev.luizloyola.anima.core.brain.board.WorkLease;
import dev.luizloyola.anima.core.brain.board.WorkSource;
import dev.luizloyola.anima.core.brain.act.ActuatorAccess;
import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BlockPlacer;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.act.ItemConsumer;
import dev.luizloyola.anima.core.brain.act.Mover;
import dev.luizloyola.anima.core.brain.act.Riser;
import dev.luizloyola.anima.core.brain.board.AgentClaims;
import dev.luizloyola.anima.core.brain.instinct.EatInstinct;
import dev.luizloyola.anima.core.brain.instinct.Instinct;
import dev.luizloyola.anima.core.brain.instinct.FleeInstinct;
import dev.luizloyola.anima.core.brain.instinct.WanderInstinct;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.sense.Percepts;
import dev.luizloyola.anima.core.brain.task.Task;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.log.AgentJournal;
import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.AgentRandom;
import dev.luizloyola.anima.core.agent.Pronouns;
import dev.luizloyola.anima.mod.brain.AgentBlockPlacer;
import dev.luizloyola.anima.mod.brain.AgentItemConsumer;
import dev.luizloyola.anima.mod.brain.AgentMover;
import dev.luizloyola.anima.mod.brain.AgentPercepts;
import dev.luizloyola.anima.mod.body.AgentBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Per-{@link AgentBody} brain host: mounts the pure-core machinery on the entity and hands it a
 * {@link BrainContext} — {@link AgentMover} legs, {@link AgentItemConsumer} mouth,
 * {@link AgentPercepts} senses. Arbiter-first ({@link Arbiter} over Flee, Eat and Wander, Flee
 * first so a flee/eat pressure tie flees); a mounting bracket only, built once from stateless
 * views.
 *
 * <p>It reads the body through {@link Percepts} and never owns body state: metabolism is the
 * entity's ({@link AgentBody#metabolism()}), so a paused or throttled brain still starves. The
 * arbiter and its task tree are transient — a reload re-decides.
 */
public final class BrainDriver {
    private final AgentBody person;
    private final Arbiter arbiter;
    /**
     * The one context every task/instinct tick receives: actuators, percepts and the debug
     * journal — the only Minecraft boundary the core machinery ever touches.
     */
    private final BrainContext context;

    /**
     * The autonomy switch. ON (the default — every AgentBody spawns autonomous): the arbiter
     * decides each tick. OFF: a manual command has the wheel, and only its task runs.
     */
    private boolean auto = true;

    /** This body's own randomness — see {@link #random()}. */
    private final AgentRandom random;

    /**
     * The wander mute. ON (the default): the idle drive bids its ambient floor. OFF: the brain
     * keeps thinking but nothing bids to roam, so an unbothered body stands where you put it —
     * narrower than autonomy off, which stops the arbiter entirely.
     *
     * <p>Durable since 2026-08-04, with {@link #auto}: the switches are a decision made ABOUT this
     * body, where the rest of the driver is re-derivable. The body saves them and hands them back
     * through {@link #restoreSwitches}.
     */
    private boolean wander = true;

    /**
     * The wander drive as the arbiter sees it: the real {@link WanderInstinct} behind the
     * {@link #wander} gate. Kept as a field so muting it can tell whether it is the drive
     * currently running, and cut it short if so.
     */
    private final Instinct wanderDrive;

    /**
     * This person's knowledge view, resolved lazily on first use and cached: the {@code AgentId}
     * and the running server are absent at construction but settled by the top of
     * {@code AgentBody.tick()}, before the brain runs.
     */
    private AgentKnowledge knowledge;

    /**
     * This person's view of the server-shared work-site claims — resolved lazily exactly like
     * {@link #knowledge}, and for the same reason.
     */
    private AgentClaims claims;

    /**
     * Where layer-3 demand comes from, supplied by whoever owns this body: a library cannot know
     * what an agent should be <em>working on</em>. A pets mod hands over {@link WorkSource#NONE}
     * and never takes an errand. Transient; a reload re-decides.
     */
    private final WorkSource board;

    /** A body with no layer-3 demand of its own — the pets case, and every test rig. */
    public BrainDriver(AgentBody person) {
        this(person, WorkSource.NONE);
    }

    public BrainDriver(AgentBody person, WorkSource board) {
        this.person = person;
        this.board = board;
        Mover mover = new AgentMover(person);
        ItemConsumer consumer = new AgentItemConsumer(person);
        BlockPlacer placer = new AgentBlockPlacer(person);
        Percepts percepts = new AgentPercepts(person, () -> person.beingSense().beings());
        ActuatorAccess actuators = new ActuatorAccess() {
            @Override
            public Mover mover() {
                return mover;
            }

            @Override
            public ItemConsumer consumer() {
                return consumer;
            }

            @Override
            public BlockBreaker breaker() {
                // Owned and ticked by the body (crack/drops/exhaustion advance with the
                // entity); the driver only lends it out as a port.
                return person.blockBreaker();
            }

            @Override
            public BlockPlacer placer() {
                return placer; // one-shot port: stateless view, driver-owned like the consumer
            }

            @Override
            public Riser riser() {
                return person.riser(); // body-owned and body-ticked, like the breaker
            }

            @Override
            public Gazer gazer() {
                return person.gaze(); // body-owned and body-ticked, like the breaker
            }
        };
        this.context = new BrainContext() {
            @Override
            public ActuatorAccess actuators() {
                return actuators;
            }

            @Override
            public Percepts percepts() {
                return percepts;
            }

            @Override
            public AgentJournal journal() {
                return person.journal(); // the entity owns the one cached view; the body/nav share it
            }

            @Override
            public Pronouns pronouns() {
                return person.pronouns(); // the narrating voice: pronouns are asked for, never spelled
            }

            @Override
            public AgentProfile profile() {
                return person.profile(); // what this body is like — the brain never assumes a species
            }

            @Override
            public DangerTable danger() {
                return person.danger(); 
            }

            @Override
            public AgentKnowledge knowledge() {
                return resolveKnowledge();
            }

            @Override
            public AgentClaims claims() {
                return resolveClaims();
            }

            @Override
            public double costTolerance() {
                // Manual driving answers to no pressure: a dev-issued task runs to completion (or
                // failure) on its own terms rather than getting judged against the arbiter's
                // budget while autonomy is off.
                return auto ? arbiter.costTolerance() : Double.POSITIVE_INFINITY;
            }

            @Override
            public java.util.random.RandomGenerator random() {
                return BrainDriver.this.random;
            }
        };
        // RandomSource isn't a java.util.random.RandomGenerator, so it can't reach the instincts
        // directly; it seeds this one once at construction instead. Both random-driven instincts
        // (Flee's scatter, Wander's roam) draw from it — one stream per brain, never shared.
        this.random = new AgentRandom(person.entity().getRandom().nextLong());
        // The board reaches the arbiter through a celebrating wrapper: a completed errand gets a
        // beat in the world (decision: Luiz) before the core board hears of it. sendParticles
        // broadcasts to every tracking client; no custom networking.
        WorkSource celebrating = new WorkSource() {
            @Override
            public Optional<WorkItem> bestAvailable(BrainContext c) {
                return board.bestAvailable(c);
            }

            @Override
            public void claimed(WorkItem item, BrainContext c) {
                board.claimed(item, c);
            }

            @Override
            public void completed(WorkItem item, BrainContext c) {
                celebrate();
                board.completed(item, c);
            }

            @Override
            public void failed(WorkItem item, BrainContext c) {
                board.failed(item, c);
            }

            @Override
            public void driveFailed(Instinct instinct, String detail, BrainContext c) {
                board.driveFailed(instinct, detail, c); // nothing to celebrate; just don't swallow it
            }

            @Override
            public void heartbeat(WorkItem item, BrainContext c) {
                board.heartbeat(item, c);
            }

            @Override
            public boolean stillMine(WorkItem item, BrainContext c) {
                return board.stillMine(item, c);
            }
        };
        // A gate rather than removal from the list: muting zeroes the bid, and zero pressure is
        // not a bid (see Arbiter), so a muted wander never wins and everything else arbitrates
        // unchanged. A wrapper, not a flag in WanderInstinct: a dev override is the mod's business.
        WanderInstinct roaming = new WanderInstinct();
        this.wanderDrive = new Instinct() {
            @Override
            public double pressure(BrainContext c) {
                return wander ? roaming.pressure(c) : 0.0;
            }

            @Override
            public Task root(BrainContext c) {
                return roaming.root(c);
            }

            @Override
            public String describe() {
                // Tagged on the pressure line. That is where someone asks why wander
                // reads 0.00 — a muted drive is otherwise indistinguishable from a profile that
                // sets WANDER_IDLE_PRESSURE to zero.
                return wander ? roaming.describe() : roaming.describe() + " (muted)";
            }

            @Override
            public int failCooldown() {
                return roaming.failCooldown();
            }
        };
        // Flee is first on purpose: the arbiter breaks pressure ties in list order, so an exact
        // flee/eat tie must resolve to fleeing.
        this.arbiter = new Arbiter(List.of(
                new FleeInstinct(), new EatInstinct(),
                this.wanderDrive),
                celebrating);
    }

    private void celebrate() {
        ServerLevel level = (ServerLevel) person.level();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                person.entity().getX(), person.entity().getY() + 1.4, person.entity().getZ(), 12, 0.4, 0.5, 0.4, 0.0);
        level.playSound(null, person.blockPosition(), SoundEvents.VILLAGER_YES,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /**
     * One brain tick, from {@link AgentBody#serverAiStep()}. Manual mode advances only the
     * executor: the arbiter stays dormant so a dev-issued task isn't second-guessed mid-flight.
     */
    public void tick() {
        // The board thinks on its own slow cadence regardless of who is driving — posting and
        // withdrawing are demand bookkeeping, not action; only the arbiter CLAIMS.
        this.board.tick(this.context);
        if (this.auto) {
            this.arbiter.tick(this.context);
        } else {
            this.arbiter.executor().tick(this.context);
        }
    }

    /**
     * Hands the executor a root task, cancelling any current one — the debug commands' entry
     * point. Always takes the wheel: autonomy goes off, and {@code true} comes back exactly when
     * this call is what disabled it.
     */
    public boolean run(Task task) {
        boolean wasAuto = this.auto;
        if (this.auto) {
            this.auto = false;
        }
        this.arbiter.executor().run(task, this.context);
        return wasAuto;
    }

    /** Cancels the running task (releasing its actuators); safe to call when idle. */
    public void cancel() {
        this.arbiter.executor().cancel(this.context);
    }

    public boolean isBusy() {
        return this.arbiter.executor().isBusy();
    }

    /**
     * This body's stream of chance — seeded once from the entity, then saved and restored, so a
     * reload picks the same roam. See {@link AgentRandom}.
     */
    public AgentRandom random() {
        return this.random;
    }

    /**
     * The plan and the grant that owns it, always as one — see {@link Arbiter.Grant} for why they
     * are never separated.
     */
    public BrainSnapshot snapshot() {
        return new BrainSnapshot(this.arbiter.executor().snapshot(), this.arbiter.grant());
    }

    /**
     * Puts a saved plan and its grant back; nothing is ticked.
     *
     * <p>{@code held} is the errand the board has just handed back, or null — an argument rather
     * than a lookup, because the board must be restored first. A grant owing an errand while
     * nothing is held took the server down.
     */
    public void restore(BrainSnapshot snapshot,
                        dev.luizloyola.anima.core.brain.board.WorkItem held) {
        this.arbiter.executor().restore(snapshot.plan());
        this.arbiter.restoreGrant(snapshot.grant(), held);
    }

    public record BrainSnapshot(dev.luizloyola.anima.core.brain.task.TaskExecutor.State plan,
                                Arbiter.Grant grant) {
    }

    /** @see Arbiter#cooldowns */
    public Map<String, Integer> cooldowns() {
        return this.arbiter.cooldowns();
    }

    /** @see Arbiter#restoreCooldowns */
    public void restoreCooldowns(Map<String, Integer> waiting) {
        this.arbiter.restoreCooldowns(waiting);
    }

    /** Whether the arbiter is currently deciding (ON) or a manual task has the wheel (OFF). */
    public boolean isAuto() {
        return this.auto;
    }

    /** Flips the autonomy switch — see {@link #auto}. */
    public void setAuto(boolean auto) {
        this.auto = auto;
        // BRAIN log: the dev override is worth a line — it explains a gap where the arbiter went quiet.
        this.person.journal().record(Category.BRAIN, "auto", auto ? "on" : "off");
    }

    /**
     * Puts both switches back as a body saved them, on load. Not the two setters:
     * those journal the change, and a reload is nobody's decision. It also runs while the entity
     * is still being read from NBT, where the journal is not reachable.
     */
    public void restoreSwitches(boolean auto, boolean wander) {
        this.auto = auto;
        this.wander = wander;
    }

    /** Whether the idle wander drive is bidding (ON) or muted (OFF) — see {@link #wander}. */
    public boolean isWander() {
        return this.wander;
    }

    /**
     * Mutes or unmutes the idle wander drive — see {@link #wander}.
     *
     * <p>Takes effect NOW, cutting short a stroll already underway — but only a running WANDER,
     * and only while the ARBITER is driving. Under a manual order the arbiter is not ticking, so
     * its active drive is a stale reading (usually wander) and cancelling on it would kill the
     * {@code brain goto} you are watching; {@code brain cancel} is the verb for that.
     */
    public void setWander(boolean wander) {
        this.wander = wander;
        // BRAIN log: same reason as `auto` — it explains a body that stopped drifting for no
        // reason the world can account for.
        this.person.journal().record(Category.BRAIN, "wander", wander ? "on" : "muted");
        if (!wander && this.auto && this.arbiter.activeDrive().orElse(null) == this.wanderDrive) {
            this.arbiter.executor().cancel(this.context);
        }
    }

    /** The person's knowledge store, resolved once and cached — see {@link #knowledge}. */
    private AgentKnowledge resolveKnowledge() {
        if (this.knowledge == null) {
            ServerLevel level = (ServerLevel) this.person.level();
            this.knowledge = Knowledges.of(level.getServer()).forPerson(this.person.agentId());
        }
        return this.knowledge;
    }

    /** The person's claims view, resolved once and cached — see {@link #claims}. */
    private AgentClaims resolveClaims() {
        if (this.claims == null) {
            ServerLevel level = (ServerLevel) this.person.level();
            this.claims = Claims.of(level.getServer()).forPerson(this.person.agentId());
        }
        return this.claims;
    }

    /**
     * The work source's status, one line per row — a row per board, or the one line saying there
     * is none. Not a plain string: layer 3 stopped being one line once an agent could reach two
     * boards.
     */
    public List<String> describeBoard() {
        return this.board.describeLines(this.context);
    }

    /** Every hold this body's boards are keeping open — the claims dump's layer-3 half. */
    public List<WorkLease> leases() {
        return this.board.leases(this.context);
    }

    /**
     * The brain's sense bundle — for debug readouts that want the same eyes the brain uses
     * (the peers dump reads the live cache with its movement history, not a fresh throwaway
     * whose first scan can't tell moving from standing).
     */
    public Percepts percepts() {
        return this.context.percepts();
    }

    /** The brain's one-line status, for the debug commands: which side is driving, then its report. */
    public String describe() {
        return this.auto
                ? "auto | " + this.arbiter.describe()
                : "manual | " + this.arbiter.executor().describe();
    }

    /**
     * The whole brain as separate lines, for the stacked debug view: who is driving, every
     * instinct's pressure, the claimed work item, and the running task tree one level per line.
     * Unlike {@link #describe()} it shows the arbiter even while a manual order holds the wheel —
     * where the arbiter is not ticking, so the pressures are frozen and the mode line says so.
     */
    /**
     * The task machinery this brain runs, for debug surfaces that want to LOOK at what is running
     * rather than read a sentence about it — {@link #describe()} is the sentence.
     */
    public dev.luizloyola.anima.core.brain.task.TaskExecutor executor() {
        return arbiter.executor();
    }

    public List<String> describeLines() {
        List<String> lines = new ArrayList<>();
        lines.add(this.auto ? "auto" : "manual — arbiter dormant, pressures frozen");
        lines.addAll(this.arbiter.pressureLines());
        lines.addAll(this.arbiter.executor().describeLines());
        return lines;
    }
}
