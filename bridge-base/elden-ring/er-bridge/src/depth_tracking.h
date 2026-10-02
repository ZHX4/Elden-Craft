// Native D3D12 depth state tracking. Recording and submission are separate:
// command lists may be recorded concurrently and executed in a different order.

struct TrackedDepth {
    ID3D12Resource* resource;
    D3D12_RESOURCE_DESC desc;
    D3D12_RESOURCE_STATES state[2] = {};
    bool known[2] = {}, split[2] = {};
    UINT planes = 1;
    uint64_t seen = 0, writes = 0;
};
struct DepthChange { ID3D12Resource* resource; UINT sub; D3D12_RESOURCE_STATES after; D3D12_RESOURCE_BARRIER_FLAGS flags; };
static SRWLOCK g_depthTrackLock = SRWLOCK_INIT;
static std::vector<TrackedDepth> g_trackedDepth;
static std::unordered_map<ID3D12CommandList*, std::vector<DepthChange>> g_depthChanges;
static void *g_barrierAddress, *g_barrierOriginal, *g_resetAddress, *g_resetOriginal;
static thread_local bool g_depthInternal = false;

static TrackedDepth* tracked_depth(ID3D12Resource* resource) {
    for (auto& d : g_trackedDepth) if (d.resource == resource) return &d;
    return nullptr;
}
static void STDMETHODCALLTYPE hkResourceBarrier(ID3D12GraphicsCommandList* list, UINT count, const D3D12_RESOURCE_BARRIER* barriers) {
    InflightGuard guard;
    if (!g_depthInternal) {
        AcquireSRWLockExclusive(&g_depthTrackLock);
        for (UINT i = 0; i < count; ++i) {
            const auto& b = barriers[i];
            if (b.Type != D3D12_RESOURCE_BARRIER_TYPE_TRANSITION || !b.Transition.pResource) continue;
            auto* d = tracked_depth(b.Transition.pResource);
            if (!d && ((b.Transition.StateBefore | b.Transition.StateAfter) &
                       (D3D12_RESOURCE_STATE_DEPTH_WRITE | D3D12_RESOURCE_STATE_DEPTH_READ)) && g_trackedDepth.size() < 32) {
                auto desc = b.Transition.pResource->GetDesc();
                // Scene buffers have the display aspect; square shadow maps are irrelevant.
                if (desc.Dimension == D3D12_RESOURCE_DIMENSION_TEXTURE2D && desc.Width >= 640 &&
                    desc.Width > desc.Height && desc.Width < desc.Height * 3ull && desc.SampleDesc.Count == 1 &&
                    desc.DepthOrArraySize == 1 && desc.MipLevels == 1 && (desc.Flags & D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL)) {
                    TrackedDepth entry{};
                    entry.resource = b.Transition.pResource; entry.resource->AddRef(); entry.desc = desc;
                    entry.planes = (desc.Format == DXGI_FORMAT_R24G8_TYPELESS || desc.Format == DXGI_FORMAT_D24_UNORM_S8_UINT ||
                                    desc.Format == DXGI_FORMAT_R32G8X24_TYPELESS || desc.Format == DXGI_FORMAT_D32_FLOAT_S8X24_UINT) ? 2 : 1;
                    g_trackedDepth.push_back(entry); d = &g_trackedDepth.back();
                    log("depth: tracking %p %llux%u format %u planes %u", entry.resource,
                        (unsigned long long)desc.Width, desc.Height, desc.Format, entry.planes);
                }
            }
            if (d) g_depthChanges[list].push_back({d->resource, b.Transition.Subresource,
                                                  b.Transition.StateAfter, b.Flags});
        }
        ReleaseSRWLockExclusive(&g_depthTrackLock);
    }
    using Fn = void (STDMETHODCALLTYPE*)(ID3D12GraphicsCommandList*, UINT, const D3D12_RESOURCE_BARRIER*);
    ((Fn)g_barrierOriginal)(list, count, barriers);
}
static HRESULT STDMETHODCALLTYPE hkCommandReset(ID3D12GraphicsCommandList* list, ID3D12CommandAllocator* alloc, ID3D12PipelineState* pso) {
    InflightGuard guard;
    using Fn = HRESULT (STDMETHODCALLTYPE*)(ID3D12GraphicsCommandList*, ID3D12CommandAllocator*, ID3D12PipelineState*);
    HRESULT result = ((Fn)g_resetOriginal)(list, alloc, pso);
    if (SUCCEEDED(result)) {
        AcquireSRWLockExclusive(&g_depthTrackLock);
        g_depthChanges.erase(list);
        ReleaseSRWLockExclusive(&g_depthTrackLock);
    }
    return result;
}
static void depth_submit(UINT count, ID3D12CommandList* const* lists) {
    AcquireSRWLockExclusive(&g_depthTrackLock);
    for (UINT i = 0; i < count; ++i) {
        auto it = g_depthChanges.find(lists[i]);
        if (it == g_depthChanges.end()) continue;
        for (const auto& b : it->second) {
            auto* d = tracked_depth(b.resource);
            if (!d) continue;
            for (UINT plane = 0; plane < d->planes; ++plane) {
                if (b.sub != D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES && b.sub != plane) continue;
                if (b.flags & D3D12_RESOURCE_BARRIER_FLAG_BEGIN_ONLY) { d->split[plane] = true; continue; }
                d->split[plane] = false; d->known[plane] = true; d->state[plane] = b.after;
            }
            d->seen = now_ms();
            if (b.after & D3D12_RESOURCE_STATE_DEPTH_WRITE) ++d->writes;
        }
        // Keep records until Reset: D3D12 permits submitting the same closed list again.
    }
    ReleaseSRWLockExclusive(&g_depthTrackLock);
}
static bool depth_snapshot(UINT width, UINT height, TrackedDepth* out) {
    AcquireSRWLockShared(&g_depthTrackLock);
    const TrackedDepth* best = nullptr;
    uint64_t now = now_ms();
    for (const auto& d : g_trackedDepth) {
        // Borderless presentation can upscale a smaller internal scene buffer.
        if (d.desc.Width > width || d.desc.Height > height || now - d.seen > 2000 || !d.writes) continue;
        int64_t aspectError = (int64_t)d.desc.Width * height - (int64_t)d.desc.Height * width;
        if (aspectError < -((int64_t)width) || aspectError > (int64_t)width) continue;
        bool valid = true;
        for (UINT p = 0; p < d.planes; ++p) valid &= d.known[p] && !d.split[p];
        if (valid && (!best || d.desc.Width * d.desc.Height > best->desc.Width * best->desc.Height ||
            (d.desc.Width * d.desc.Height == best->desc.Width * best->desc.Height && d.writes > best->writes))) best = &d;
    }
    if (best) *out = *best;
    ReleaseSRWLockShared(&g_depthTrackLock);
    return best != nullptr;
}
static void depth_tracking_shutdown() {
    for (auto& d : g_trackedDepth) d.resource->Release();
    g_trackedDepth.clear(); g_depthChanges.clear();
}
