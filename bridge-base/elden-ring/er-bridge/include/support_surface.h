#pragma once
#include <cmath>

// Follow the surface nearest the previous floor. A single ray from a large
// velocity window selects an overhead ceiling before the stationary floor.
template<class Cast>
bool nearest_support(const float* position, float previous, float range, Cast cast, float& floor) {
    for (float window = .125f;; window = std::fmin(range, window + .25f)) {
        float extent = std::fmin(range, window);
        float s[3] = {position[0], previous + extent, position[2]};
        float e[3] = {position[0], previous - extent, position[2]}, hit[3];
        if (cast(s, e, hit) && std::isfinite(hit[1]) && std::fabs(hit[1] - previous) <= extent + .001f) {
            floor = hit[1]; return true;
        }
        if (extent >= range) return false;
    }
}
