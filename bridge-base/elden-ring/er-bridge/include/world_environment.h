#pragma once
#include <stdint.h>

namespace mb {
// Minecraft's clock starts at 06:00. Elden Ring requests wall-clock H:M:S.
inline uint32_t environment_seconds(uint32_t dayTicks) {
    return ((dayTicks % 24000u) * 18u / 5u + 21600u) % 86400u;
}
inline int16_t environment_weather(uint32_t weather) {
    // Native requests take WeatherParam row suffixes, not EMEVD/cutscene enums.
    // Rain 20: SFX/audio 806100. Windy rain 30: SFX/audio 806130.
    // Minecraft thunder uses WindyRain plus the native Jagged Peak sky ambience.
    // https://github.com/soulsmods/Paramdex/blob/master/ER/Names/WeatherParam.txt
    return weather == 2 ? 30 : weather == 1 ? 20 : 1;
}
} // namespace mb
