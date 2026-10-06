package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Uses live native contacts in Minecraft's normal collision and step solver.
 * Neither terrain generation nor block-entity packets can delay these contacts. */
public final class NativeTerrainCollision {
    private NativeTerrainCollision() {}
    private static volatile boolean traceMovement;
    public static void traceMovement(boolean enabled) { traceMovement = enabled; }
    public static boolean tracingMovement() { return traceMovement; }
    private record Contact(AABB bounds, VoxelShape shape, boolean floor, boolean wall) {}
    // Each level is owned by its game thread. Never hold a process-wide monitor
    // while constructing shapes: server refinement must not stop client movement.
    private static final Map<Level, Snapshot> LEVELS = new ConcurrentHashMap<>();
    private static final class Prepared {
        final VoxelShape original, filtered;
        List<TerrainWallGeometry.Plane> planes = List.of();
        VoxelShape smooth;
        Prepared(VoxelShape original, VoxelShape filtered) { this.original = original; this.filtered = filtered; }
        VoxelShape forMovement(List<TerrainWallGeometry.Plane> active, BlockPos pos) {
            if (active.isEmpty()) return filtered;
            if (smooth == null || !active.equals(planes)) {
                planes = active;
                smooth = TerrainContactGeometry.removeAir(filtered, active.stream().map(TerrainWallGeometry.Plane::replacementBand).toList(),
                    pos.getX(), pos.getY(), pos.getZ());
            }
            return smooth;
        }
    }
    private static final class Snapshot {
        final float[] data = new float[Protocol.MAX_CONTACTS * Protocol.CONTACT_FLOATS];
        final float[] nativeOrigin = new float[6];
        final int[] metadata = new int[3];
        final Map<java.util.UUID, Vec3> previousFeet = new java.util.HashMap<>();
        final Map<java.util.UUID, List<TerrainWallGeometry.Plane>> activeWalls = new HashMap<>();
        List<Contact> contacts = List.of();
        List<TerrainWallGeometry.Plane> walls = List.of();
        List<AABB> air = List.of();
        List<AABB> completeAir = List.of();
        final Map<BlockPos, Prepared> prepared = new HashMap<>();
        CoordMap.Mapping map;
        Vec3 origin;
        Vec3 previousNativeFeet;
        int sequence;
        long receivedAt;
        long lastBlockedLog;
    }

    public static void reset() { LEVELS.clear(); }

    /** Called before client movement and after server terrain updates. */
    public static void refresh(Level level, CoordMap.Mapping map) {
        Snapshot snapshot = LEVELS.computeIfAbsent(level, ignored -> new Snapshot());
        if (!map.equals(snapshot.map)) {
            snapshot.contacts = List.of(); snapshot.walls = List.of(); snapshot.air = List.of(); snapshot.completeAir = List.of(); snapshot.prepared.clear();
            snapshot.previousFeet.clear(); snapshot.activeWalls.clear(); snapshot.sequence = 0;
            snapshot.map = map; snapshot.receivedAt = 0;
        }
        int count = ErLink.get().readContacts(snapshot.data, snapshot.nativeOrigin, snapshot.metadata, snapshot.sequence);
        if (count == -2) { prepare(level, snapshot); return; }
        if (count < 0 || snapshot.metadata[0] != map.zone()) {
            // A seqlock retry is not removal of the last complete snapshot.
            return;
        }
        Vec3 origin = map.toMc(snapshot.nativeOrigin[0], snapshot.nativeOrigin[1], snapshot.nativeOrigin[2]);
        if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)) return;
        List<AABB> floors = new ArrayList<>(), walls = new ArrayList<>(), ceilings = new ArrayList<>(), air = new ArrayList<>();
        double reach = .8125 / map.unitsPerMeter();
        AABB local = new AABB(origin.x - reach, origin.y - 1 / map.unitsPerMeter(), origin.z - reach,
            origin.x + reach, origin.y + 2.75 / map.unitsPerMeter(), origin.z + reach);
        for (int i = 0; i < count; i++) {
            int at = i * Protocol.CONTACT_FLOATS;
            Vec3 first = map.toMc(snapshot.data[at], snapshot.data[at + 1], snapshot.data[at + 2]);
            Vec3 last = map.toMc(snapshot.data[at + 3], snapshot.data[at + 4], snapshot.data[at + 5]);
            // CoordMap reverses one horizontal axis: order bounds after mapping.
            AABB bounds = new AABB(first, last);
            if (!Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY) || !Double.isFinite(bounds.minZ)
                    || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)
                    || bounds.getXsize() <= 0 || bounds.getYsize() <= 0 || bounds.getZsize() <= 0) continue;
            int kind = (int)snapshot.data[at + 6];
            if (kind == Protocol.CONTACT_CLEAR) {
                if (bounds.intersects(local)) air.add(bounds.intersect(local));
            } else if (kind == Protocol.CONTACT_FLOOR) floors.add(bounds);
            else if (kind == 2) walls.add(bounds);
            else if (kind == 3) ceilings.add(bounds);
        }
        // An empty partial sample cannot revoke the last measured support.
        if (count == 0) return;
        boolean guarded = (snapshot.metadata[2] & 2) == 0 || (snapshot.metadata[2] & 4) != 0;
        if (!guarded) for (Contact previous : snapshot.contacts) {
            if (previous.wall) walls.add(previous.bounds);
            else if (!previous.floor) ceilings.add(previous.bounds);
        }
        List<TerrainWallGeometry.Plane> planes = guarded ? TerrainWallGeometry.fit(walls, origin, map.unitsPerMeter()) : snapshot.walls;
        List<Contact> contacts = new ArrayList<>();
        for (AABB bounds : TerrainContactGeometry.merge(floors)) contacts.add(new Contact(bounds, Shapes.create(bounds), true, false));
        for (AABB bounds : TerrainContactGeometry.merge(walls)) contacts.add(new Contact(bounds, Shapes.create(bounds), false, true));
        for (AABB bounds : TerrainContactGeometry.merge(ceilings)) contacts.add(new Contact(bounds, Shapes.create(bounds), false, false));
        snapshot.contacts = List.copyOf(contacts); snapshot.origin = origin;
        if (!planes.equals(snapshot.walls)) snapshot.prepared.clear();
        snapshot.walls = planes;
        boolean complete = (snapshot.metadata[2] & 2) == 0 || (snapshot.metadata[2] & 8) != 0;
        // A new partial batch must not restore coarse cubes in air already
        // verified by the last complete batch. Keep that evidence local; new
        // measured contacts independently restore a closing door or higher step.
        if (!complete) for (AABB previous : snapshot.completeAir)
            if (previous.intersects(local)) air.add(previous.intersect(local));
        List<AABB> updatedAir = TerrainContactGeometry.merge(air);
        if (complete) snapshot.completeAir = updatedAir;
        if (!updatedAir.equals(snapshot.air)) snapshot.prepared.clear();
        snapshot.air = updatedAir;
        snapshot.previousNativeFeet = map.toMc(snapshot.nativeOrigin[4], snapshot.nativeOrigin[3], snapshot.nativeOrigin[5]);
        snapshot.sequence = snapshot.metadata[1]; snapshot.receivedAt = System.currentTimeMillis();
        prepare(level, snapshot);
    }

    private static void prepare(Level level, Snapshot snapshot) {
        if (snapshot.origin == null || snapshot.air.isEmpty()) return;
        BlockPos centre = BlockPos.containing(snapshot.origin);
        for (int x = centre.getX() - 1; x <= centre.getX() + 1; x++)
        for (int z = centre.getZ() - 1; z <= centre.getZ() + 1; z++) {
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            for (int y = centre.getY() - 1; y <= centre.getY() + 3; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                var state = level.getBlockState(pos);
                if (!ErBridgeMod.isTerrain(state)) continue;
                VoxelShape original = state.getShape(level, pos);
                Prepared previous = snapshot.prepared.get(pos);
                if (previous != null && previous.original == original) continue;
                snapshot.prepared.put(pos, new Prepared(original,
                    TerrainContactGeometry.removeAir(original, snapshot.air, x, y, z)));
            }
        }
    }

    /** Augments the same collider list used for falling, wall sliding and stairs. */
    public static List<VoxelShape> append(Entity entity, Level level, AABB area, List<VoxelShape> existing) {
        if (entity instanceof Player player && player.isSpectator()) return existing;
        Snapshot snapshot = valid(level, area.getCenter());
        if (snapshot == null) return existing;
        ArrayList<VoxelShape> result = null;
        for (Contact contact : snapshot.contacts) if (contact.bounds.intersects(area)) {
            if (contact.wall && entity instanceof Player player && snapshot.activeWalls.getOrDefault(player.getUUID(), List.of()).stream().anyMatch(p -> p.supportsMovement(player.getBoundingBox(), area)
                    && p.contains(contact.bounds))) continue;
            if (result == null) result = new ArrayList<>(existing);
            result.add(contact.shape);
        }
        return result == null ? existing : result;
    }

    public static BlockHitResult clip(Level level, ClipContext context, BlockHitResult vanilla) {
        Snapshot snapshot = valid(level, context.getFrom());
        if (snapshot == null) return vanilla;
        Vec3 from = context.getFrom(), to = context.getTo();
        double nearest = vanilla.getType() == HitResult.Type.MISS ? from.distanceToSqr(to) : from.distanceToSqr(vanilla.getLocation());
        BlockHitResult result = vanilla;
        AABB area = new AABB(from, to).inflate(1e-6);
        for (Contact contact : snapshot.contacts) if (contact.bounds.intersects(area)) {
            BlockHitResult hit = contact.shape.clip(from, to, BlockPos.ZERO);
            if (hit != null && from.distanceToSqr(hit.getLocation()) < nearest) {
                nearest = from.distanceToSqr(hit.getLocation());
                Vec3 solid = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(1e-5));
                result = new BlockHitResult(hit.getLocation(), hit.getDirection(), BlockPos.containing(solid), hit.isInside());
            }
        }
        return result;
    }

    /** Projects only motion entering a measured wall; tangent speed and vertical
     * movement are unchanged. Minecraft still resolves other blocks and steps. */
    public static Vec3 slide(Entity entity, Vec3 movement) {
        if (!(entity instanceof Player player) || player.isSpectator()) return movement;
        Snapshot snapshot = valid(player.level(), player.position());
        if (snapshot == null) return movement;
        AABB body = player.getBoundingBox();
        var active = snapshot.walls.stream().filter(p -> p.supportsMovement(body, body.expandTowards(movement))
            && p.covers(body.move(movement))).toList();
        snapshot.activeWalls.put(player.getUUID(), active);
        return TerrainWallGeometry.slide(movement, body, active);
    }

    public static void finishMove(Entity entity) {
        if (entity instanceof Player player) {
            Snapshot snapshot = LEVELS.get(player.level());
            if (snapshot != null) snapshot.activeWalls.remove(player.getUUID());
        }
    }

    /** Optional development trace: records the actual move, without moving the
     * player or submitting physics queries. At most one record per 3 s/level. */
    public static void traceMove(Entity entity, Vec3 start, Vec3 requested, Vec3 projected) {
        if (!traceMovement || !(entity instanceof Player player) || player.isSpectator()) return;
        Snapshot snapshot = valid(player.level(), start);
        Vec3 actual = player.position().subtract(start);
        if (snapshot == null || requested.horizontalDistanceSqr() < .001
                || actual.horizontalDistanceSqr() >= requested.horizontalDistanceSqr() * .01) return;
        long now = System.currentTimeMillis();
        if (now - snapshot.lastBlockedLog < 3000) return;
        snapshot.lastBlockedLog = now;
        AABB body = player.getBoundingBox().move(start.subtract(player.position())), area = body.expandTowards(requested);
        List<String> blocks = new ArrayList<>();
        int checked = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            if (++checked > 64 || blocks.size() >= 4) break;
            if (!player.level().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            var state = player.level().getBlockState(pos);
            if (!ErBridgeMod.isTerrain(state)) continue;
            VoxelShape shape = collision(player, player.level(), pos, state.getShape(player.level(), pos));
            if (!shape.isEmpty() && shape.bounds().move(pos).intersects(area)) blocks.add(pos + " " + shape.bounds());
        }
        var nativeWalls = snapshot.contacts.stream().filter(c -> !c.floor && c.bounds.intersects(area))
            .limit(4).map(c -> c.bounds.toString()).toList();
        org.slf4j.LoggerFactory.getLogger("erbridge").info(
            "[collision] blocked {} at {}: requested {}, projected {}, actual {}; origin {}, age {} ms, flags {}, planes {}; terrain {}, native {}",
            player.getClass().getSimpleName(), start, requested, projected, actual, snapshot.origin,
            now - snapshot.receivedAt, snapshot.metadata[2], snapshot.walls.size(), blocks, nativeWalls);
    }

    /** A teleport is an arrival, never a swept fall through intervening floors. */
    public static void teleported(Player player) {
        Snapshot snapshot = LEVELS.get(player.level());
        if (snapshot != null) {
            snapshot.previousFeet.put(player.getUUID(), player.position());
            snapshot.activeWalls.remove(player.getUUID());
        }
    }

    /** Retain every part of generated terrain that fresh rays did not verify as
     * air. Partial batches, packet delays and flight never erase entire blocks. */
    public static VoxelShape collision(Entity entity, Level level, BlockPos pos, VoxelShape original) {
        if (!(entity instanceof Player player) || player.isSpectator()) return original;
        Snapshot snapshot = valid(level, player.position());
        if (snapshot == null) return original;
        Prepared prepared = snapshot.prepared.get(pos);
        return prepared != null && prepared.original == original
            ? prepared.forMovement(snapshot.activeWalls.getOrDefault(player.getUUID(), List.of()), pos) : original;
    }

    /** Swept recovery covers a floor crossing before the first native snapshot arrived. */
    public static void recoverLanding(Player player) {
        Snapshot snapshot = LEVELS.get(player.level());
        if (snapshot == null) return;
        Vec3 current = player.position(), previous = snapshot.previousFeet.put(player.getUUID(), current);
        if (valid(player.level(), current) == null || !TerrainManager.recallSettled(400)
                || player.isSpectator() || player.getAbilities().flying) return;
        // The first complete snapshot may arrive after several falling ticks.
        // Its original control pose is still direct evidence of that crossing.
        if (previous == null && snapshot.previousNativeFeet != null
                && snapshot.previousNativeFeet.distanceToSqr(current) <= 256) previous = snapshot.previousNativeFeet;
        double floor = Double.NEGATIVE_INFINITY;
        AABB footprint = player.getBoundingBox();
        for (Contact contact : snapshot.contacts) {
            AABB bounds = contact.bounds;
            if (contact.floor && bounds.maxX > footprint.minX && bounds.minX < footprint.maxX
                    && bounds.maxZ > footprint.minZ && bounds.minZ < footprint.maxZ
                    && previous != null && current.subtract(previous).lengthSqr() <= 256
                    && TerrainLanding.crossed(previous.y, current.y, bounds.maxY, false)) {
                double at = (previous.y - bounds.maxY) / (previous.y - current.y);
                double x = previous.x + (current.x - previous.x) * at, z = previous.z + (current.z - previous.z) * at;
                if (x + footprint.getXsize() * .5 > bounds.minX && x - footprint.getXsize() * .5 < bounds.maxX
                        && z + footprint.getZsize() * .5 > bounds.minZ && z - footprint.getZsize() * .5 < bounds.maxZ)
                    floor = Math.max(floor, bounds.maxY);
            }
        }
        if (Double.isFinite(floor)) {
            player.setPos(current.x, floor + 1e-5, current.z);
            player.setDeltaMovement(player.getDeltaMovement().multiply(1, 0, 1));
            player.setOnGround(true); player.stopFallFlying(); player.fallDistance = 0;
            snapshot.previousFeet.put(player.getUUID(), player.position());
        }
    }

    private static Snapshot valid(Level level, Vec3 feet) {
        Snapshot snapshot = LEVELS.get(level);
        if (!TerrainManager.isBridgeWorld() || !ErLink.get().alive() || snapshot == null || snapshot.origin == null
                || !snapshot.map.equals(CoordMap.get()) || System.currentTimeMillis() - snapshot.receivedAt > 2000) return null;
        // A measured wall ahead stays usable as the player approaches it. The
        // collider's own world bounds decide whether it intersects this move.
        return snapshot;
    }
}
