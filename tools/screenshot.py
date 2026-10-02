from pathlib import Path
import sys, ctypes, struct
from ctypes import wintypes
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.tools/python-site'))
from PIL import ImageGrab
pid=struct.unpack_from('<I',(ROOT/'runtime/bridge.shm').read_bytes(),0x20)[0]
user=ctypes.windll.user32
user.SetProcessDPIAware()
found=[]
CB=ctypes.WINFUNCTYPE(wintypes.BOOL,wintypes.HWND,wintypes.LPARAM)
@CB
def enum(hwnd,_):
    owner=wintypes.DWORD()
    user.GetWindowThreadProcessId(hwnd,ctypes.byref(owner))
    if owner.value==pid and user.IsWindowVisible(hwnd):
        rect=wintypes.RECT()
        user.GetWindowRect(hwnd,ctypes.byref(rect))
        if rect.right-rect.left>320 and rect.bottom-rect.top>200:
            found.append((rect.left,rect.top,rect.right,rect.bottom))
    return True
user.EnumWindows(enum,0)
if not found: raise SystemExit('No visible game window')
output=ROOT/'runtime/game.png'
ImageGrab.grab(bbox=found[0]).save(output)
print(output)
