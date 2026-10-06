package dev.ermc.bridge;

/** Contact-local platform travel; walking over a static surface never counts as a ride. */
public final class MovingPlatformMotion {
	private int epoch;
	private double travel, floor;
	private boolean contact;
	private long movedAt, sampledAt;

	public record Step(double dy, double previousFloor, double floor, boolean moving, boolean landed) {}

	public void reset() { contact = false; epoch = 0; travel = 0; movedAt = 0; sampledAt = 0; }
	public int epoch() { return contact ? epoch : 0; }
	public double travel() { return contact ? travel : 0; }
	public boolean moving(long now) { return contact && movedAt != 0 && now - movedAt < 750; }
	public static double collisionHeight(double floor) { return Math.ceil(floor * 16 - 1e-4) / 16; }

	public Step update(boolean valid, int nextEpoch, double nextTravel, double nextFloor,
		double feetY, boolean grounded, boolean flying, long now) {
		return update(valid, nextEpoch, nextTravel, nextFloor, feetY, grounded, flying, grounded ? 0 : 1, now);
	}

	public Step update(boolean valid, int nextEpoch, double nextTravel, double nextFloor,
		double feetY, boolean grounded, boolean flying, double verticalSpeed, long now) {
		if (!valid || flying || !Double.isFinite(nextFloor) || !Double.isFinite(nextTravel)) {
			reset();
			return new Step(0, nextFloor, nextFloor, false, false);
		}
		if (!contact || nextEpoch != epoch) {
			reset();
			double top = collisionHeight(nextFloor);
			// A rising platform may already be above the falling feet by the
			// first client tick that sees it. Catch that crossing on attachment.
			if (feetY >= top - .45 && feetY <= top + .2) {
				contact = true; epoch = nextEpoch; travel = nextTravel; floor = nextFloor; sampledAt = now;
				// Native support also reports ordinary ground. An elevated footprint
				// corner on a static slope must never catch or reposition the player.
				boolean landed = Math.abs(nextTravel) > .002
					&& (grounded ? feetY < top - .01 : feetY <= top + .08 && verticalSpeed <= 0);
				if (landed) {
					movedAt = now;
					return new Step(top - feetY, nextFloor, nextFloor, true, true);
				}
			}
			return new Step(0, nextFloor, nextFloor, false, false);
		}
		double previous = floor, change = nextTravel - travel;
		double previousTop = collisionHeight(previous), nextTop = collisionHeight(nextFloor);
		long elapsed = now - sampledAt;
		if (elapsed < 0 || (elapsed > 250 && Math.abs(change) > .05) || Math.abs(change) > .05 + 24 * Math.min(elapsed, 250) / 1000.0
			|| (grounded && Math.abs(feetY - previousTop) > .25 && Math.abs(feetY - nextTop) > .2)
			|| feetY > Math.max(previousTop, nextTop) + 4.0 || feetY < Math.min(previousTop, nextTop) - .5) {
			reset();
			return new Step(0, previous, nextFloor, false, false);
		}
		boolean movingSurface = moving(now) || Math.abs(change) > .002;
		boolean landed = movingSurface && !grounded && verticalSpeed <= nextTop - previousTop && feetY <= nextTop + .08
			&& feetY >= previousTop - .5;
		double dy = landed ? nextTop - feetY : grounded && Math.abs(change) > .002 ? nextTop - previousTop : 0;
		travel = nextTravel; floor = nextFloor; sampledAt = now;
		if (Math.abs(change) > .002) movedAt = now;
		return new Step(dy, previous, nextFloor, moving(now), landed);
	}
}
