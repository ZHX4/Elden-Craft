package dev.ermc.bridge.client;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.MovingPlatformMotion;
import dev.ermc.bridge.MovingPlatformTerrain;
import dev.ermc.bridge.MovingPlatformBoarding;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ControlState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.client.Minecraft;

/** Predict lift motion locally; normal movement packets carry the resulting feet to the server. */
public final class MovingPlatformClient {
	private static final MovingPlatformMotion MOTION = new MovingPlatformMotion();
	private static final MovingPlatformBoarding BOARDING = new MovingPlatformBoarding();
	private static final GameState STATE = new GameState();
	private static long loggedAt;
	private MovingPlatformClient() {}

	public static void onTeleport() { MOTION.reset(); BOARDING.reset(); }

	public static void tick(Minecraft mc) {
		var map = CoordMap.get();
		if (mc.player == null || mc.level == null || !TerrainManager.isBridgeWorld() || !Overlay.active() || Overlay.hostMode()
			|| !TerrainManager.recallSettled(400) || map == null || !ErLink.get().snapshot(STATE) || map.zone() != STATE.stageId) {
			MOTION.reset(); return;
		}
		BOARDING.refresh(mc.level, map, null);
		var support = map.toMc(STATE.supportPos[0], STATE.supportPos[1], STATE.supportPos[2]);
		boolean valid = STATE.has(Protocol.STATE_SUPPORT_VALID)
			&& Math.abs(mc.player.getX() - support.x) < .6 && Math.abs(mc.player.getZ() - support.z) < .6;
		var step = MOTION.update(valid, STATE.supportEpoch, STATE.supportTravelY / map.unitsPerMeter(), support.y,
			mc.player.getY(), mc.player.onGround(), mc.player.getAbilities().flying, mc.player.getDeltaMovement().y, System.currentTimeMillis());
		if (step.moving()) MovingPlatformTerrain.move(mc.level, mc.player.position(), step.previousFloor(), step.floor());
		if (step.dy() != 0 || step.landed()) {
			mc.player.setPos(mc.player.getX(), MovingPlatformMotion.collisionHeight(step.floor()), mc.player.getZ());
			mc.player.setOnGround(true);
			mc.player.fallDistance = 0;
			if (step.landed()) mc.player.setDeltaMovement(mc.player.getDeltaMovement().multiply(1, 0, 1));
		}
		if (step.moving() && System.currentTimeMillis() - loggedAt >= 1000) {
			loggedAt = System.currentTimeMillis();
			org.slf4j.LoggerFactory.getLogger("erbridge").info("Moving platform: epoch {} travel {} m, floor {} feet {} grounded {}",
				MOTION.epoch(), MOTION.travel(), step.floor(), mc.player.getY(), mc.player.onGround());
		}
	}

	public static void copyControl(ControlState control, float partialTick) {
		control.supportEpoch = MOTION.epoch();
		var map = CoordMap.get();
		// START_CLIENT_TICK moves the player before ClientLevel saves xo/yo/zo.
		// Both rendered endpoints already include this travel. Interpolating the
		// acknowledgement would make native code apply part of it a second time.
		control.supportTravelY = (float)(MOTION.travel() * (map == null ? 1 : map.unitsPerMeter()));
	}
}
