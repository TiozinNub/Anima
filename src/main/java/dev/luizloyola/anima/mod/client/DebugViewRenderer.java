package dev.luizloyola.anima.mod.client;

import dev.luizloyola.anima.compat.client.debug.GizmoFrame;
import dev.luizloyola.anima.core.brain.knowledge.HorizonBuffer;
import dev.luizloyola.anima.core.brain.knowledge.HorizonScanner;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.mod.debug.DebugLayer;
import dev.luizloyola.anima.mod.net.DebugViewPayload;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The in-world debug view, client half: draws the latest {@link DebugViewClient} snapshot through
 * the same {@code net.minecraft.gizmos} API vanilla's hitbox view uses, so it depth-sorts like the
 * game's own.
 *
 * <p>Hooked through {@link GizmoFrame}: the gizmo collector is a thread-local the level renderer
 * installs, and outside its window every static {@code Gizmos} call is silently discarded. Which
 * Fabric event that is differs by version, so the hook lives in {@code compat} and none of the
 * drawing does. Immediate mode — a dropped payload redraws the previous truth.
 *
 * <p><b>Snapshot facts and live facts are mixed.</b> Waypoints, beliefs and peer
 * positions come off the wire; their own position and head yaw are read from the LOCAL entity every
 * frame, so the first leg stays glued to their feet and the cone turns as they look, between
 * four-tick snapshots.
 */
@Environment(EnvType.CLIENT)
public final class DebugViewRenderer {
    private DebugViewRenderer() {}

    // Path colours by move type: a leap and a stroll are the same cells and different intentions.
    private static final int WALK_COLOR = 0xFFB0C4FF;
    private static final int JUMP_COLOR = 0xFFFFE066;
    private static final int DROP_COLOR = 0xFFFF9E3D;
    private static final int LEAP_COLOR = 0xFFFF4D4D;
    private static final int SWIM_COLOR = 0xFF4DD2FF;
    private static final int GOAL_COLOR = 0xFF57F287;

    private static final int TREE_COLOR = 0xFF3FBF5F;
    private static final int WATER_COLOR = 0xFF3F8FFF;
    private static final int HERD_COLOR = 0xFFD9A05B;
    private static final int GHOST_COLOR = 0xFF9A9A9A;

    private static final int SEEN_COLOR = 0xFF57F287;
    private static final int HEARD_COLOR = 0xFFFFD24D;
    private static final int REMEMBERED_COLOR = 0xFF8A8A8A;
    private static final int CONE_COLOR = 0x66FFFFFF;

    /** Gold for the far aperture, magenta for both ways the far sense admits it is guessing. */
    private static final int HORIZON_CONE_COLOR = 0xB4FFD700;
    private static final int TRUNCATED_COLOR = 0xFFFF3FD4;
    private static final int GLIMPSE_COLOR = 0xFFFF3FD4;

    private static final int NAV_TEXT_COLOR = 0xFFB0C4FF;
    private static final int BRAIN_TEXT_COLOR = 0xFFFFFFFF;
    private static final int HORIZON_TEXT_COLOR = 0xFFFFD700;

    /** Line widths: the leg being walked now is drawn heavier than the rest of the plan. */
    private static final float PATH_WIDTH = 2.5F;
    private static final float CURRENT_LEG_WIDTH = 5.0F;
    private static final float THIN = 1.5F;

    /** Waypoint lines float just above the floor of their cell. */
    private static final double PATH_Y = 0.15;

    /** Text sizes, matching vanilla's own debug renderers (title line vs detail lines). */
    private static final float TITLE_SCALE = 0.48F;
    private static final float DETAIL_SCALE = 0.32F;

    /** Vertical gap between stacked text lines — vanilla's spacing, kept so the stack reads the same. */
    private static final double TEXT_LINE_STEP = 0.25;
    /** How far above their head the stack starts: enough to clear the always-visible name tag. */
    private static final double NAME_TAG_CLEARANCE = 0.9;
    /** Vanilla's left-alignment nudge for stacked debug text. */
    private static final float TEXT_LEFT_ALIGN = 0.5F;
    /** Height of the anchor marker's column, so it stays findable inside a large bounds box. */
    private static final float BELIEF_STUB = 1.5F;
    /** Clearance between the top of what a belief covers and its label. */
    private static final double BELIEF_LABEL_CLEARANCE = 0.6;
    /** Stand-in height for a peer with no loaded body — a player's, since every peer is one. */
    private static final double ASSUMED_BODY_HEIGHT = 1.8;

    /** Segments in the drawn cone arc — enough to read as a curve at any sane radius. */
    private static final int CONE_SEGMENTS = 24;

    /** The skyline ribbon: heavy enough to read at sixty blocks, with a dot on every sample. */
    private static final float SKYLINE_WIDTH = 3.0F;
    private static final float SKYLINE_POINT = 0.14F;
    private static final int SKYLINE_ALPHA = 0xC8;

    /**
     * One sight line back to the eye every this many bearings. A ray per sample would be a solid
     * wall of lines; a sparse fan is what makes the ribbon read as something being LOOKED at.
     */
    private static final int RAY_EVERY_BINS = 8;

    /**
     * Elevation tangents at the ends of the colour ramp — 17° down and 17° up, so level ground
     * lands in the middle of it and comes out green.
     *
     * <p>Narrow on purpose: out at horizon range a five-block ridge subtends 8° and a twelve-block
     * tower 10°, so a ramp with real headroom paints every one of them the same cyan. Saturating
     * on genuine cliffs is the cheaper mistake.
     */
    private static final double COLDEST_TAN = -0.30;
    private static final double HOTTEST_TAN = 0.30;

    /** Clearance between a glimpsed cell and its label. */
    private static final double GLIMPSE_LABEL_CLEARANCE = 0.6;

    public static void install() {
        GizmoFrame.onFrame(DebugViewRenderer::draw);
    }

    private static void draw() {
        DebugViewPayload view = DebugViewClient.get();
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (view == null || view.isEmpty() || level == null) {
            return;
        }
        // The Person may be out of client render distance while their path and beliefs are still
        // perfectly drawable — those are world-anchored. Only the head-mounted text and the cone
        // need a body, and they check for one.
        Entity person = level.getEntity(view.entityId());
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        // One shared counter so PATH's status line and BRAIN's stack pile up over their head in
        // registration order instead of overprinting each other.
        int[] line = {0};

        if (DebugLayer.PATH.in(view.layers())) {
            drawPath(view, person, partialTick);
            if (person != null && !view.nav().isBlank()) {
                overhead(person, partialTick, line[0]++, view.nav(), NAV_TEXT_COLOR, TITLE_SCALE);
            }
        }
        if (DebugLayer.BRAIN.in(view.layers()) && person != null) {
            drawBrain(view, person, partialTick, line);
        }
        if (DebugLayer.MEMORY.in(view.layers())) {
            drawBeliefs(view);
        }
        if (DebugLayer.PEERS.in(view.layers())) {
            drawPeers(view, person, level, partialTick);
        }
        if (DebugLayer.HORIZON.in(view.layers()) && person != null) {
            drawHorizon(view.sight(), person, partialTick, line);
        }
    }

    /**
     * The walked plan: a polyline from their feet through every remaining waypoint, each leg
     * coloured by how they mean to enter it. Legs already behind are drawn faint rather than
     * dropped — seeing where they came from is half of reading a path that went wrong.
     */
    private static void drawPath(DebugViewPayload view, @Nullable Entity person, float partialTick) {
        List<DebugViewPayload.Step> path = view.path();
        view.goal().ifPresent(goal ->
                Gizmos.cuboid(goal, GizmoStyle.stroke(GOAL_COLOR, THIN)).setAlwaysOnTop());
        if (path.isEmpty()) {
            return;
        }
        int index = Mth.clamp(view.pathIndex(), 0, path.size());
        // Behind them: waypoint to waypoint, faded. Not chained through the current
        // position — those cells are left behind, and hanging them off the feet would draw a long
        // line backwards to the start of the path.
        for (int i = 1; i < index; i++) {
            leg(centre(path.get(i - 1).pos()), path.get(i), true, false);
        }
        if (index >= path.size()) {
            return; // arrived: the whole plan is behind them
        }
        // The leg being walked NOW starts at the actual feet, so it tracks them between
        // snapshots instead of jumping cell to cell at the send cadence.
        // With no body to anchor to (they are outside render distance), fall back to the waypoint
        // before this one so the leg still has a direction to show.
        Vec3 from = person != null
                ? person.getPosition(partialTick).add(0.0, PATH_Y, 0.0)
                : centre(path.get(index > 0 ? index - 1 : index).pos());
        leg(from, path.get(index), false, true);
        for (int i = index + 1; i < path.size(); i++) {
            leg(centre(path.get(i - 1).pos()), path.get(i), false, false);
        }
    }

    /** One leg of the path: the line into {@code step}, coloured by how they mean to enter it. */
    private static void leg(Vec3 from, DebugViewPayload.Step step, boolean walked, boolean current) {
        Vec3 to = centre(step.pos());
        int color = fade(moveColor(step.move()), walked);
        Gizmos.line(from, to, color, current ? CURRENT_LEG_WIDTH : PATH_WIDTH);
        Gizmos.point(to, color, current ? 0.18F : 0.10F);
    }

    /**
     * The arbiter's reasoning, stacked over their head — one gizmo per line.
     *
     * <p>A text gizmo is a SINGLE line: an embedded newline renders as nothing. The server sends
     * the readout already split ({@code BrainDriver.describeLines}) rather than a joined string,
     * because the separators are a chat format's detail and parsing them back out here went
     * quietly wrong whenever that format changed.
     */
    private static void drawBrain(
            DebugViewPayload view, Entity person, float partialTick, int[] line) {
        boolean first = true;
        for (String text : view.brain()) {
            if (text.isBlank()) {
                continue;
            }
            // The first line is "auto"/"manual" — the headline fact of who is driving.
            overhead(person, partialTick, line[0]++, text,
                    BRAIN_TEXT_COLOR, first ? TITLE_SCALE : DETAIL_SCALE);
            first = false;
        }
    }

    /**
     * One stacked line of floating text above a Person's head.
     *
     * <p>Not {@code Gizmos.billboardTextOverMob}, which anchors at
     * {@code getBlockX() + 0.5} / {@code getBlockZ() + 0.5} and the raw {@code getY()}: over a
     * WALKING Person that snaps from block centre to block centre. This keeps vanilla's layout —
     * spacing, left alignment, always-on-top — off their interpolated position instead.
     *
     * <p>Height comes from the body's own box, not vanilla's flat {@code 2.4}, so it follows a
     * crouch and clears the name tag every Person carries (vanilla's debug targets have none).
     */
    private static void overhead(Entity person, float partialTick, int line,
                                 String text, int color, float scale) {
        Vec3 at = above(person.getPosition(partialTick), person.getBbHeight(), line);
        Gizmos.billboardText(text, at, TextGizmo.Style.forColor(color)
                        .withScale(scale)
                        .withLeftAlignment(TEXT_LEFT_ALIGN))
                .setAlwaysOnTop();
    }

    /**
     * Where line {@code line} of a text stack belongs for a body of {@code height} standing at
     * {@code feet} — the single answer to "put this above them".
     *
     * <p>Every label anchored to a body goes through here: text drawn AT a body's position competes
     * with the skin behind it and with the name tag every Person carries.
     */
    private static Vec3 above(Vec3 feet, double height, int line) {
        return new Vec3(feet.x,
                feet.y + height + NAME_TAG_CLEARANCE + line * TEXT_LINE_STEP,
                feet.z);
    }

    /**
     * What they believe is out there: the bounds box they remember, a marker on the anchor they
     * would actually walk to, and a word for what it is. A stale belief greys out — the ghost of a
     * grove since chopped down is the knowledge store working correctly.
     *
     * <p><b>The label is the point of the layer.</b> Boxes alone say only where and how stale, and
     * a settler in a worked area stands in a field of overlapping ones. The text sits at the top of
     * the anchor's stub so it clears the box's own lines, in the belief's colour.
     */
    private static void drawBeliefs(DebugViewPayload view) {
        for (DebugViewPayload.Belief belief : view.beliefs()) {
            int color = belief.stale() ? GHOST_COLOR : kindColor(belief.kind());
            Gizmos.cuboid(AABB.encapsulatingFullBlocks(belief.min(), belief.max()),
                    GizmoStyle.stroke(color, THIN));
            // The anchor gets a stub of a column so it stays findable inside a big bounds box.
            Vec3 anchor = centre(belief.anchor());
            Gizmos.line(anchor, anchor.add(0.0, BELIEF_STUB, 0.0), color, PATH_WIDTH);
            Gizmos.point(anchor, color, 0.2F);
            if (!belief.label().isEmpty()) {
                Gizmos.billboardText(belief.label(), labelAt(belief, anchor),
                                TextGizmo.Style.forColor(color)
                                        .withScale(DETAIL_SCALE)
                                        .withLeftAlignment(TEXT_LEFT_ALIGN))
                        // A belief is a claim ABOUT the blocks, so being hidden by them is exactly
                        // backwards — and the commonest belief is a tree, a wall of leaves.
                        .setAlwaysOnTop();
            }
        }
    }

    /**
     * Where a belief's label goes: clear of the TOP OF the THING, not of its anchor.
     *
     * <p>A grove's anchor is its lowest trunk log, so a label a stub-height above it sits inside
     * the canopy (decision: Luiz). Riding the bounds instead clears whatever the belief actually
     * covers, while a single-cell belief still gets its label just above the marker.
     */
    private static Vec3 labelAt(DebugViewPayload.Belief belief, Vec3 anchor) {
        double top = Math.max(belief.max().getY() + 1, anchor.y + BELIEF_STUB);
        return new Vec3(anchor.x, top + BELIEF_LABEL_CLEARANCE, anchor.z);
    }

    /**
     * Who they know about, and the eyes that found them: a line per peer from their own eyes,
     * coloured by which channel is carrying the perception, plus the view cone at its configured
     * angle and range. A REMEMBERED peer's line points at where they last SAW them.
     */
    private static void drawPeers(DebugViewPayload view, @Nullable Entity person,
                                  ClientLevel level, float partialTick) {
        if (person == null) {
            return;
        }
        Vec3 eye = person.getEyePosition(partialTick);
        drawCone(eye, person.getYHeadRot(), view.sight().coneDegrees(),
                0, view.sight().senseRadius(), CONE_COLOR);
        for (DebugViewPayload.PeerMark peer : view.peers()) {
            int color = awarenessColor(peer.awareness());
            // The live body when the server sent one (a SEEN peer, whose cell is a live sample and
            // so may as well be drawn smoothly); otherwise the believed cell exactly as sent, which
            // for a HEARD or REMEMBERED peer is the whole point.
            Entity body = peer.entityId() == DebugViewPayload.PeerMark.NO_BODY
                    ? null
                    : level.getEntity(peer.entityId());
            Vec3 feet = body != null ? body.getPosition(partialTick) : floorCentre(peer.pos());
            double height = body != null ? body.getBbHeight() : ASSUMED_BODY_HEIGHT;
            // The LINE aims at mid-body — one drawn to the feet reads as pointing straight past
            // someone — but the LABEL goes above the head, clear of the skin it describes, with
            // the same clearance the selected Person's own stack uses.
            Gizmos.line(eye, feet.add(0.0, height * 0.5, 0.0), color, PATH_WIDTH).setAlwaysOnTop();
            // Two lines: who they think it is, then the whole reading — the name alone answers
            // only "is someone there".
            peerText(peer.name(), above(feet, height, 1), color, TITLE_SCALE);
            peerText(detail(peer), above(feet, height, 0), color, DETAIL_SCALE);
        }
    }

    /**
     * The horizontal view cone: its two edges, and an arc closing them at the outer range.
     *
     * <p>Takes an INNER radius because the two sense tiers are different wedges of one aperture:
     * the near field runs from the body to its range, the far sweep from there to the horizon, and
     * far edges drawn from the eye would claim it sees what it never walks. Only the outer arc is
     * drawn — with both layers up, the near cone's own arc is the inner one.
     */
    private static void drawCone(Vec3 eye, float headYaw, int coneDegrees,
                                 int from, int to, int color) {
        if (coneDegrees <= 0 || to <= from) {
            return;
        }
        double half = Math.toRadians(coneDegrees / 2.0);
        // Minecraft yaw is degrees clockwise from south with -Z as north, so the facing vector is
        // (-sin, cos) — the same convention the sensor's own cone test uses.
        double facing = Math.toRadians(headYaw);
        Vec3 left = rim(eye, facing - half, to);
        Vec3 right = rim(eye, facing + half, to);
        Gizmos.line(rim(eye, facing - half, from), left, color, THIN);
        Gizmos.line(rim(eye, facing + half, from), right, color, THIN);
        Vec3 previous = left;
        for (int i = 1; i <= CONE_SEGMENTS; i++) {
            Vec3 next = rim(eye, facing - half + (2 * half * i / CONE_SEGMENTS), to);
            Gizmos.line(previous, next, color, THIN);
            previous = next;
        }
    }

    /** A point on the cone's rim: flat, at eye height, {@code radius} out along {@code angle}. */
    private static Vec3 rim(Vec3 eye, double angle, int radius) {
        return eye.add(-Math.sin(angle) * radius, 0.0, Math.cos(angle) * radius);
    }

    /**
     * How far they can see, and what stopped them — the far sense drawn as the peers layer draws
     * the near one: a cone, and a line to every reading.
     *
     * <ul>
     *   <li><b>"Why didn't they see that tree?"</b> The ribbon runs through whatever TOPPED each
     *       swept bearing; if the tree is not on it, the thing in front of it is.</li>
     *   <li><b>"Is it behind them?"</b> The cone is gold and the ribbon stops at its edges — the
     *       passive sweep never looks backwards.</li>
     *   <li><b>"Did they run out of world?"</b> A bearing that ended at an unloaded chunk goes
     *       magenta: "I could see no further" is not "there was nothing there".</li>
     * </ul>
     *
     * <p>The ribbon's colour is elevation from their eye — blue below, green level, red above —
     * recomputed every frame rather than sent, so a ridge stays a warm band as they walk up to it.
     */
    private static void drawHorizon(DebugViewPayload.Sight sight, Entity person,
                                    float partialTick, int[] line) {
        Vec3 eye = person.getEyePosition(partialTick);
        drawCone(eye, person.getYHeadRot(), sight.coneDegrees(),
                sight.senseRadius(), sight.horizonRadius(), HORIZON_CONE_COLOR);
        drawSkyline(eye, sight.skyline());
        drawGlimpses(eye, sight.glimpses());
        overhead(person, partialTick, line[0]++, skylineSummary(sight),
                HORIZON_TEXT_COLOR, DETAIL_SCALE);
    }

    /**
     * The skyline itself: a crest point per swept bearing joined into a ribbon, with a sparse fan
     * of sight lines back to the eye.
     *
     * <p>Neighbours are joined; holes are not. Two samples are neighbours only within the sweep's
     * own bin stride — a wider gap is a bearing that came back empty or one not yet reached, and
     * bridging it would draw a skyline across ground nobody looked at. The list is ascending by bin
     * and closes into a ring, so a cone straddling bearing zero joins across the seam.
     */
    private static void drawSkyline(Vec3 eye, List<DebugViewPayload.Bearing> skyline) {
        int count = skyline.size();
        for (int i = 0; i < count; i++) {
            DebugViewPayload.Bearing bearing = skyline.get(i);
            Vec3 crest = crest(bearing.top());
            int color = bearing.truncated() ? TRUNCATED_COLOR : elevationColor(eye, crest);
            Gizmos.point(crest, color, SKYLINE_POINT);
            if (bearing.bin() % RAY_EVERY_BINS == 0) {
                Gizmos.line(eye, crest, fade(color, true), THIN);
            }
            // Closing the ring costs one extra pair and is what carries the seam; with only two
            // samples that pair is the segment already drawn, so it stops at three.
            if (count < 2 || (i == count - 1 && count < 3)) {
                continue;
            }
            DebugViewPayload.Bearing next = skyline.get((i + 1) % count);
            if (!neighbours(bearing.bin(), next.bin())) {
                continue;
            }
            // A segment either side of a truncation wears the truncation's colour: the break is
            // the fact, and it belongs to the join as much as to the sample.
            int segment = bearing.truncated() || next.truncated()
                    ? TRUNCATED_COLOR
                    : elevationColor(eye, crest);
            Gizmos.line(crest, crest(next.top()), segment, SKYLINE_WIDTH);
        }
    }

    /** Whether two bearings are adjacent samples of the same sweep rather than sides of a hole. */
    private static boolean neighbours(int bin, int other) {
        int gap = Math.abs(bin - other);
        return Math.min(gap, HorizonBuffer.BINS - gap) <= HorizonScanner.BIN_STRIDE;
    }

    /**
     * The gist tier, drawn as a peer is: a line from the eye, a box round the cell, and what they
     * took it for — labelled with the range it was taken at, and magenta like a bearing that ran
     * out of world.
     */
    private static void drawGlimpses(Vec3 eye, List<DebugViewPayload.Glimpse> glimpses) {
        for (DebugViewPayload.Glimpse glimpse : glimpses) {
            Vec3 crest = crest(glimpse.at());
            Gizmos.line(eye, crest, GLIMPSE_COLOR, PATH_WIDTH).setAlwaysOnTop();
            Gizmos.cuboid(glimpse.at(), GizmoStyle.stroke(GLIMPSE_COLOR, THIN)).setAlwaysOnTop();
            Gizmos.billboardText(glimpse.label(), crest.add(0.0, GLIMPSE_LABEL_CLEARANCE, 0.0),
                            TextGizmo.Style.forColorAndCentered(GLIMPSE_COLOR)
                                    .withScale(DETAIL_SCALE))
                    .setAlwaysOnTop();
        }
    }

    /** The state of the far sense in one line — the numbers the drawing cannot show by itself. */
    private static String skylineSummary(DebugViewPayload.Sight sight) {
        int cutShort = 0;
        for (DebugViewPayload.Bearing bearing : sight.skyline()) {
            if (bearing.truncated()) {
                cutShort++;
            }
        }
        return String.format(Locale.ROOT, "skyline r%d %d° · %d bearings · %d glimpsed%s",
                sight.horizonRadius(), sight.coneDegrees(), sight.skyline().size(),
                sight.glimpses().size(),
                cutShort > 0 ? " · " + cutShort + " ran out of world" : "");
    }

    /**
     * A crest's drawing point: the middle of the TOP FACE of the cell that topped the bearing.
     * The block's own top is the line the eye actually stops at, and it is the one place a ribbon
     * segment can sit without disappearing into the terrain it is describing.
     */
    private static Vec3 crest(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    /**
     * How high this crest stands from their eye, as a colour — cold below, green about level, warm
     * above. Computed from the LIVE eye rather than from the snapshot's, so the ramp answers "how
     * does this look from where they are standing now".
     */
    private static int elevationColor(Vec3 eye, Vec3 crest) {
        double flat = Math.hypot(crest.x - eye.x, crest.z - eye.z);
        double tan = flat < 1.0 ? HOTTEST_TAN : (crest.y - eye.y) / flat;
        double ramp = Mth.clamp((tan - COLDEST_TAN) / (HOTTEST_TAN - COLDEST_TAN), 0.0, 1.0);
        return Mth.hsvToArgb(0.6F * (1.0F - (float) ramp), 0.85F, 1.0F, SKYLINE_ALPHA);
    }

    /** A centred, always-visible line of peer text. */
    private static void peerText(String text, Vec3 at, int color, float scale) {
        Gizmos.billboardText(text, at,
                        TextGizmo.Style.forColorAndCentered(color).withScale(scale))
                .setAlwaysOnTop();
    }

    /**
     * The reading under a peer's name: everything they have on them, then how they have it.
     *
     * <p>The awareness tag follows the chat readouts' convention: SEEN is unmarked, {@code [heard]}
     * and {@code [remembered]} are called out, because those are the readings that can be wrong.
     * Spelled out as well as colour-coded: a grey line reads as stale only beside a green one.
     */
    private static String detail(DebugViewPayload.PeerMark peer) {
        Being.Awareness[] values = Being.Awareness.values();
        Being.Awareness awareness = peer.awareness() >= 0 && peer.awareness() < values.length
                ? values[peer.awareness()]
                : Being.Awareness.REMEMBERED;
        String tag = awareness == Being.Awareness.SEEN
                ? ""
                : " [" + awareness.name().toLowerCase(Locale.ROOT) + "]";
        return String.format(Locale.ROOT, "%s%s · %.1fm", peer.tell(), tag, peer.distance());
    }

    private static int moveColor(int move) {
        MoveType[] moves = MoveType.values();
        if (move < 0 || move >= moves.length) {
            return WALK_COLOR;
        }
        return switch (moves[move]) {
            case WALK -> WALK_COLOR;
            case JUMP -> JUMP_COLOR;
            case DROP -> DROP_COLOR;
            case LEAP -> LEAP_COLOR;
            case SWIM -> SWIM_COLOR;
        };
    }

    /**
     * What each kind of belief is drawn in. A consuming mod registers a colour for the kinds it
     * declared; anything unregistered still draws, in the ghost colour — the view should never
     * hide a memory because nobody picked a colour for it.
     */
    private static final Map<String, Integer> KIND_COLORS = new ConcurrentHashMap<>();

    static {
        KIND_COLORS.put(PoiKind.HERD.key(), HERD_COLOR); // Anima's own kind
    }

    /** Declares how {@code kind} should be drawn by the memory layer. */
    public static void kindColor(PoiKind kind, int argb) {
        KIND_COLORS.put(kind.key(), argb);
    }

    private static int kindColor(String kind) {
        return KIND_COLORS.getOrDefault(kind, GHOST_COLOR);
    }

    private static int awarenessColor(int awareness) {
        Being.Awareness[] values = Being.Awareness.values();
        if (awareness < 0 || awareness >= values.length) {
            return REMEMBERED_COLOR;
        }
        return switch (values[awareness]) {
            case SEEN -> SEEN_COLOR;
            case HEARD -> HEARD_COLOR;
            case REMEMBERED -> REMEMBERED_COLOR;
        };
    }

    /** Drops a colour to a quarter alpha — how the already-walked part of the path is drawn. */
    private static int fade(int argb, boolean faded) {
        if (!faded) {
            return argb;
        }
        int alpha = Mth.clamp((argb >>> 24) / 4, 16, 255);
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** The centre of a cell, lifted just off its floor — where a waypoint line is drawn through. */
    private static Vec3 centre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + PATH_Y, pos.getZ() + 0.5);
    }

    /** The exact floor centre of a cell — where a body standing in it has its feet. */
    private static Vec3 floorCentre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
