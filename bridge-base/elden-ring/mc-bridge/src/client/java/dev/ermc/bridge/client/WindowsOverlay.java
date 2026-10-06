package dev.ermc.bridge.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HCURSOR;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HRGN;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinUser.MONITORINFO;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses one Windows transparency mechanism at a time, keeping the input surface clickable. */
public final class WindowsOverlay {
	private WindowsOverlay() {}
	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static boolean perPixel;
	private static boolean inputOnly;
	private static boolean inputPassThrough;
	private static boolean initialized;
	private static final float INPUT_OPACITY = 1.0F / 255.0F;
	private static final int GWLP_HWNDPARENT = -8;
	private static final DWORD GW_OWNER = new DWORD(4);
	private static HWND hostWindow;
	private static Pointer savedOwner;
	private static boolean ownerSaved;
	private static boolean ownerError;
	private static long nextHostSearchMs;
	private static boolean taskbarFullscreen;
	private static boolean taskbarError;
	private static long nextTaskbarAttemptMs;
	private static int cursorShowAdjustments;
	private static final int WS_MINIMIZE = 0x20000000;
	private static boolean taskbarButtonPending;
	private static boolean taskbarButtonError;
	private static long nextTaskbarButtonAttemptMs;

	public static boolean inputOnly() {
		return inputOnly;
	}

	/** Window existence is independent of frame heartbeats during loading or Alt+Tab. */
	public static boolean hostAvailable(int hostProcessId) {
		return findHost(hostProcessId) != null;
	}

	/** Startup/F8 preparation: restore the owner without taking focus from the loading window. */
	public static boolean prepareHostForOverlay(int hostProcessId) {
		HWND host = findHost(hostProcessId);
		if (host == null) {
			return false;
		}
		User32 user = User32.INSTANCE;
		if ((user.GetWindowLong(host, -16) & WS_MINIMIZE) != 0) { // GWL_STYLE
			user.ShowWindow(host, 4); // SW_SHOWNOACTIVATE: restore without activating
		}
		return true;
	}

	/** Called once when the overlay is ready, after its owner and position have been set. */
	public static boolean showAboveHost(long window, int hostProcessId) {
		if (!prepareHostForOverlay(hostProcessId)) {
			return false;
		}
		if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
			GLFW.glfwRestoreWindow(window);
		}
		GLFW.glfwShowWindow(window);
		HWND hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
		taskbarButtonPending = true;
		updateTaskbarButton(hwnd, true);
		// Showing a previously hidden window clears Explorer's fullscreen marking.
		taskbarFullscreen = false;
		updateTaskbar(window, true);
		GLFW.glfwFocusWindow(window);
		return true;
	}

	/** Use the Windows cursor theme, independently of the nearly transparent input framebuffer. */
	public static void updateCursor(Minecraft mc) {
		long window = mc.getWindow().getWindow();
		HWND hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
		boolean visible = Overlay.active() && !Overlay.hostMode() && !Overlay.hostBusy()
				&& mc.screen != null && !mc.mouseHandler.isMouseGrabbed()
				&& hwnd.equals(User32.INSTANCE.GetForegroundWindow())
				&& GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;
		CursorApi cursor = CursorApi.INSTANCE;
		if (!visible) {
			// Undo only our own ShowCursor calls on gameplay, F8, focus loss or overlay exit.
			while (cursorShowAdjustments > 0) {
				cursor.ShowCursor(false);
				cursorShowAdjustments--;
			}
			return;
		}
		if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL) {
			GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
		}
		// SetCursor(NULL) from the host can leave the pointer empty even when GLFW is
		// already in NORMAL mode. Reassert the real system arrow rather than drawing one.
		cursor.SetCursor(cursor.LoadCursorW(null, Pointer.createConstant(32512))); // IDC_ARROW
		int displayCount = cursor.ShowCursor(true);
		if (displayCount > 0) {
			cursor.ShowCursor(false); // It was already visible; keep the counter unchanged.
		} else {
			cursorShowAdjustments++;
			while (displayCount < 0) {
				displayCount = cursor.ShowCursor(true);
				cursorShowAdjustments++;
			}
		}
	}

	public static void update(long window, boolean active, boolean hiddenContent, int hostProcessId) {
		updateOwner(window, active, hostProcessId);
		updateTaskbarButton(new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window))), active);
		updateTaskbar(window, active);
		boolean wantInputOnly = active && hiddenContent;
		boolean wantPerPixel = active && !hiddenContent;
		boolean wantPassThrough = wantInputOnly && Overlay.hostBusy();
		if (initialized && wantInputOnly == inputOnly && wantPerPixel == perPixel && wantPassThrough == inputPassThrough) {
			return;
		}
		// Remove constant opacity before enabling framebuffer alpha; disable framebuffer
		// alpha before applying constant opacity. Combining them is undefined in GLFW.
		GLFW.glfwSetWindowOpacity(window, 1.0F);
		if (perPixel != wantPerPixel) {
			setFramebufferTransparency(window, wantPerPixel);
			perPixel = wantPerPixel;
		}
		if (wantInputOnly) {
			// An opaque framebuffer with a nonzero constant alpha keeps all pixels clickable
			// and avoids the black OpenGL surface when the window covers the whole monitor.
			// Loading/title screens belong to Elden Ring. Zero opacity lets their
			// clicks through while preserving the visible window and taskbar entry.
			GLFW.glfwSetWindowOpacity(window, wantPassThrough ? 0.0F : INPUT_OPACITY);
		}
		inputOnly = wantInputOnly;
		inputPassThrough = wantPassThrough;
		initialized = true;
		LOG.info("Windows overlay: {}", inputPassThrough ? "loading window, mouse passes to Elden Ring"
				: inputOnly ? "input window, constant alpha 1/255"
				: perPixel ? "framebuffer transparency" : "normal window");
	}

	/** An owned top-level window stays above its owner, without global TOPMOST or focus stealing. */
	private static void updateOwner(long window, boolean active, int hostProcessId) {
		User32 user = User32.INSTANCE;
		HWND hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
		if (!active && !ownerSaved) {
			return;
		}
		Pointer currentOwner = Pointer.createConstant(user.GetWindowLongPtr(hwnd, GWLP_HWNDPARENT).longValue());
		if (active && !ownerSaved) {
			savedOwner = currentOwner;
			ownerSaved = true;
		}
		Pointer wantedOwner = savedOwner;
		if (active && hostProcessId != 0) {
			HWND host = findHost(hostProcessId);
			if (host != null) {
				wantedOwner = host.getPointer();
			}
		}
		if (!java.util.Objects.equals(currentOwner, wantedOwner)) {
			if (active && hostWindow != null) {
				// Attaching to a minimized owner can hide Minecraft and remove its taskbar button.
				prepareHostForOverlay(hostProcessId);
			}
			Native.setLastError(0);
			Pointer previous = user.SetWindowLongPtr(hwnd, GWLP_HWNDPARENT, wantedOwner);
			int error = Native.getLastError();
			if (previous == null && error != 0) {
				if (!ownerError) {
					LOG.warn("Could not change Minecraft window owner: Windows error {}", error);
				}
				ownerError = true;
				return;
			}
			ownerError = false;
			// Apply cached window attributes without activating, moving or globally raising us.
			// SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
			user.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0037);
			if (active && hostWindow != null) {
				if (!user.IsWindowVisible(hwnd) || (user.GetWindowLong(hwnd, -16) & WS_MINIMIZE) != 0) {
					user.ShowWindow(hwnd, 4); // SW_SHOWNOACTIVATE
					taskbarFullscreen = false;
				}
				taskbarButtonPending = true;
			}
		}
		if (!active) {
			taskbarButtonPending = false;
			ownerSaved = false;
			hostWindow = null;
			nextHostSearchMs = 0;
		}
	}

	private static HWND findHost(int hostProcessId) {
		User32 user = User32.INSTANCE;
		if (hostWindow != null && (!user.IsWindow(hostWindow) || processId(hostWindow) != hostProcessId)) {
			hostWindow = null;
			nextHostSearchMs = 0;
		}
		long now = System.currentTimeMillis();
		if (hostProcessId != 0 && hostWindow == null && now >= nextHostSearchMs) {
			nextHostSearchMs = now + 1000;
			user.EnumWindows((candidate, data) -> {
				if (processId(candidate) == hostProcessId && user.IsWindowVisible(candidate)
						&& user.GetWindow(candidate, GW_OWNER) == null) {
					hostWindow = candidate;
					return false;
				}
				return true;
			}, null);
		}
		return hostWindow;
	}

	private static int processId(HWND hwnd) {
		IntByReference pid = new IntByReference();
		User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
		return pid.getValue();
	}

	/** Owned overlays still need their own application button in Explorer. */
	private static void updateTaskbarButton(HWND hwnd, boolean active) {
		if (!active || !taskbarButtonPending || System.currentTimeMillis() < nextTaskbarButtonAttemptMs) {
			return;
		}
		try {
			TaskbarList.addTab(hwnd);
			taskbarButtonPending = false;
			taskbarButtonError = false;
		} catch (RuntimeException e) {
			nextTaskbarButtonAttemptMs = System.currentTimeMillis() + 1000;
			if (!taskbarButtonError) {
				LOG.warn("Could not keep Minecraft's taskbar button: {}", e.toString());
			}
			taskbarButtonError = true;
		}
	}

	/** Tell Explorer this borderless overlay is fullscreen; Explorer restores the taskbar on Alt+Tab. */
	private static void updateTaskbar(long window, boolean active) {
		HWND hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
		boolean fullscreen = active && hostWindow != null && coversMonitor(hwnd);
		if (fullscreen == taskbarFullscreen || System.currentTimeMillis() < nextTaskbarAttemptMs) {
			return;
		}
		try {
			TaskbarList.markFullscreen(hwnd, fullscreen);
			taskbarFullscreen = fullscreen;
			taskbarError = false;
		} catch (RuntimeException e) {
			nextTaskbarAttemptMs = System.currentTimeMillis() + 1000;
			if (!taskbarError) {
				LOG.warn("Could not mark Minecraft fullscreen for the Windows taskbar: {}", e.toString());
			}
			taskbarError = true;
		}
	}

	private static boolean coversMonitor(HWND hwnd) {
		User32 user = User32.INSTANCE;
		RECT rect = new RECT();
		MONITORINFO monitor = new MONITORINFO();
		if (!user.GetWindowRect(hwnd, rect)
				|| !user.GetMonitorInfo(user.MonitorFromWindow(hwnd, 2), monitor).booleanValue()) {
			return false;
		}
		RECT screen = monitor.rcMonitor;
		// Overlay.follow leaves one pixel at the bottom to keep OpenGL desktop-composited.
		return rect.left <= screen.left && rect.top <= screen.top && rect.right >= screen.right
				&& rect.bottom >= screen.bottom - 1;
	}

	/** ITaskbarList2: only a short-lived COM reference on the calling render thread. */
	private static final class TaskbarList extends Unknown {
		private static final GUID CLSID = new GUID("{56FDF344-FD6D-11D0-958A-006097C9A090}");
		private static final GUID IID = new GUID("{602D4995-B13A-429B-A66E-1935E44F4317}");

		private TaskbarList(Pointer pointer) {
			super(pointer);
		}

		private static void markFullscreen(HWND hwnd, boolean fullscreen) {
			invoke(8, hwnd, fullscreen ? 1 : 0); // MarkFullscreenWindow
		}

		private static void addTab(HWND hwnd) {
			invoke(4, hwnd, null); // AddTab
		}

		private static void invoke(int method, HWND hwnd, Integer value) {
			Ole32 ole = Ole32.INSTANCE;
			HRESULT initialized = ole.CoInitializeEx(null, 0); // COINIT_MULTITHREADED
			// GLFW or another mod may have initialized this thread as an STA already.
			if (initialized.intValue() != 0x80010106) { // RPC_E_CHANGED_MODE
				COMUtils.checkRC(initialized);
			}
			TaskbarList taskbar = null;
			try {
				PointerByReference pointer = new PointerByReference();
				COMUtils.checkRC(ole.CoCreateInstance(CLSID, null, 1, IID, pointer)); // CLSCTX_INPROC_SERVER
				taskbar = new TaskbarList(pointer.getValue());
				HRESULT ready = new HRESULT(taskbar._invokeNativeInt(3, new Object[]{taskbar.getPointer()})); // HrInit
				// Some Windows builds return E_NOTIMPL for this legacy initializer while
				// MarkFullscreenWindow is fully supported. Check the actual marking call below.
				if (ready.intValue() != 0x80004001) {
					COMUtils.checkRC(ready);
				}
				Object[] args = value == null ? new Object[]{taskbar.getPointer(), hwnd}
						: new Object[]{taskbar.getPointer(), hwnd, value};
				COMUtils.checkRC(new HRESULT(taskbar._invokeNativeInt(method, args)));
			} finally {
				if (taskbar != null) {
					taskbar.Release();
				}
				if (initialized.intValue() >= 0) {
					ole.CoUninitialize();
				}
			}
		}
	}

	private static void setFramebufferTransparency(long window, boolean enabled) {
		BlurBehind blur = new BlurBehind();
		blur.dwFlags = 1; // DWM_BB_ENABLE
		blur.fEnable = enabled ? 1 : 0;
		if (enabled) {
			blur.dwFlags |= 2; // DWM_BB_BLURREGION, matching GLFW's transparent framebuffer
			blur.hRgnBlur = GDI32.INSTANCE.CreateRectRgn(0, 0, -1, -1);
		}
		try {
			HWND hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
			int result = DwmApi.INSTANCE.DwmEnableBlurBehindWindow(hwnd, blur);
			if (result < 0) {
				throw new IllegalStateException("DwmEnableBlurBehindWindow failed: 0x" + Integer.toHexString(result));
			}
		} finally {
			if (blur.hRgnBlur != null) {
				GDI32.INSTANCE.DeleteObject(blur.hRgnBlur);
			}
		}
	}

	public interface DwmApi extends StdCallLibrary {
		DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);
		int DwmEnableBlurBehindWindow(HWND hwnd, BlurBehind blur);
	}

	public interface CursorApi extends StdCallLibrary {
		CursorApi INSTANCE = Native.load("user32", CursorApi.class);
		HCURSOR LoadCursorW(HINSTANCE instance, Pointer name);
		HCURSOR SetCursor(HCURSOR cursor);
		int ShowCursor(boolean show);
	}

	@Structure.FieldOrder({"dwFlags", "fEnable", "hRgnBlur", "fTransitionOnMaximized"})
	public static class BlurBehind extends Structure {
		public int dwFlags;
		public int fEnable;
		public HRGN hRgnBlur;
		public int fTransitionOnMaximized;
	}
}
