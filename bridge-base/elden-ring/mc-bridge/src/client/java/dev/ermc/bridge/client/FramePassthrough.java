package dev.ermc.bridge.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.ermc.bridge.link.ControlState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Sends Minecraft's frames to the host game, which draws them into its own frame (see
 * elden-ring/er-bridge). Per frame:
 * <ol>
 *   <li>after the world is drawn (before the hand): async readback of world color + depth,
 *   then the color buffer is cleared so the hand draws onto a transparent layer;</li>
 *   <li>after the hand: async readback of the hand layer (the host game lights it like the
 *   world, but never hides it behind its walls), then the color buffer is cleared again;</li>
 *   <li>at the end of the frame: async readback of the HUD layer (drawn as is);</li>
 *   <li>next frame: the readbacks are copied into a frames.shm slot, and only then is that
 *   frame's camera pose handed to the host game, so it always has the matching pixels for the
 *   pose it renders.</li>
 * </ol>
 * Minecraft's own window then shows nothing (it only keeps the input focus).
 */
public final class FramePassthrough {
	private FramePassthrough() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
	private static final String PATH = dev.ermc.bridge.link.BridgePaths.file("frames.shm");
	private static final int MAGIC = 0x524D484D;
	private static final int MAX_W = 3840;
	private static final int MAX_H = 2160;
	private static final int SLOTS = 3;
	private static final int HDR = 0x100;
	/** Layout version 3: four layers per slot, up to 3840x2160 for Windows borderless displays. */
	private static final int VERSION = 3;
	private static final long SLOT_SIZE = HDR + (long) MAX_W * MAX_H * 16;
	private static final long FILE_SIZE = 0x1000 + SLOTS * SLOT_SIZE;

	/**
	 * User toggle (F6). Effective only once the host game confirms it is compositing (its
	 * D3D12 Present hook, elden-ring/er-bridge/src/compositor.cpp); until then the separate
	 * overlay window (Overlay.java) shows Minecraft.
	 */
	private static boolean enabled = true;
	private static MappedByteBuffer frames;
	private static boolean failed;

	// Capture buffers stay separate from the three presentation metadata slots.
	private static final int CAPTURES = GpuTransport.SLOTS;
	private static final int[][] PBO = new int[CAPTURES][4];
	private static final long[] READY = new long[CAPTURES];
	private static final boolean[] GPU_FRAME = new boolean[CAPTURES];
	private static final int[] GPU_GENERATION = new int[CAPTURES];
	private static int pboW, pboH;
	private static RenderTarget captureTarget;
	private static float frameScale = 1.0F;
	private static long budgetAt, budgetHostFrame;
	private static int frameBudget = 90;

	/** Avoid rendering extra full-resolution frames that the host cannot display. */
	public static int frameLimit() {
		long now = System.nanoTime();
		long host = ErLink.get().hostFrame();
		if (budgetAt == 0 || host < budgetHostFrame) {
			budgetAt = now; budgetHostFrame = host;
		} else if (now - budgetAt >= 1_000_000_000L) {
			double fps = (host - budgetHostFrame) * 1_000_000_000.0 / (now - budgetAt);
			// Two independent render loops drift in phase. A limit barely above host
			// FPS misses host frames whenever either loop has a small timing spike.
			// Modest headroom keeps one completed capture ready for each host frame.
			int target = Math.max(60, Math.min(96, (int)Math.ceil(fps * 1.4) + 6));
			frameBudget += Math.max(-3, Math.min(3, target - frameBudget));
			budgetAt = now; budgetHostFrame = host;
		}
		return frameBudget;
	}

	/** Changes only the transferred layers; the input window and Elden Ring keep their resolution. */
	public static void setFrameScale(float value) {
		if (!Float.isFinite(value)) return;
		frameScale = Math.max(0.25F, Math.min(1.0F, value));
		LOG.info("Frame passthrough transfer scale {}", frameScale);
	}
	private static final boolean[] PENDING = new boolean[CAPTURES];
	/** The set's hand layer was captured (else the hand, if any, is in the HUD layer). */
	private static final boolean[] HAND = new boolean[CAPTURES];
	private static final long[] FRAME_ID = new long[CAPTURES];
	private static final ControlState[] POSE = new ControlState[CAPTURES];
	static {for(int i=0;i<CAPTURES;i++) POSE[i]=new ControlState();}
	private static final float[][] CLIP = new float[CAPTURES][2];
	private static long statsAt;
	private static int rendered, captures, published, waitingCapture, waitingHost;
	private static boolean worldCaptured;
	private static long frameCounter = System.currentTimeMillis() * 1024L;
	/**
	 * Host-game frame counter at the last capture. The host game takes one pose per frame and
	 * presents it a frame later, so capturing faster than it runs (60 vs ~40 fps in big areas)
	 * would overwrite the slot it still needs, and it would show a frame with the wrong pose.
	 */
	private static long capturedAtHostFrame = Long.MIN_VALUE;
	private static int cur;
	private static long lastOffer;

	public static boolean enabled() {
		return enabled;
	}

	public static void toggle() {
		enabled = !enabled;
	}

	/** True while the host game is drawing our frames, so our own window should stay empty. */
	public static boolean activeInHost() {
		return enabled && Overlay.active() && CameraSync.mode() == CameraSync.Mode.DRIVE_HOST && CameraSync.hostCompositing();
	}

	/** Whether frames are being captured (then CameraSync leaves pose publishing to us). */
	public static boolean wanted() {
		return enabled && !failed && Overlay.active() && !Overlay.hostMode() && CameraSync.mode() == CameraSync.Mode.DRIVE_HOST && CameraSync.driving()
			&& fits(Minecraft.getInstance().getMainRenderTarget());
	}

	private static boolean tooBigLogged;

	/**
	 * Frames larger than a frames.shm slot can't be sent. Then nothing is captured and the
	 * camera pose goes to the host game directly (overlay window mode) instead of never at all.
	 */
	private static boolean fits(RenderTarget main) {
		boolean fits = main.width <= MAX_W && main.height <= MAX_H;
		if (!fits && !tooBigLogged) {
			tooBigLogged = true;
			LOG.warn("Frame {}x{} is larger than {}x{}; showing Minecraft in its own window instead of inside Elden Ring",
				main.width, main.height, MAX_W, MAX_H);
		}
		return fits;
	}

	private static boolean open() {
		if (frames != null) {
			return true;
		}
		try {
			Path p = Path.of(PATH);
			Files.createDirectories(p.getParent());
			try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
				if (ch.size() < FILE_SIZE) {
					ch.write(ByteBuffer.wrap(new byte[1]), FILE_SIZE - 1);
				}
				frames = ch.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
				frames.order(ByteOrder.LITTLE_ENDIAN);
			}
			// Invalidate old sessions before advertising the larger slot layout.
			INT.setRelease(frames, 0, 0);
			// The host and its shared GPU fences may outlive a Minecraft restart.
			// New captures must be newer than the last frame they have consumed.
			frameCounter = Math.max(frameCounter, (long) LONG.getAcquire(frames, 0x10));
			for (int slot = 0; slot < SLOTS; slot++) {
				int base = (int) (0x1000 + slot * SLOT_SIZE);
				for (int i = 0; i < HDR; i++) frames.put(base + i, (byte) 0);
			}
			frames.putInt(4, VERSION);
			INT.setRelease(frames, 0, MAGIC);
			LOG.info("Frame passthrough: mapped {} ({} MB)", PATH, FILE_SIZE >> 20);
			return true;
		} catch (IOException | RuntimeException e) {
			LOG.warn("Frame passthrough unavailable: {}", e.toString());
			failed = true;
			return false;
		}
	}

	private static void ensurePbos(int w, int h) {
		if (w == pboW && h == pboH && PBO[0][0] != 0) {
			return;
		}
		for (int[] set : PBO) {
			for (int i = 0; i < set.length; i++) {
				if (set[i] != 0) {
					GL15.glDeleteBuffers(set[i]);
				}
				set[i] = GL15.glGenBuffers();
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, set[i]);
				GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
			}
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		for (int i=0; i<PBO.length; i++) {
			PENDING[i] = false;
			if (READY[i] != 0) GL32.glDeleteSync(READY[i]);
			READY[i] = 0;
		}
		pboW = w;
		pboH = h;
		if (captureTarget != null) captureTarget.destroyBuffers();
		captureTarget = new TextureTarget(w, h, true, Minecraft.ON_OSX);
		Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
		LOG.info("Frame passthrough transfer resolution {}x{}", w, h);
	}

	private static void readInto(RenderTarget main, int pbo, int format, int type) {
		if (main.width != pboW || main.height != pboH) {
			// Scale on the GPU before readback. No CPU resize, no changes to mouse/GUI coordinates.
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, captureTarget.frameBufferId);
			GL30.glBlitFramebuffer(0, 0, main.width, main.height, 0, 0, pboW, pboH,
					format == GL11.GL_DEPTH_COMPONENT ? GL11.GL_DEPTH_BUFFER_BIT : GL11.GL_COLOR_BUFFER_BIT,
					GL11.GL_NEAREST);
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, captureTarget.frameBufferId);
		} else {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
		GL11.glReadPixels(0, 0, pboW, pboH, format, type, 0L);
		main.bindWrite(false);
	}

	/** GameRenderer.renderLevel, right after the level (before the hand). */
	public static void afterWorld(Minecraft mc) {
		worldCaptured = false;
		if (!wanted() || !open()) {
			return;
		}
		long hostFrame = ErLink.get().hostFrame();
		if (hostFrame == capturedAtHostFrame) {
			return;  // the host game hasn't shown a frame since the last capture
		}
		capturedAtHostFrame = hostFrame;
		RenderTarget main = mc.getMainRenderTarget();
		ensurePbos(Math.max(1, Math.round(main.width * frameScale)), Math.max(1, Math.round(main.height * frameScale)));
		cur = (int) (frameCounter % PBO.length);
		if (PENDING[cur]) {waitingCapture++;return;} // Never wait for the GPU here.
		GPU_FRAME[cur]=frameScale==1.0F && GpuTransport.open(frames,pboW,pboH);
		if(GPU_FRAME[cur]) {
			if(!GpuTransport.available(cur)) {waitingHost++;return;}
			GPU_GENERATION[cur]=GpuTransport.generation();
			GpuTransport.begin(cur);
		}
		HAND[cur] = false;
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
		captureLayer(main,0,GL12.GL_BGRA,GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
		captureLayer(main,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		// The hand and screen effects, then the HUD, go onto transparent layers over the world.
		clearColor(main);
		CameraSync.copyCurrentPose(POSE[cur]);
		CLIP[cur][0] = 0.05F;
		CLIP[cur][1] = mc.gameRenderer.getDepthFar();
		worldCaptured = true;
		captures++;
	}

	private static void clearColor(RenderTarget main) {
		main.bindWrite(false);
		GlStateManager._colorMask(true, true, true, true);
		GlStateManager._clearColor(0, 0, 0, 0);
		GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
	}
	private static void captureLayer(RenderTarget main,int layer,int format,int type) {
		if(GPU_FRAME[cur]) GpuTransport.capture(main,cur,layer);
		else readInto(main,PBO[cur][layer],format,type);
	}

	/** GameRenderer.renderLevel, right after the hand: read back the hand layer. */
	public static void afterHand(Minecraft mc) {
		if (!worldCaptured) {
			return;
		}
		RenderTarget main = mc.getMainRenderTarget();
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		captureLayer(main,3,GL12.GL_BGRA,GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		clearColor(main);
		HAND[cur] = true;
	}

	/** End of frame, before the main target is shown: read back the overlay layer, publish last frame. */
	public static void endFrame(Minecraft mc) {
		rendered++;
		long now=System.nanoTime();
		if(statsAt==0) statsAt=now;
		if(now-statsAt>=5_000_000_000L) {
			double seconds=(now-statsAt)/1_000_000_000.0;
			LOG.info("Capture pipeline: rendered {} fps, captured {} fps, published {} fps, pending skips {}, host-buffer skips {}",
				Math.round(rendered/seconds),Math.round(captures/seconds),Math.round(published/seconds),waitingCapture,waitingHost);
			statsAt=now; rendered=captures=published=waitingCapture=waitingHost=0;
		}
		boolean captured = worldCaptured;
		if (worldCaptured) {
			RenderTarget main = mc.getMainRenderTarget();
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
			captureLayer(main,2,GL12.GL_BGRA,GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			FRAME_ID[cur] = ++frameCounter;
			POSE[cur].mcFrame = FRAME_ID[cur];
			PENDING[cur] = true;
			if(GPU_FRAME[cur]) GpuTransport.finish(cur,FRAME_ID[cur]);
			READY[cur] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			GL11.glFlush();
			worldCaptured = false;
		}
		// Publish the capture from an earlier frame (its readback had a frame to finish), even
		// if this frame captured nothing.
		int newestReady = -1;
		for (int set = 0; set < PBO.length; set++) {
			if (PENDING[set] && !(captured && set == cur)) {
				int ready = GL32.glClientWaitSync(READY[set], 0, 0);
				if (ready != GL32.GL_ALREADY_SIGNALED && ready != GL32.GL_CONDITION_SATISFIED) continue;
				GL32.glDeleteSync(READY[set]); READY[set] = 0;
				// Once Minecraft stops driving the host game (F8, map change...), stale frames
				// must not re-pin its camera and hunter.
				if (newestReady < 0 || FRAME_ID[set] > FRAME_ID[newestReady]) {
					if(newestReady>=0 && GPU_FRAME[newestReady]) GpuTransport.discard(newestReady);
					newestReady = set;
				} else if(GPU_FRAME[set]) GpuTransport.discard(set);
				PENDING[set] = false;
			}
		}
		if (newestReady >= 0) {
			if (wanted()) {
				publish(newestReady);
			} else if (GPU_FRAME[newestReady]) {
				// No presentation header was sent, so the host cannot acknowledge this
				// texture. The GL fence is complete: release it here, including the
				// newest pending frame discarded by F8 or a zone change.
				GpuTransport.discard(newestReady);
			}
		}
	}

	private static void publish(int set) {
		int w = pboW;
		int h = pboH;
		long frameId = FRAME_ID[set];
		int slot = (int) (frameId % SLOTS);
		int base = (int) (0x1000 + slot * SLOT_SIZE);
		// Odd while the slot is being written: the host game skips it (and a copy it started
		// before sees the sequence change).
		int s = (int) INT.getOpaque(frames, base);
		INT.setOpaque(frames, base, s | 1);
		VarHandle.releaseFence();
		long layer = (long) w * h * 4;
		boolean hand = HAND[set];
		for (int i = 0; !GPU_FRAME[set] && i < (hand ? 4 : 3); i++) {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, PBO[set][i]);
			ByteBuffer src = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, layer, GL30.GL_MAP_READ_BIT);
			if (src != null) {
				ByteBuffer dst = frames.duplicate().order(ByteOrder.LITTLE_ENDIAN);
				dst.position((int) (base + HDR + layer * i));
				dst.put(src);
				GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
			}
		}
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		frames.putInt(base + 0x04, w);
		frames.putInt(base + 0x08, h);
		frames.putInt(base + 0x0C, (hand ? 7 : 3) | (GPU_FRAME[set] ? 8 : 0));
		frames.putInt(base + 0x30,set);
		frames.putInt(base + 0x34,GPU_GENERATION[set]);
		frames.putLong(base + 0x10, frameId);
		frames.putLong(base + 0x18, frameId);
		frames.putFloat(base + 0x20, CLIP[set][0]);
		frames.putFloat(base + 0x24, CLIP[set][1]);
		frames.putFloat(base + 0x28, POSE[set].fovYDeg);
		frames.putFloat(base + 0x2C, (float) w / h);
		int even = ((int) INT.getOpaque(frames, base) | 1) + 1;
		INT.setRelease(frames, base, even);
		frames.putInt(0x08, slot);
		LONG.setRelease(frames, 0x10, frameId);
		if(GPU_FRAME[set]) GpuTransport.published(set,frameId);
		published++;
		// Only now may the host game render this pose: the pixels for it are in place.
		ErLink.get().writeControl(POSE[set]);
	}
}
