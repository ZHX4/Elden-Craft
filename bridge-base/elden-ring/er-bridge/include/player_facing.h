#pragma once
#include <math.h>

namespace mb {

// The character's local forward is -Z. Its quaternion rotates that axis, whereas
// map assets' MSB yaw uses +Z. Keep that distinction at the direction-to-yaw boundary.
inline float player_yaw_from_forward(float x, float z) {
    return atan2f(-x, -z);
}

inline float player_yaw_from_minecraft(float degrees) {
    float y = degrees * 3.14159265f / 180.0f;
    // Minecraft yaw 0 faces +Z; the coordinate mapping flips Z into host space.
    return player_yaw_from_forward(-sinf(y), -cosf(y));
}

}  // namespace mb
