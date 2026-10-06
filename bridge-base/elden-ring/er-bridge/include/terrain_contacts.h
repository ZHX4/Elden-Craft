#pragma once
#include "bridge_protocol.h"
#include <cmath>
#include <cstring>

// Local contacts are complete surfaces, not isolated hit voxels. Each ray owns
// a small footprint/strip; adjacent strips meet, including across block borders.
// Native CastRay has NO surface normals and can miss when starting inside solid
// geometry. Verify offset origins from the body corridor before trusting a miss.
struct TerrainContactSampler {
    static constexpr int EDGE = 13, FLOOR_RAYS = EDGE * EDGE;
    // Sample the body densely in the first pass. A sparse hit must never be
    // stretched vertically into an invented wall below a beam or above a step.
    static constexpr int HEIGHTS = 21, WALL_RAYS = 0;
    static constexpr int GUARD_EDGE = 7, GUARD_FLOORS = GUARD_EDGE * GUARD_EDGE;
    static constexpr int GUARD_FLOOR_RAYS = GUARD_FLOORS * 3;
    static constexpr int GUARD_CEILING_RAYS = GUARD_FLOORS * 3;
    static constexpr int GUARD_WALL_EDGE = EDGE;
    static constexpr int GUARD_WALL_ROWS = HEIGHTS + 6;
    static constexpr int GUARD_RAYS = GUARD_FLOOR_RAYS + GUARD_CEILING_RAYS + GUARD_WALL_EDGE * GUARD_WALL_ROWS * 4;
    static constexpr int RAYS = GUARD_RAYS + FLOOR_RAYS * 2 + WALL_RAYS;
    static constexpr float STEP = .125f;
    static_assert(RAYS * 2 <= ERMC_MAX_CONTACTS, "complete collision and air samples fit without truncation");
    ErmcTerrainContacts table = {};
    int cursor = RAYS;
    float high = 0, low = 0, reach = 0;
    float nextX = 0, nextZ = 0, nextY = 0;
    bool verifiedOrigin = false;

    void begin(const float* feet, float previousY, float horizontalTravel, uint32_t zone, float velocityX = 0, float velocityZ = 0,
            float previousX = NAN, float previousZ = NAN, float velocityY = 0) {
        table = {}; table.zone = zone; table.valid = 3;
        std::memcpy(table.origin, feet, sizeof(table.origin));
        table.previousFeetY = previousY;
        table.previousFeetX = std::isfinite(previousX) ? previousX : feet[0];
        table.previousFeetZ = std::isfinite(previousZ) ? previousZ : feet[2];
        // Query above climbable steps, but below ordinary door lintels. Include
        // the previous feet for fast descending players and delayed MC updates.
        high = feet[1] + .625f;
        if (previousY >= feet[1] && previousY - feet[1] <= 16)
            high = std::fmax(high, previousY + .125f);
        low = feet[1] - 24;
        reach = std::fmin(64.f, std::fmax(12.f, 12.f + horizontalTravel * 4));
        nextX = velocityX * .05f; nextZ = velocityZ * .05f;
        nextY = velocityY * .05f;
        cursor = 0;
        verifiedOrigin = false;
    }
    bool done() const { return cursor >= RAYS; }

    void clear(const float* s, const float* e, int axis) {
        if (table.count >= ERMC_MAX_CONTACTS || std::fabs(e[axis] - s[axis]) < .001f) return;
        auto& out = table.contacts[table.count++]; out.kind = ERMC_CONTACT_CLEAR;
        for (int a = 0; a < 3; a++) {
            out.min[a] = std::fmin(s[a], e[a]) - (a == axis ? 0 : STEP / 2);
            out.max[a] = std::fmax(s[a], e[a]) + (a == axis ? 0 : STEP / 2);
        }
    }

    template<class Cast> void step(Cast cast) {
        if (done()) return;
        int ray = cursor++;
        // These forecasts coincide with current probes when the relevant
        // velocity is zero; skip casts rather than query the same surface again.
        if (ray < GUARD_FLOOR_RAYS + GUARD_CEILING_RAYS) {
            int local = ray < GUARD_FLOOR_RAYS ? ray : ray - GUARD_FLOOR_RAYS;
            if (local >= GUARD_FLOORS && std::fabs(nextX) < .001f && std::fabs(nextZ) < .001f) return;
        } else if (ray < GUARD_RAYS && cell_height(ray) >= HEIGHTS && std::fabs(nextY) < .001f) return;
        float s[3], e[3];
        std::memcpy(s, table.origin, sizeof(s));
        std::memcpy(e, s, sizeof(e));
        uint32_t kind;
        int axis = 1, direction = -1;
        bool guard = ray < GUARD_RAYS;
        int normal = ray - GUARD_RAYS;
        if ((guard && ray < GUARD_FLOOR_RAYS + GUARD_CEILING_RAYS) || (!guard && (normal < FLOOR_RAYS || normal >= FLOOR_RAYS + WALL_RAYS))) {
            kind = (guard ? ray < GUARD_FLOOR_RAYS : normal < FLOOR_RAYS) ? ERMC_CONTACT_FLOOR : ERMC_CONTACT_CEILING;
            int cell = guard ? ray % GUARD_FLOORS : (kind == ERMC_CONTACT_FLOOR ? normal : normal - FLOOR_RAYS - WALL_RAYS);
            // Align footprints in native space so successive batches don't
            // introduce seams when the player crosses a sample centre.
            s[0] = e[0] = guard ? table.origin[0] + (cell % GUARD_EDGE - GUARD_EDGE / 2) * STEP
                : (std::floor(table.origin[0] / STEP) + cell % EDGE - EDGE / 2 + .5f) * STEP;
            s[2] = e[2] = guard ? table.origin[2] + (cell / GUARD_EDGE - GUARD_EDGE / 2) * STEP
                : (std::floor(table.origin[2] / STEP) + cell / EDGE - EDGE / 2 + .5f) * STEP;
            // Landings need support at the next simulation feet, not only at
            // the position sent before a fast elytra/flight move.
            if (guard) {
                int forecast = (kind == ERMC_CONTACT_FLOOR ? ray : ray - GUARD_FLOOR_RAYS) / GUARD_FLOORS;
                s[0] = e[0] = s[0] + nextX * forecast;
                s[2] = e[2] = s[2] + nextZ * forecast;
            }
            s[1] = kind == ERMC_CONTACT_FLOOR ? high : table.origin[1] + .65f;
            e[1] = kind == ERMC_CONTACT_FLOOR ? low : table.origin[1] + 2.75f + std::fmax(0.f, nextY * 2);
        } else {
            kind = ERMC_CONTACT_WALL;
            int local = guard ? ray - GUARD_FLOOR_RAYS - GUARD_CEILING_RAYS : normal - FLOOR_RAYS, face = local % 4, cell = local / 4;
            axis = face < 2 ? 0 : 2; direction = (face & 1) ? -1 : 1;
            int cross = axis == 0 ? 2 : 0;
            s[cross] = e[cross] = guard ? table.origin[cross] + (cell % GUARD_WALL_EDGE - GUARD_WALL_EDGE / 2) * STEP
                : (std::floor(table.origin[cross] / STEP) + cell % EDGE - EDGE / 2 + .5f) * STEP;
            int row = cell / GUARD_WALL_EDGE;
            s[1] = e[1] = table.origin[1] + (guard ? wall_height(row) : (cell / EDGE + .5f) * STEP);
            e[axis] += direction * reach;
        }
        float hit[3], anchor[3] = {table.origin[0], s[1], table.origin[2]};
        bool offset = std::fabs(s[0] - anchor[0]) > .001f || std::fabs(s[2] - anchor[2]) > .001f;
        if (offset && cast(anchor, s, hit)) {
            if (kind != ERMC_CONTACT_WALL) return;
            // Sliding beside an oblique wall can put a parallel ray's offset
            // origin INSIDE that wall. Keep the entry face seen from the body
            // instead of treating the following inside-start miss as empty air.
            axis = axis == 0 ? 2 : 0;
            direction = s[axis] > anchor[axis] ? 1 : -1;
            std::memcpy(s, anchor, sizeof(s));
        } else {
            bool present = cast(s, e, hit);
            if (!present) {
                if (verifiedOrigin && kind != ERMC_CONTACT_FLOOR) clear(s, e, axis);
                return;
            }
        }
        for (int a = 0; a < 3; a++) if (!std::isfinite(hit[a])) return;
        // Reject inside/start hits: they cannot establish a traversable boundary.
        if (kind == ERMC_CONTACT_WALL && (hit[axis] - s[axis]) * direction < .005f) return;
        if (kind == ERMC_CONTACT_FLOOR) verifiedOrigin = true;
        if (verifiedOrigin) clear(s, hit, axis);
        if (table.count >= ERMC_MAX_CONTACTS) return;
        auto& out = table.contacts[table.count++]; out.kind = kind;
        if (kind != ERMC_CONTACT_WALL) {
            out.min[0] = s[0] - STEP / 2; out.max[0] = s[0] + STEP / 2;
            out.min[2] = s[2] - STEP / 2; out.max[2] = s[2] + STEP / 2;
            out.min[1] = hit[1] - (kind == ERMC_CONTACT_FLOOR ? STEP : 0);
            out.max[1] = hit[1] + (kind == ERMC_CONTACT_CEILING ? STEP : 0);
        } else {
            int cross = axis == 0 ? 2 : 0;
            out.min[cross] = s[cross] - STEP / 2; out.max[cross] = s[cross] + STEP / 2;
            out.min[1] = s[1] - STEP / 2;
            out.max[1] = s[1] + STEP / 2;
            // Occupy only the blocked side; do not widen a doorway towards air.
            out.min[axis] = hit[axis] - (direction < 0 ? .0625f : 0);
            out.max[axis] = hit[axis] + (direction > 0 ? .0625f : 0);
            // A strip must not extend above a tread and turn a legal .6 m step
            // into a .625 m barrier. Measure the top just behind its entry face;
            // a tall wall's inside-start ray misses and keeps its full barrier.
            if (s[1] <= table.origin[1] + .625f) {
                float above[3] = {hit[0], table.origin[1] + .625f, hit[2]};
                above[axis] += direction * .015625f;
                float below[3] = {above[0], table.origin[1] - .125f, above[2]}, tread[3];
                if (cast(above, below, tread) && std::isfinite(tread[1]) && tread[1] >= s[1] - .001f
                        && tread[1] <= table.origin[1] + .625f) out.max[1] = std::fmin(out.max[1], tread[1]);
            }
        }
    }

    static int cell_height(int ray) { return (ray - GUARD_FLOOR_RAYS - GUARD_CEILING_RAYS) / (4 * GUARD_WALL_EDGE); }
    float wall_height(int row) const {
        // Shin/body/head arrive first, followed by the rest of the connected
        // 1/8-metre rows. The final six rows cover vertical flight forecasts.
        static constexpr int order[HEIGHTS] = {1, 7, 13, 0, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20};
        if (row < HEIGHTS) return (order[row] + .5f) * STEP;
        int future = row - HEIGHTS;
        return (future % 3) * .75f + .1875f + nextY * (1 + future / 3);
    }
};
