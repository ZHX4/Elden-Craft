package dev.ermc.bridge.client;

import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.Protocol;
import dev.ermc.bridge.TerrainManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overlay mode: Minecraft's window becomes a borderless, transparent layer above the host
 * glued to the host game's window. Only Minecraft's own content (blocks, entities, hand, HUD)
 * is opaque; everywhere else the host game shows through.
 *
 * <p>Windows creates an opaque framebuffer and uses constant window opacity when the host
 * composites Minecraft; the fallback enables framebuffer transparency separately. macOS
 * creates a transparent framebuffer. While overlay mode is off, the window renders normally.
 */
public final class Overlay {
	private Overlay() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");

	private static boolean transparentWindow;
	private static boolean active;
	/** The player handed control to the host game (F8): our window is hidden and Minecraft is paused. */
	private static boolean hostMode;
	private static int lastSwitchReq = Integer.MIN_VALUE;
	private static long window;
	private static int savedX, savedY, savedW, savedH;
	private static int appliedX = Integer.MIN_VALUE, appliedY, appliedW, appliedH;
	private static long lastInWorldMs;
	private static boolean busy;
	/** Dev ("spin" command): turn the player this fast, every frame, to check that both games show the same pose. */
	public static float spinDegPerSec;
	private static long lastSpinNs;
	private static long lastWantMs;
	private static boolean hostPreparationPending = true;
	private static boolean focusPending;
	private static final GameState STATE = new GameState();

	public static boolean active() {
		return active;
	}

	public static boolean transparentWindow() {
		return transparentWindow;
	}

	/** Windows overlays need a windowed swap chain; they follow the host's size themselves. */
	public static boolean windowedModeRequired() {
		return Platform.get() == Platform.WINDOWS && !Boolean.getBoolean("erbridge.disableOverlay");
	}

	/** A zero-alpha input window lets Windows deliver clicks to Elden Ring underneath it. */
	public static float windowBackgroundAlpha() {
		if (!active) {
			return 1.0F;
		}
		if (Platform.get() == Platform.WINDOWS) {
			return WindowsOverlay.inputOnly() ? 1.0F : 1.0F / 255.0F;
		}
		return 0.0F;
	}

	public static boolean hostMode() {
		return hostMode;
	}

	/**
	 * Elden Ring is showing its own screens (the Tarnished dying, "YOU DIED", a loading screen) or
	 * Steve is still being moved to the Tarnished: the overlay stays but draws nothing.
	 */
	public static boolean hostBusy() {
		return busy;
	}

	/** F8 in Minecraft: hide and pause Minecraft, give the host game its camera and the keyboard/mouse. */
	public static void switchToHost(Minecraft mc) {
		if (hostMode || window == 0L) {
			return;
		}
		hostMode = true;
		CameraSync.suspend();
		if (mc.level != null && mc.screen == null) {
			mc.pauseGame(false);
		}
		mc.mouseHandler.releaseMouse();
		// Hiding an owned window can immediately focus Elden Ring. Publish the
		// handoff first so its next tick cannot treat this F8 as a return press.
		ErLink.get().requestHostFocus();
		GLFW.glfwHideWindow(window);
		LOG.info("Control -> Elden Ring");
	}

	/** F8 in the host game (reported by the DLL): bring Minecraft back on top. */
	public static void switchToMc(Minecraft mc) {
		if (!hostMode) {
			return;
		}
		hostMode = false;
		hostPreparationPending = true;
		if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
			GLFW.glfwRestoreWindow(window);
		}
		GLFW.glfwShowWindow(window);
		GLFW.glfwFocusWindow(window);
		if (mc.screen instanceof PauseScreen) {
			mc.setScreen(null);
		}
		LOG.info("Control -> Minecraft");
	}

	/** Called right before GLFW creates Minecraft's window. */
	public static void applyWindowHints() {
		if (Boolean.getBoolean("erbridge.disableOverlay")) {
			return;
		}
		// Windows uses an opaque input framebuffer with constant window opacity when
		// Elden Ring composites our frames. Per-pixel alpha is enabled only for fallback.
		GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER,
				Platform.get() == Platform.WINDOWS ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
		transparentWindow = true;
		if (Platform.get() == Platform.MACOSX) {
			// Match the host game's pixel density (CrossOver renders it at 1x) and save 4x fill
			// rate. Always, not only when the host game is already up: a Retina framebuffer is
			// twice its size, too big to be drawn inside its frame.
			GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);
		}
	}

	public static void onWindowCreated(long handle) {
		window = handle;
		hostPreparationPending = true;
		if (handle == 0L) {
			transparentWindow = false;
		}
	}

	/** Once per frame on the render thread, before anything is drawn. */
	public static void onFrame(Minecraft mc) {
		if (spinDegPerSec != 0.0F && mc.player != null) {
			long now = System.nanoTime();
			if (lastSpinNs != 0L) {
				float yaw = mc.player.getYRot() + spinDegPerSec * (now - lastSpinNs) / 1.0e9F;
				mc.player.setYRot(yaw);
				mc.player.yRotO = yaw;
			}
			lastSpinNs = now;
		} else {
			lastSpinNs = 0L;
		}
		// Recover even if options.txt or a fullscreen mod requested fullscreen at startup.
		// Whole-window opacity must not be combined with our transparent framebuffer.
		if (windowedModeRequired() && mc.getWindow().isFullscreen()) {
			mc.getWindow().toggleFullScreen();
			mc.options.fullscreen().set(false);
			appliedX = Integer.MIN_VALUE;
			LOG.info("Keeping the Minecraft input window in windowed mode");
		}
		ErLink link = ErLink.get();
		boolean alive = link.poll();
		link.bumpMcHeartbeat();
		int req = link.mcSwitchRequests();
		if (lastSwitchReq == Integer.MIN_VALUE) {
			lastSwitchReq = req;
		} else if (req != lastSwitchReq) {
			lastSwitchReq = req;
			switchToMc(mc);
		}
		boolean haveState = alive && link.snapshot(STATE);
		if (hostPreparationPending && !hostMode && transparentWindow && window != 0L
				&& Platform.get() == Platform.WINDOWS) {
			// A minimized host may stop its heartbeat. Prepare it once at startup/F8 return,
			// independently of state polling; later Alt+Tab does not keep restoring it.
			hostPreparationPending = !WindowsOverlay.prepareHostForOverlay(link.hostProcessId());
		}
		boolean haveClientWorld = mc.level != null && mc.player != null && TerrainManager.isBridgeWorld();
		boolean haveHostWindow = Platform.get() == Platform.WINDOWS && WindowsOverlay.hostAvailable(link.hostProcessId());
		// Remember the last valid hunter state for short area-load gaps.
		long now = System.currentTimeMillis();
		if (haveState && STATE.has(Protocol.STATE_PLAYER_VALID)) {
			lastInWorldMs = now;
		}
		boolean hostBusyNow = !haveState || !STATE.has(Protocol.STATE_PLAYER_VALID) || STATE.has(Protocol.STATE_HOST_BUSY);
		// Loading can stop the heartbeat for several seconds. Keep the bridge world
		// transparent while its host window exists, including before the first save
		// finishes loading; never expose the ordinary void world during that gap.
		boolean inWorld = (haveState && (now - lastInWorldMs < 3000 || STATE.has(Protocol.STATE_HOST_BUSY)))
				|| (haveClientWorld && haveHostWindow);
		boolean want = transparentWindow && window != 0L && inWorld && !hostMode
				&& GLFW.glfwGetWindowMonitor(window) == 0L;
		if (want) {
			lastWantMs = now;
		}
		// Turn on immediately, but only turn off after a sustained reason (or a user toggle).
		if (want && !active) {
			setActive(mc, true);
		} else if (!want && active && (hostMode || now - lastWantMs > 1500)) {
			setActive(mc, false);
		}
		if (active && STATE.has(Protocol.STATE_WINDOW_VALID)) {
			follow(STATE.winX, STATE.winY, STATE.winW, STATE.winH);
		}
		boolean nowBusy = active && (hostBusyNow || !haveClientWorld || !TerrainManager.recallSettled(400));
		if (nowBusy && mc.screen instanceof PauseScreen) {
			// Nothing is drawn while Elden Ring shows its own screen, so a pause menu would be
			// invisible yet take the clicks meant for Elden Ring.
			mc.setScreen(null);
		}
		if (nowBusy != busy) {
			busy = nowBusy;
			LOG.info(busy ? "Elden Ring shows its own screen (death, loading or recall): drawing nothing"
				: "Drawing again");
		}
		if (!active || busy) {
			CameraSync.suspend();
		}
		if (Platform.get() == Platform.WINDOWS && window != 0L) {
			WindowsOverlay.updateCursor(mc);
			WindowsOverlay.update(window, active && !hostMode, busy || FramePassthrough.activeInHost(),
					link.hostProcessId());
		}
		if (active && focusPending && haveClientWorld && readyToFocus(mc, hostBusyNow) && !busy) {
			// Show and focus only after ownership, geometry and opacity have been applied.
			if (Platform.get() == Platform.WINDOWS) {
				focusPending = !WindowsOverlay.showAboveHost(window, link.hostProcessId());
			} else {
				GLFW.glfwShowWindow(window);
				GLFW.glfwFocusWindow(window);
				focusPending = false;
			}
		}
	}

	private static boolean readyToFocus(Minecraft mc, boolean hostBusyNow) {
		return !hostBusyNow && mc.getOverlay() == null && !(mc.screen instanceof ReceivingLevelScreen)
				&& mc.level.hasChunkAt(mc.player.blockPosition()) && TerrainManager.recallSettled(400)
				// A loaded void chunk alone does not mean Elden Ring is showing our frame.
				&& (Platform.get() != Platform.WINDOWS || !FramePassthrough.enabled()
						|| CameraSync.mode() != CameraSync.Mode.DRIVE_HOST || FramePassthrough.activeInHost());
	}

	private static void setActive(Minecraft mc, boolean on) {
		active = on;
		focusPending = on;
		LOG.info("Overlay mode {}", on ? "ON" : "OFF");
		if (on) {
			TerrainManager.requestRecall();
			int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
			GLFW.glfwGetWindowPos(window, x, y);
			GLFW.glfwGetWindowSize(window, w, h);
			savedX = x[0];
			savedY = y[0];
			savedW = w[0];
			savedH = h[0];
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			// Windows keeps us above only Elden Ring through window ownership, so Alt+Tab
			// can bring other applications above both games without waiting for another frame.
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING,
					Platform.get() == Platform.WINDOWS ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
			setShadow(false);
			// When the host game draws our frames, this window is fully transparent; macOS would
			// then let clicks fall through to it unless told otherwise.
			objcBool("setIgnoresMouseEvents:", false);
			appliedX = Integer.MIN_VALUE;
		} else if (!hostMode) {
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			setShadow(true);
			if (savedW > 0 && savedH > 0) {
				GLFW.glfwSetWindowSize(window, savedW, savedH);
				GLFW.glfwSetWindowPos(window, savedX, savedY);
			}
		}
	}

	/**
	 * The host game reports its client area in Windows screen coordinates. CrossOver (Retina
	 * mode off) maps one Windows pixel to one macOS point with the same top-left origin as GLFW.
	 */
	private static void follow(int x, int y, int w, int h) {
		if (w < 64 || h < 64) {
			return;
		}
		// Windows OpenGL drivers can bypass desktop composition for a borderless
		// surface that exactly covers a monitor, displaying our empty input buffer black.
		// Leave one screen pixel at the bottom so the window remains desktop-composited.
		if (Platform.get() == Platform.WINDOWS) {
			h--;
		}
		if (x == appliedX && y == appliedY && w == appliedW && h == appliedH) {
			return;
		}
		GLFW.glfwSetWindowSize(window, w, h);
		GLFW.glfwSetWindowPos(window, x, y);
		appliedX = x;
		appliedY = y;
		appliedW = w;
		appliedH = h;
		LOG.info("Overlay glued to host-game client area {}x{} at {},{}", w, h, x, y);
	}

	/** Borderless transparent windows would otherwise cast a shadow around every block. */
	private static void setShadow(boolean shadow) {
		objcBool("setHasShadow:", shadow);
	}

	/** Calls an NSWindow setter taking a BOOL. */
	private static void objcBool(String selector, boolean value) {
		if (Platform.get() != Platform.MACOSX) {
			return;
		}
		try {
			long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(window);
			long msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			if (nsWindow != 0L && msgSend != 0L) {
				JNI.invokePPV(nsWindow, ObjCRuntime.sel_getUid(selector), value, msgSend);
			}
		} catch (Throwable t) {
			LOG.warn("NSWindow {} failed: {}", selector, t.toString());
		}
	}
}
