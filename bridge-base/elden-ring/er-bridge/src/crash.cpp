#include "common.h"
#include <dbghelp.h>
#include <stdio.h>
#include <string.h>

namespace mb {

static PVOID g_handler = nullptr;
static HMODULE g_dbghelp = nullptr;
static volatile LONG g_writing = 0;
static thread_local const char* g_phase = "game outside bridge";
static char g_dumpPath[MAX_PATH], g_textPath[MAX_PATH];
typedef BOOL(WINAPI* WriteDump_t)(HANDLE, DWORD, HANDLE, MINIDUMP_TYPE,
    PMINIDUMP_EXCEPTION_INFORMATION, PMINIDUMP_USER_STREAM_INFORMATION, PMINIDUMP_CALLBACK_INFORMATION);
static WriteDump_t g_writeDump = nullptr;

const char* crash_phase(const char* phase) {
    const char* previous = g_phase;
    g_phase = phase;
    return previous;
}

static LONG CALLBACK observe_fault(EXCEPTION_POINTERS* exception) {
    DWORD code = exception->ExceptionRecord->ExceptionCode;
    if (code != EXCEPTION_ACCESS_VIOLATION && code != EXCEPTION_ILLEGAL_INSTRUCTION
        && code != EXCEPTION_ARRAY_BOUNDS_EXCEEDED && code != 0xC0000374u) return EXCEPTION_CONTINUE_SEARCH;
    uintptr_t site = (uintptr_t)exception->ExceptionRecord->ExceptionAddress;
    // Some protected game code deliberately raises and handles faults. Watch
    // bridge calls and the allocator site recorded in the actual crash only.
    if (g_phase[0] == 'g' && strcmp(g_phase, "game outside bridge") == 0
        && site != main_module_base() + 0xE1E97B && code != 0xC0000374u) return EXCEPTION_CONTINUE_SEARCH;
    if (InterlockedCompareExchange(&g_writing, 1, 0) != 0) return EXCEPTION_CONTINUE_SEARCH;
    // First-chance observer: record the evidence, then let the game's normal
    // handlers decide whether this fault is fatal. Never turn an invalid ray
    // into an empty doorway or continue after allocator corruption.
    FILE* file = fopen(g_textPath, "w");
    if (file) {
        SYSTEMTIME when; GetLocalTime(&when);
        fprintf(file, "%04u-%02u-%02u %02u:%02u:%02u.%03u\ncode=%08lx thread=%lu phase=%s\n",
            when.wYear, when.wMonth, when.wDay, when.wHour, when.wMinute, when.wSecond, when.wMilliseconds,
            code, GetCurrentThreadId(), g_phase);
        CONTEXT& c = *exception->ContextRecord;
        fprintf(file, "game_base=%llx rip=%llx rsp=%llx rbp=%llx\nrax=%llx rcx=%llx rdx=%llx r8=%llx r9=%llx\n",
            (unsigned long long)main_module_base(), c.Rip, c.Rsp, c.Rbp, c.Rax, c.Rcx, c.Rdx, c.R8, c.R9);
        if (code == EXCEPTION_ACCESS_VIOLATION && exception->ExceptionRecord->NumberParameters >= 2)
            fprintf(file, "access=%llu address=%llx\n",
                (unsigned long long)exception->ExceptionRecord->ExceptionInformation[0],
                (unsigned long long)exception->ExceptionRecord->ExceptionInformation[1]);
        // Read through the OS: the failing thread's stack or a heap pointer
        // might itself be invalid. Raw stack candidates complement the dump.
        for (unsigned i = 0; i < 128; i++) {
            uint64_t value; SIZE_T got;
            if (!ReadProcessMemory(GetCurrentProcess(), (void*)(c.Rsp + i*8), &value, 8, &got) || got != 8) break;
            fprintf(file, "stack+%04x=%016llx\n", i*8, (unsigned long long)value);
        }
        fclose(file);
    }
    if (g_writeDump) {
        HANDLE output = CreateFileA(g_dumpPath, GENERIC_WRITE, FILE_SHARE_READ, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (output != INVALID_HANDLE_VALUE) {
            MINIDUMP_EXCEPTION_INFORMATION info = {GetCurrentThreadId(), exception, FALSE};
            g_writeDump(GetCurrentProcess(), GetCurrentProcessId(), output,
                (MINIDUMP_TYPE)(MiniDumpNormal | MiniDumpWithUnloadedModules | MiniDumpWithThreadInfo), &info, nullptr, nullptr);
            CloseHandle(output);
        }
    }
    InterlockedExchange(&g_writing, 0);
    return EXCEPTION_CONTINUE_SEARCH;
}

void crash_init() {
    if (g_handler) return;
    snprintf(g_dumpPath, sizeof(g_dumpPath), "%s", bridge_path("eldenring-crash.dmp"));
    snprintf(g_textPath, sizeof(g_textPath), "%s", bridge_path("eldenring-crash.txt"));
    g_dbghelp = LoadLibraryA("dbghelp.dll");
    if (g_dbghelp) g_writeDump = (WriteDump_t)GetProcAddress(g_dbghelp, "MiniDumpWriteDump");
    g_handler = AddVectoredExceptionHandler(1, observe_fault);
    log("crash: fault observer %s, minidump %s", g_handler ? "ready" : "unavailable", g_writeDump ? "ready" : "unavailable");
}

void crash_shutdown() {
    if (g_handler) RemoveVectoredExceptionHandler(g_handler);
    g_handler = nullptr;
    if (g_dbghelp) FreeLibrary(g_dbghelp);
    g_dbghelp = nullptr; g_writeDump = nullptr;
}

} // namespace mb
