package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Updates verified platform cells before the player steps onto them. */
public final class MovingPlatformBoarding {
	private final float[] cells = new float[Protocol.MAX_PLATFORM_CELLS * Protocol.PLATFORM_FLOATS];
	private final int[] metadata = new int[2];
	private int sequence;
	private long receivedAt;
	public void reset() { receivedAt = 0; }

	public void refresh(Level level, CoordMap.Mapping map, LongOpenHashSet columns) {
		int count = ErLink.get().readPlatforms(cells, metadata);
		if (count < 0 || metadata[0] != map.zone()) { reset(); return; }
		long now = System.currentTimeMillis();
		if (metadata[1] != sequence) { sequence = metadata[1]; receivedAt = now; }
		if (now - receivedAt > 250) { reset(); return; }
		for (int i = 0; i < count; i++) {
			int p = i * Protocol.PLATFORM_FLOATS;
			var centre = map.toMc(cells[p], cells[p + 2], cells[p + 1]);
			double radius = .25 / map.unitsPerMeter();
			double low = map.toMc(cells[p], cells[p + 4], cells[p + 1]).y;
			double high = map.toMc(cells[p], cells[p + 5], cells[p + 1]).y;
			int flags = (int)cells[p + 6];
			if (!Double.isFinite(low) || !Double.isFinite(high) || high < low || high - low > 3 / map.unitsPerMeter()) continue;
			// Static contacts go straight to the movement solver. Sparse clearance
			// patches must not flatten stairs or carve fine wall/floor occupancy.
			if ((flags & Protocol.PLATFORM_STATIC_CLEARANCE) != 0) continue;
			MovingPlatformTerrain.refresh(level, centre, radius, low, high, (flags & 1) != 0);
			if ((flags & 2) != 0) {
				double previous = map.toMc(cells[p], cells[p + 3], cells[p + 1]).y;
				MovingPlatformTerrain.refresh(level, centre, radius, previous - .08 / map.unitsPerMeter(), previous + .08 / map.unitsPerMeter(), false);
			}
            if (columns != null && (flags & Protocol.PLATFORM_STATIC_CLEARANCE) == 0) for (int x = Mth.floor(centre.x - radius); x <= Mth.floor(centre.x + radius); x++)
				for (int z = Mth.floor(centre.z - radius); z <= Mth.floor(centre.z + radius); z++) columns.add(ChunkPos.asLong(x, z));
		}
	}

}
