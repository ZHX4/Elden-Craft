"""Check that fault evidence is written and exception handling stays with Windows/the game."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/crash-recorder-test'
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'test.cpp'
source.write_text(r'''
#include "common.h"
#include <cassert>
#include <stdio.h>
#include <string.h>
namespace mb {
const char* bridge_path(const char* name) { static char path[MAX_PATH]; snprintf(path,sizeof(path),"build/crash-recorder-test/%s",name);return path; }
void log(const char*,...) {}
uintptr_t main_module_base(){return (uintptr_t)GetModuleHandleA(nullptr);}
}
static LONG CALLBACK handled(EXCEPTION_POINTERS* p) {
    return p->ExceptionRecord->ExceptionCode==EXCEPTION_ACCESS_VIOLATION ? EXCEPTION_CONTINUE_EXECUTION:EXCEPTION_CONTINUE_SEARCH;
}
int main() {
    auto gameHandler=AddVectoredExceptionHandler(0,handled);
    mb::crash_init();
    {
        mb::CrashPhase phase("recorder regression first");
        ULONG_PTR parameters[2]={0,1};
        RaiseException(EXCEPTION_ACCESS_VIOLATION,0,2,parameters);
    }
    assert(GetFileAttributesA("build/crash-recorder-test/eldenring-crash.dmp")!=INVALID_FILE_ATTRIBUTES);
    // A handled first-chance exception must not disable recording of a later
    // fatal fault. The second game's handler still executes normally too.
    {
        mb::CrashPhase phase("recorder regression second");
        ULONG_PTR parameters[2]={1,2};
        RaiseException(EXCEPTION_ACCESS_VIOLATION,0,2,parameters);
    }
    mb::crash_shutdown();RemoveVectoredExceptionHandler(gameHandler);
    FILE* file=fopen("build/crash-recorder-test/eldenring-crash.txt","r");assert(file);
    char evidence[16384]={};fread(evidence,1,sizeof(evidence)-1,file);fclose(file);
    assert(strstr(evidence,"recorder regression second") && strstr(evidence,"access=1 address=2"));
    puts("PASS: stack/context and minidump, repeated faults, normal exception handling and clean shutdown");
}
''', encoding='utf-8')
compiler = next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe'))
native = ROOT / 'bridge-base/elden-ring/er-bridge'
exe = OUT / 'test.exe'
subprocess.run([str(compiler), '-std=c++17', '-static', '-I'+str(native/'src'), '-I'+str(native/'include'),
                str(source), str(native/'src/crash.cpp'), '-o', str(exe)], check=True)
subprocess.run([str(exe)], cwd=ROOT, check=True)
assert (OUT/'eldenring-crash.dmp').stat().st_size > 1024
