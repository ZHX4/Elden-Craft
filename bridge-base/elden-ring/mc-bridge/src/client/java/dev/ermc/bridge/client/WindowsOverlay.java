package dev.ermc.bridge.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.WinDef.HRGN;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.win32.StdCallLibrary;
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
	private static boolean initialized;
	private static final float INPUT_OPACITY = 1.0F / 255.0F;

	public static boolean inputOnly() {
		return inputOnly;
	}

	public static void update(long window, boolean active, boolean hiddenContent) {
		boolean wantInputOnly = active && hiddenContent;
		boolean wantPerPixel = active && !hiddenContent;
		if (initialized && wantInputOnly == inputOnly && wantPerPixel == perPixel) {
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
			GLFW.glfwSetWindowOpacity(window, INPUT_OPACITY);
		}
		inputOnly = wantInputOnly;
		initialized = true;
		LOG.info("Windows overlay: {}", inputOnly ? "input window, constant alpha 1/255"
				: perPixel ? "framebuffer transparency" : "normal window");
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

	@Structure.FieldOrder({"dwFlags", "fEnable", "hRgnBlur", "fTransitionOnMaximized"})
	public static class BlurBehind extends Structure {
		public int dwFlags;
		public int fEnable;
		public HRGN hRgnBlur;
		public int fTransitionOnMaximized;
	}
}
