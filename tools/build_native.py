from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import subprocess
ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'
CXX = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
CC = CXX.with_name('clang.exe')
OUT = ROOT / 'dist'
OBJ = ROOT / 'build/objects'
OUT.mkdir(exist_ok=True)
OBJ.mkdir(parents=True, exist_ok=True)
flags = ['--target=x86_64-w64-mingw32', '-O2', '-std=c++17', '-DWIN32_LEAN_AND_MEAN', '-DNOMINMAX', '-I'+str(ER/'include'), '-I'+str(ER/'third_party/minhook/include')]
cpp = ['proxy','log','shm','core','crash','memutil','debugcmd','frame','game','compositor']
def compile_one(name):
    subprocess.run([str(CXX), *flags, '-c', str(ER/'src'/f'{name}.cpp'), '-o',str(OBJ/f'{name}.o')],check=True)
with ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(compile_one, cpp))
mh = []
for name in ['buffer.c','hook.c','trampoline.c','hde/hde64.c']:
    obj = OBJ / (Path(name).stem + '.o')
    subprocess.run([str(CC),'-O2','-I'+str(ER/'third_party/minhook/include'),'-c',str(ER/'third_party/minhook/src'/name),'-o',str(obj)],check=True)
    mh.append(str(obj))
link = ['--target=x86_64-w64-mingw32','-shared','-static','-s']
subprocess.run([str(CXX),*link, *[str(OBJ/f'{x}.o') for x in ['proxy','log','shm']],str(ER/'src/dinput8.def'),'-luser32','-lkernel32','-o',str(OUT/'dinput8.dll')],check=True)
subprocess.run([str(CXX),*link, *[str(OBJ/f'{x}.o') for x in ['core','crash','log','shm','memutil','debugcmd','frame','game','compositor']],*mh,'-luser32','-lkernel32','-ld3d12','-ldxgi','-ldxguid','-o',str(OUT/'erbridge_core.dll')],check=True)
print('Built native Windows DLLs in',OUT)
