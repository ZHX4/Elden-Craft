// Full-resolution OpenGL -> D3D12 exchange, without GPU readback or CPU pixel copies.
static constexpr unsigned kGpuSlots = 6;
static ID3D12Resource* g_gpuTextures[kGpuSlots][4] = {};
static HANDLE g_gpuHandles[kGpuSlots*4+1] = {};
static ID3D12Fence* g_gpuReady = nullptr;
static UINT g_gpuGeneration = 0;
static UINT64 g_gpuAckFence[kGpuSlots] = {}, g_gpuAckFrame[kGpuSlots] = {};
static void gpu_release() {
    if (g_frames) InterlockedExchange((volatile LONG*)(g_frames+0x40),0);
    for (auto& set : g_gpuTextures) for (auto& t : set) safe_release(t);
    safe_release(g_gpuReady);
    for (auto& h : g_gpuHandles) { if(h) CloseHandle(h); h=nullptr; }
    g_gpuGeneration=0;
    memset(g_gpuAckFence,0,sizeof(g_gpuAckFence)); memset(g_gpuAckFrame,0,sizeof(g_gpuAckFrame));
}
static void gpu_init(UINT w, UINT h) {
    if(!g_frames) return;
    g_gpuGeneration=GetTickCount();
    auto heap=heap_props(D3D12_HEAP_TYPE_DEFAULT);
    HRESULT result=S_OK;
    for(unsigned s=0;s<kGpuSlots && SUCCEEDED(result);s++) for(int layer=0;layer<4 && SUCCEEDED(result);layer++) {
        auto desc=tex_desc(w,h,layer==1 ? DXGI_FORMAT_R32_FLOAT : DXGI_FORMAT_B8G8R8A8_UNORM);
        desc.Flags=D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET | D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;
        result=g_dev->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_SHARED,&desc,D3D12_RESOURCE_STATE_COMMON,
            nullptr,__uuidof(ID3D12Resource),(void**)&g_gpuTextures[s][layer]);
        wchar_t name[128]; swprintf(name,128,L"Local\\ERMCGPU_%lu_%u_%d_%d",GetCurrentProcessId(),g_gpuGeneration,s,layer);
        if(SUCCEEDED(result)) result=g_dev->CreateSharedHandle(g_gpuTextures[s][layer],nullptr,GENERIC_ALL,name,&g_gpuHandles[s*4+layer]);
    }
    if(SUCCEEDED(result)) result=g_dev->CreateFence(0,D3D12_FENCE_FLAG_SHARED,__uuidof(ID3D12Fence),(void**)&g_gpuReady);
    wchar_t name[128]; swprintf(name,128,L"Local\\ERMCGPU_%lu_%u_ready",GetCurrentProcessId(),g_gpuGeneration);
    if(SUCCEEDED(result)) result=g_dev->CreateSharedHandle(g_gpuReady,nullptr,GENERIC_ALL,name,&g_gpuHandles[kGpuSlots*4]);
    if(FAILED(result)) {log("gpu transport: creation failed %08lx",(unsigned long)result);gpu_release();return;}
    memset(g_frames+0x40,0,0xA0);
    *(UINT*)(g_frames+0x44)=w; *(UINT*)(g_frames+0x48)=h;
    *(UINT*)(g_frames+0x4c)=g_gpuGeneration; *(UINT*)(g_frames+0x50)=GetCurrentProcessId();
    *(UINT*)(g_frames+0x54)=kGpuSlots;
    MemoryBarrier(); InterlockedExchange((volatile LONG*)(g_frames+0x40),0x47504d43);
    log("gpu transport: shared textures ready %ux%u generation %u",w,h,g_gpuGeneration);
}
static void gpu_acknowledge(uint64_t lastUploaded, bool discardAll = false) {
    if(!g_gpuReady) return;
    UINT64 complete=g_fence->GetCompletedValue();
    for(unsigned s=0;s<kGpuSlots;s++) if(g_gpuAckFence[s] && complete>=g_gpuAckFence[s]) {
        InterlockedExchange64((volatile LONG64*)(g_frames+0x60+s*8),g_gpuAckFrame[s]);
        g_gpuAckFence[s]=0;
    }
    // These per-texture publication IDs outlive the three presentation headers.
    // A header may be replaced while an older texture is still awaiting release.
    for(unsigned s=0;s<kGpuSlots;s++) {
        UINT64 id=(UINT64)InterlockedCompareExchange64((volatile LONG64*)(g_frames+0xA0+s*8),0,0);
        if(!id || (!discardAll && id>=lastUploaded) || g_gpuAckFence[s] || g_gpuReady->GetCompletedValue()<id) continue;
        auto* ack=(volatile LONG64*)(g_frames+0x60+s*8);
        if((UINT64)*ack<id) InterlockedExchange64(ack,id);
    }
    // A faster producer can publish frames the host never selects. Release these
    // obsolete captures too, otherwise one unconsumed slot stops the whole ring.
    for(unsigned i=0;i<ERMC_FRAME_SLOTS;i++) {
        auto* h=slot_header(i);
        if((h->seq&1) || !(h->flags&8) || (!discardAll && h->frameId>=lastUploaded)) continue;
        unsigned s=*(UINT*)((uint8_t*)h+0x30), gen=*(UINT*)((uint8_t*)h+0x34);
        if(s>=kGpuSlots || gen!=g_gpuGeneration || g_gpuAckFence[s] || g_gpuReady->GetCompletedValue()<h->frameId) continue;
        auto* ack=(volatile LONG64*)(g_frames+0x60+s*8);
        if((UINT64)*ack<h->frameId) InterlockedExchange64(ack,h->frameId);
    }
}
static bool gpu_frame_ready(ErmcFrameHeader* slot) {
    UINT index=*(UINT*)((uint8_t*)slot+0x30), generation=*(UINT*)((uint8_t*)slot+0x34);
    return g_gpuReady && index<kGpuSlots && generation==g_gpuGeneration && g_gpuReady->GetCompletedValue()>=slot->frameId;
}
