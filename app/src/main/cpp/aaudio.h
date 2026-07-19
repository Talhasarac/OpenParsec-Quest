#pragma once

#include <stdint.h>

struct aaudio;

void aaudio_init(struct aaudio **ctx_out);
void aaudio_destroy(struct aaudio **ctx_out);
void aaudio_pause(struct aaudio *ctx);
void aaudio_resume(struct aaudio *ctx);
void aaudio_play(int16_t *pcm, uint32_t frames, void *opaque);
