from pathlib import Path
import subprocess
root = Path(__file__).resolve().parents[1]
source = (root / 'bridge-base/elden-ring/er-bridge/src/compositor.cpp').read_text()
helper = source[source.index('static void copy_upload('):source.index('// Copies a frames.shm slot')]
test = r'''
#include <emmintrin.h>
#include <stdint.h>
#include <string.h>
#include <malloc.h>
#include <stdio.h>
HELPER
int main() {
    for (size_t bytes : {0u,1u,15u,16u,63u,64u,65u,255u,256u,10240u,10244u,10400u,14745600u}) {
        for (size_t shift : {0u,1u,7u,16u}) {
            auto* src = (uint8_t*)_aligned_malloc(bytes + 64, 32);
            auto* dst = (uint8_t*)_aligned_malloc(bytes + 64, 32);
            memset(dst, 0xA5, bytes + 64);
            for (size_t i = 0; i < bytes + 64; ++i) src[i] = (uint8_t)(i * 37 + 19);
            copy_upload(dst + shift, src + 3, bytes); _mm_sfence();
            if (memcmp(dst + shift, src + 3, bytes)) return 1;
            for (size_t i = 0; i < shift; ++i) if (dst[i] != 0xA5) return 2;
            for (size_t i = shift + bytes; i < bytes + 64; ++i) if (dst[i] != 0xA5) return 3;
            _aligned_free(src); _aligned_free(dst);
        }
    }
    puts("Upload copy: 52 alignment/size cases passed, guard bytes preserved.");
}
'''.replace('HELPER', helper).replace('#include <stdio.h>', '#include <stdio.h>\n#include <initializer_list>')
out = root / 'build/upload-copy-test.cpp'
out.write_text(test)
compiler = next((root / '.tools/compiler').glob('*/bin/clang++.exe'))
exe = root / 'build/upload-copy-test.exe'
subprocess.run([str(compiler), '--target=x86_64-w64-mingw32', '-O2', '-std=c++17', '-static', str(out), '-o', str(exe)], check=True)
subprocess.run([str(exe)], check=True)
