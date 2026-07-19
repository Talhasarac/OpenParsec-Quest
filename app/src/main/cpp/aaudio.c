#include "aaudio.h"

#include <stdbool.h>
#include <stdlib.h>

#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <android/log.h>

struct aaudio {
    AAudioStreamBuilder *builder;
    AAudioStream *stream;
    int32_t last_underruns;
    bool paused;
};

static void aaudio_errorcallback(AAudioStream *stream, void *userData, aaudio_result_t error)
{
    __android_log_print(ANDROID_LOG_INFO, "PARSEC", "aaudio error %d", error);
}

static bool aaudio_open_stream(struct aaudio *ctx)
{
    if (!ctx || !ctx->builder)
        return false;

    if (ctx->stream) {
        AAudioStream_close(ctx->stream);
        ctx->stream = NULL;
    }

    aaudio_result_t result = AAudioStreamBuilder_openStream(ctx->builder, &ctx->stream);
    if (result != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "PARSEC",
            "failed to open aaudio stream: %s", AAudio_convertResultToText(result));
        ctx->stream = NULL;
        return false;
    }

    ctx->last_underruns = AAudioStream_getXRunCount(ctx->stream);
    return true;
}

static bool aaudio_wait_for_state(
    AAudioStream *stream, aaudio_stream_state_t wanted, int attempts)
{
    if (!stream)
        return false;

    aaudio_stream_state_t state = AAudioStream_getState(stream);
    while (state != wanted && attempts-- > 0) {
        if (state == AAUDIO_STREAM_STATE_CLOSED
            || state == AAUDIO_STREAM_STATE_DISCONNECTED)
            return false;

        aaudio_stream_state_t next = state;
        aaudio_result_t result =
            AAudioStream_waitForStateChange(stream, state, &next, 25000000);
        if (result != AAUDIO_OK && result != AAUDIO_ERROR_TIMEOUT)
            return false;
        state = next;
    }
    return state == wanted;
}

void aaudio_init(struct aaudio **ctx_out)
{
    struct aaudio *ctx = *ctx_out = calloc(1, sizeof(struct aaudio));

    AAudio_createStreamBuilder(&ctx->builder);
    AAudioStreamBuilder_setDeviceId(ctx->builder, AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setSampleRate(ctx->builder, 48000);
    AAudioStreamBuilder_setChannelCount(ctx->builder, 2);
    AAudioStreamBuilder_setFormat(ctx->builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(ctx->builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setErrorCallback(ctx->builder, aaudio_errorcallback, NULL);

    // Route remote-desktop audio as full-bandwidth media playback, not as a
    // voice call. Without these, AAudio's defaults make Android treat the
    // stream like a phone call: earpiece speaker, narrow-band, voice
    // processing (echo cancel / noise suppress) which mangles music. Both
    // setters were added in Android 9 (Pie, API 28). __builtin_available
    // satisfies Clang's availability check and at runtime falls through to
    // the unguarded defaults on API 27.
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setUsage(ctx->builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(ctx->builder, AAUDIO_CONTENT_TYPE_MOVIE);
    }

    aaudio_open_stream(ctx);
}

void aaudio_destroy(struct aaudio **ctx_out)
{
    if (!ctx_out || !*ctx_out)
        return;

    struct aaudio *ctx = *ctx_out;

    if (ctx->stream) {
        AAudioStream_requestStop(ctx->stream);
        AAudioStream_close(ctx->stream);
    }

    if (ctx->builder)
        AAudioStreamBuilder_delete(ctx->builder);

    free(ctx);
    *ctx_out = NULL;
}

void aaudio_pause(struct aaudio *ctx)
{
    if (!ctx)
        return;

    // Set this before touching the stream. If an SDK audio callback is already
    // in flight, aaudio_play will discard it instead of restarting playback.
    ctx->paused = true;
    if (!ctx->stream)
        return;

    aaudio_stream_state_t state = AAudioStream_getState(ctx->stream);
    if (state == AAUDIO_STREAM_STATE_STARTED
        || state == AAUDIO_STREAM_STATE_STARTING) {
        if (AAudioStream_requestPause(ctx->stream) == AAUDIO_OK)
            aaudio_wait_for_state(ctx->stream, AAUDIO_STREAM_STATE_PAUSED, 4);
    }

    state = AAudioStream_getState(ctx->stream);
    if (state == AAUDIO_STREAM_STATE_PAUSED
        || state == AAUDIO_STREAM_STATE_PAUSING) {
        if (state == AAUDIO_STREAM_STATE_PAUSING)
            aaudio_wait_for_state(ctx->stream, AAUDIO_STREAM_STATE_PAUSED, 4);
        if (AAudioStream_requestFlush(ctx->stream) == AAUDIO_OK)
            aaudio_wait_for_state(ctx->stream, AAUDIO_STREAM_STATE_FLUSHED, 4);
    }
}

void aaudio_resume(struct aaudio *ctx)
{
    if (!ctx)
        return;

    if (!ctx->stream
        || AAudioStream_getState(ctx->stream) == AAUDIO_STREAM_STATE_DISCONNECTED
        || AAudioStream_getState(ctx->stream) == AAUDIO_STREAM_STATE_CLOSED) {
        if (!aaudio_open_stream(ctx))
            return;
    }

    ctx->paused = false;
    aaudio_result_t result = AAudioStream_requestStart(ctx->stream);
    if (result != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_WARN, "PARSEC",
            "failed to restart aaudio stream: %s", AAudio_convertResultToText(result));
    }
}

void aaudio_play(int16_t *pcm, uint32_t frames, void *opaque)
{
    struct aaudio *ctx = (struct aaudio *) opaque;
    if (!ctx || !ctx->stream || ctx->paused)
        return;

    aaudio_stream_state_t state = AAudioStream_getState(ctx->stream);
    if (state == AAUDIO_STREAM_STATE_DISCONNECTED
        || state == AAUDIO_STREAM_STATE_CLOSED) {
        if (!aaudio_open_stream(ctx))
            return;
        state = AAudioStream_getState(ctx->stream);
    }

    if (state != AAUDIO_STREAM_STATE_STARTED
        && state != AAUDIO_STREAM_STATE_STARTING) {
        AAudioStream_requestStart(ctx->stream);
    }

    AAudioStream_write(ctx->stream, pcm, frames, 40000000); // 40ms

    int32_t underruns = AAudioStream_getXRunCount(ctx->stream);
    if (underruns > ctx->last_underruns)
        ctx->last_underruns = underruns;
}
