#include <stdlib.h>
#include <stdbool.h>
#include <memory.h>
#include <string.h>
#include <pthread.h>

#include <unistd.h>

#include <jni.h>
#include <android/log.h>

#include "parsec.h"
#include "aaudio.h"

/* ---- Client-event state, updated from the GL render thread via
 *      clientPollEvents and read from the UI thread via the getters below.
 *      The app only ever has one active client, so module statics are fine. */
static volatile int g_cursorRelative = 0;   // host requested relative (pointer-lock) mode
static volatile int g_rumbleBig = 0;        // last large-motor rumble value (0-255)
static volatile int g_rumbleSmall = 0;      // last small-motor rumble value (0-255)
static volatile int g_rumbleNew = 0;        // 1 if an unconsumed rumble event is pending
static volatile int g_requestedSoftwareDecoder = 0;
static pthread_mutex_t g_clipLock = PTHREAD_MUTEX_INITIALIZER;
static char *g_pendingClipboard = NULL;     // owned malloc'd copy of latest host user-data text
static char *g_pendingVideoConfig = NULL;   // owned copy of user-data message 11

/* User-data ids not exposed by this legacy public SDK header. Clipboard id 1
 * remains best-effort; video-config ids 9/11 match current official clients. */
#define CLIPBOARD_MSG_ID 1
#define VIDEO_CONFIG_MSG_ID 11

static void logCallback(ParsecLogLevel level, const char *msg, void *opaque)
{
    __android_log_print(ANDROID_LOG_INFO, "PARSEC", "%s", msg);
}

static void *getPointer(JNIEnv *env, jobject instance, const char *name)
{
    jclass cls = (*env)->GetObjectClass(env, instance);
    jfieldID id = (*env)->GetFieldID(env, cls, name, "J");
    return (void *) (*env)->GetLongField(env, instance, id);
}

static void setPointer(JNIEnv *env, jobject instance, const char *name, void *ptr)
{
    jclass cls = (*env)->GetObjectClass(env, instance);
    jfieldID id = (*env)->GetFieldID(env, cls, name, "J");
    (*env)->SetLongField(env, instance, id, (long) ptr);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_setLogCallback(JNIEnv *env, jobject instance)
{
    ParsecSetLogCallback(logCallback, NULL);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_init(JNIEnv *env, jobject instance)
{
    Parsec *parsec = NULL;
    ParsecInit(PARSEC_VER, NULL, NULL, &parsec);

    struct aaudio *aaudio = NULL;
    aaudio_init(&aaudio);

    setPointer(env, instance, "parsec", parsec);
    setPointer(env, instance, "aaudio", aaudio);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_destroy(JNIEnv *env, jobject instance)
{
    struct aaudio *aaudio = getPointer(env, instance, "aaudio");
    aaudio_destroy(&aaudio);

    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecDestroy(parsec);

    pthread_mutex_lock(&g_clipLock);
    free(g_pendingClipboard);
    g_pendingClipboard = NULL;
    free(g_pendingVideoConfig);
    g_pendingVideoConfig = NULL;
    pthread_mutex_unlock(&g_clipLock);

    setPointer(env, instance, "parsec", NULL);
    setPointer(env, instance, "aaudio", NULL);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientConnect(JNIEnv *env, jobject instance, jstring sessionID,
    jstring peerID, jint decoderSoftware, jint decoderH265, jint resolutionX, jint resolutionY)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    const char *cSessionID = (*env)->GetStringUTFChars(env, sessionID, 0);
    const char *cPeerID = (*env)->GetStringUTFChars(env, peerID, 0);

    // The 2021 SDK exposes one video config per stream. decoderH265 is a
    // client capability/preference: the host falls back to H.264 if HEVC is
    // unsupported by any participant.
    ParsecClientConfig cfg = {0};
    for (uint8_t stream = 0; stream < NUM_VSTREAMS; stream++) {
        cfg.video[stream].decoderIndex = decoderSoftware ? 0 : 1;
        cfg.video[stream].resolutionX = resolutionX;
        cfg.video[stream].resolutionY = resolutionY;
        cfg.video[stream].decoderCompatibility = false;
        cfg.video[stream].decoderH265 = decoderH265 ? true : false;
        cfg.video[stream].decoder444 = false;
    }
    cfg.mediaContainer = CONTAINER_PARSEC;
    cfg.protocol = PROTO_MODE_BUD;
    cfg.pngCursor = false;
    g_requestedSoftwareDecoder = decoderSoftware ? 1 : 0;

    // Fresh session — clear any stale event state.
    g_cursorRelative = 0;
    g_rumbleNew = 0;
    pthread_mutex_lock(&g_clipLock);
    free(g_pendingClipboard);
    g_pendingClipboard = NULL;
    free(g_pendingVideoConfig);
    g_pendingVideoConfig = NULL;
    pthread_mutex_unlock(&g_clipLock);

    ParsecStatus e = ParsecClientConnect(parsec, &cfg, (char *) cSessionID, (char *) cPeerID);

    (*env)->ReleaseStringUTFChars(env, sessionID, cSessionID);
    (*env)->ReleaseStringUTFChars(env, peerID, cPeerID);

    return (jint) e;
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSetConfig(JNIEnv *env, jobject instance,
    jint decoderSoftware, jint decoderH265, jint resolutionX, jint resolutionY)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec)
        return (jint) PARSEC_NOT_RUNNING;

    ParsecClientConfig cfg = {0};
    for (uint8_t stream = 0; stream < NUM_VSTREAMS; stream++) {
        cfg.video[stream].decoderIndex = decoderSoftware ? 0 : 1;
        cfg.video[stream].resolutionX = resolutionX;
        cfg.video[stream].resolutionY = resolutionY;
        cfg.video[stream].decoderCompatibility = false;
        cfg.video[stream].decoderH265 = decoderH265 ? true : false;
        cfg.video[stream].decoder444 = false;
    }
    cfg.mediaContainer = CONTAINER_PARSEC;
    cfg.protocol = PROTO_MODE_BUD;
    cfg.pngCursor = false;
    ParsecStatus status = ParsecClientSetConfig(parsec, &cfg);
    if (status == PARSEC_OK)
        g_requestedSoftwareDecoder = decoderSoftware ? 1 : 0;
    return (jint) status;
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientPollAudio(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    struct aaudio *aaudio = getPointer(env, instance, "aaudio");
    if (!parsec || !aaudio)
        return;

    // PollAudio returns one queued packet at a time. Calling it only once per
    // video frame leaves the SDK queue permanently behind whenever audio
    // packets arrive faster than frames render. Drain the current backlog;
    // aaudio_play uses a bounded, non-blocking device buffer and drops excess
    // stale packets so playback catches the live edge instead of accumulating
    // seconds of stable delay.
    const int max_packets_per_frame = 128;
    for (int packet = 0; packet < max_packets_per_frame; packet++) {
        if (ParsecClientPollAudio(parsec, aaudio_play, 0, aaudio) != PARSEC_OK)
            break;
    }
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientPauseAudio(JNIEnv *env, jobject instance)
{
    struct aaudio *aaudio = getPointer(env, instance, "aaudio");
    aaudio_pause(aaudio);
}

static void discard_audio(const int16_t *pcm, uint32_t frames, void *opaque)
{
    (void) pcm;
    (void) frames;
    (void) opaque;
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientResumeAudio(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    struct aaudio *aaudio = getPointer(env, instance, "aaudio");
    if (!parsec || !aaudio)
        return 0;

    // Keep AAudio paused while rapidly draining packets the SDK accumulated
    // during headset sleep. Playing these packets is what caused sound to
    // remain seconds behind video after putting the Quest back on.
    aaudio_pause(aaudio);
    int drained = 0;
    const int max_drain_packets = 4096;
    while (drained < max_drain_packets
        && ParsecClientPollAudio(parsec, discard_audio, 0, NULL) == PARSEC_OK) {
        drained++;
    }

    aaudio_resume(aaudio);
    return (jint) drained;
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientDestroy(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecClientDisconnect(parsec);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSetDimensions(JNIEnv *env, jobject instance,
    jint x, jint y)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec || x <= 0 || y <= 0)
        return (jint) PARSEC_NOT_RUNNING;
    return (jint) ParsecClientSetDimensions(
        parsec, 0, (uint32_t) x, (uint32_t) y, 1.0f);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientGLRenderFrame(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecClientGLRenderFrame(parsec, 0, NULL, NULL, 0);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendMouseMotion(JNIEnv *env, jobject instance,
    jboolean relative, jint x, jint y)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    ParsecMessage msg = {};
    msg.type = MESSAGE_MOUSE_MOTION;
    msg.mouseMotion.relative = relative;
    msg.mouseMotion.x = x;
    msg.mouseMotion.y = y;

    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendMouseWheel(JNIEnv *env, jobject instance, jint x, jint y)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    ParsecMessage msg = {};
    msg.type = MESSAGE_MOUSE_WHEEL;
    msg.mouseWheel.x = x;
    msg.mouseWheel.y = y;

    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendGamepadButton(JNIEnv *env, jobject instance,
        jint gamepadID, jint button, jboolean pressed)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    ParsecMessage msg = {};
    msg.type = MESSAGE_GAMEPAD_BUTTON;
    msg.gamepadButton.button = (ParsecGamepadButton) button;
    msg.gamepadButton.id = (uint32_t) gamepadID;
    msg.gamepadButton.pressed = pressed;

    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendGamepadAxis(JNIEnv *env, jobject instance,
        jint gamepadID, jint axis, jint value)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    ParsecMessage msg = {};
    msg.type = MESSAGE_GAMEPAD_AXIS;
    msg.gamepadAxis.axis = (ParsecGamepadAxis) axis;
    msg.gamepadAxis.id = (uint32_t) gamepadID;
    // ParsecGamepadAxisMessage.value is int16_t; cast through that so the
    // sign bit is preserved when the Java side passes a negative axis value.
    msg.gamepadAxis.value = (int16_t) value;

    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendGamepadUnplug(JNIEnv *env, jobject instance,
        jint gamepadID)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecMessage msg = {};
    msg.type = MESSAGE_GAMEPAD_UNPLUG;
    msg.gamepadUnplug.id = (uint32_t) gamepadID;
    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendKeyboard(JNIEnv *env, jobject instance,
        jint keyCode, jint keyMod, jboolean pressed)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    ParsecMessage msg = {};
    msg.type = MESSAGE_KEYBOARD;
    msg.keyboard.code = (ParsecKeycode) keyCode;
    msg.keyboard.mod = (ParsecKeymod) keyMod;
    msg.keyboard.pressed = pressed;

    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendMouseButton(JNIEnv *env, jobject instance,
    jint button, jboolean pressed)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    ParsecMessage msg = {};
    msg.type = MESSAGE_MOUSE_BUTTON;
    msg.mouseButton.button = (ParsecMouseButton) button;
    msg.mouseButton.pressed = pressed;

    return (jint) ParsecClientSendMessage(parsec, &msg);
}

JNIEXPORT jboolean JNICALL
Java_parsec_bindings_Parsec_clientHasNetworkFailure(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return JNI_TRUE;
    ParsecClientStatus status = {0};
    ParsecStatus rc = ParsecClientGetStatus(parsec, &status);
    if (rc != PARSEC_OK) return JNI_TRUE;
    return status.networkFailure ? JNI_TRUE : JNI_FALSE;
}

/** Returns a freeze-detection signal — the SDK's reported decode latency
 *  (milliseconds, float) scaled to 1000ths and packed in a jlong alongside
 *  the network latency. Bits 32-63 carry decodeLatency * 1000; bits 0-31
 *  carry networkLatency * 1000. The activity-level watchdog samples this
 *  every 5s; if neither value changes for 15s the client is considered
 *  hung and reconnects, even when networkFailure has NOT tripped (which
 *  happens when the transport is alive but no fresh frames decode). */
JNIEXPORT jlong JNICALL
Java_parsec_bindings_Parsec_clientGetFreezeSignal(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0;
    jlong dec = (jlong) (status.self.metrics[0].decodeLatency * 1000.0f);
    jlong net = (jlong) (status.self.metrics[0].networkLatency * 1000.0f);
    return (dec << 32) | (net & 0xFFFFFFFFL);
}

/* ---- Latency / decoder metrics for the stats overlay ---- */
JNIEXPORT jfloat JNICALL
Java_parsec_bindings_Parsec_clientGetDecodeLatency(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0.0f;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0.0f;
    return status.self.metrics[0].decodeLatency;
}

JNIEXPORT jfloat JNICALL
Java_parsec_bindings_Parsec_clientGetNetworkLatency(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0.0f;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0.0f;
    return status.self.metrics[0].networkLatency;
}

JNIEXPORT jfloat JNICALL
Java_parsec_bindings_Parsec_clientGetEncodeLatency(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0.0f;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0.0f;
    return status.self.metrics[0].encodeLatency;
}

JNIEXPORT jboolean JNICALL
Java_parsec_bindings_Parsec_clientDecoderFellBack(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return JNI_FALSE;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return JNI_FALSE;
    return !g_requestedSoftwareDecoder
        && status.decoder[0].width > 0
        && status.decoder[0].index == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_parsec_bindings_Parsec_clientIsH265(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return JNI_FALSE;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return JNI_FALSE;
    return status.decoder[0].h265 ? JNI_TRUE : JNI_FALSE;
}

/** Return all performance-overlay values from one status read so the Java
 *  classifier never combines counters and latencies from different frames.
 *
 *  values[0] packetsSent (unsigned 32-bit)
 *  values[1] fastRTs + slowRTs (unsigned 32-bit, wrapping)
 *  values[2] queuedFrames
 *  values[3] decodeLatency float bits << 32 | networkLatency float bits
 *  values[4] encodeLatency float bits << 32 | flags
 *            flags: bit 0 network failure, bit 1 decoder fallback, bit 2 H.265
 */
JNIEXPORT jlongArray JNICALL
Java_parsec_bindings_Parsec_clientGetPerformanceSnapshot(
    JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return NULL;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return NULL;

    const ParsecMetrics *metrics = &status.self.metrics[0];
    uint32_t decBits = 0;
    uint32_t netBits = 0;
    uint32_t encBits = 0;
    memcpy(&decBits, &metrics->decodeLatency, sizeof(decBits));
    memcpy(&netBits, &metrics->networkLatency, sizeof(netBits));
    memcpy(&encBits, &metrics->encodeLatency, sizeof(encBits));

    uint32_t flags = status.networkFailure ? 1U : 0U;
    if (!g_requestedSoftwareDecoder
        && status.decoder[0].width > 0
        && status.decoder[0].index == 0) {
        flags |= 1U << 1;
    }
    if (status.decoder[0].h265) flags |= 1U << 2;

    jlong values[5] = {
        (jlong) metrics->packetsSent,
        (jlong) (uint32_t) (metrics->fastRTs + metrics->slowRTs),
        (jlong) metrics->queuedFrames,
        ((jlong) decBits << 32) | (jlong) netBits,
        ((jlong) encBits << 32) | (jlong) flags,
    };
    jlongArray result = (*env)->NewLongArray(env, 5);
    if (!result) return NULL;
    (*env)->SetLongArrayRegion(env, result, 0, 5, values);
    return result;
}

/** Atomically return the active decoder dimensions as (width << 32 | height). */
JNIEXPORT jlong JNICALL
Java_parsec_bindings_Parsec_clientGetVideoSize(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0;
    return ((jlong) status.decoder[0].width << 32)
        | ((jlong) status.decoder[0].height & 0xFFFFFFFFL);
}

/* ---- Client event pump. Called from the GL render thread once per frame.
 *      Drains all pending events (timeout 0) and stashes their state for the
 *      UI thread to consume via the getters below. */
JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientPollEvents(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return;

    ParsecClientEvent evt;
    while (ParsecClientPollEvents(parsec, 0, &evt)) {
        switch (evt.type) {
            case CLIENT_EVENT_CURSOR:
                g_cursorRelative = evt.cursor.cursor.relative ? 1 : 0;
                // We render our own cursor, so drain any image buffer to
                // avoid leaking it.
                if (evt.cursor.cursor.imageUpdate && evt.cursor.key) {
                    void *buf = ParsecGetBuffer(parsec, evt.cursor.key);
                    if (buf) ParsecFree(buf);
                }
                break;
            case CLIENT_EVENT_RUMBLE:
                g_rumbleBig = evt.rumble.motorBig;
                g_rumbleSmall = evt.rumble.motorSmall;
                g_rumbleNew = 1;
                break;
            case CLIENT_EVENT_USER_DATA: {
                void *buf = ParsecGetBuffer(parsec, evt.userData.key);
                if (buf) {
                    char *copy = (evt.userData.id == CLIPBOARD_MSG_ID
                            || evt.userData.id == VIDEO_CONFIG_MSG_ID)
                        ? strdup((const char *) buf) : NULL;
                    ParsecFree(buf);
                    if (copy) {
                        pthread_mutex_lock(&g_clipLock);
                        if (evt.userData.id == CLIPBOARD_MSG_ID) {
                            free(g_pendingClipboard);
                            g_pendingClipboard = copy;
                        } else {
                            free(g_pendingVideoConfig);
                            g_pendingVideoConfig = copy;
                        }
                        pthread_mutex_unlock(&g_clipLock);
                    }
                }
                break;
            }
            default:
                break;
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_parsec_bindings_Parsec_clientGetCursorRelative(JNIEnv *env, jobject instance)
{
    return g_cursorRelative ? JNI_TRUE : JNI_FALSE;
}

/** Returns packed (big<<8 | small) rumble values if a new rumble event is
 *  pending, clearing the flag; otherwise -1. */
JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientPollRumble(JNIEnv *env, jobject instance)
{
    if (!g_rumbleNew) return -1;
    g_rumbleNew = 0;
    return ((g_rumbleBig & 0xFF) << 8) | (g_rumbleSmall & 0xFF);
}

/** Returns the latest host user-data (clipboard) text and clears it, or null. */
JNIEXPORT jstring JNICALL
Java_parsec_bindings_Parsec_clientPollClipboard(JNIEnv *env, jobject instance)
{
    char *s = NULL;
    pthread_mutex_lock(&g_clipLock);
    s = g_pendingClipboard;
    g_pendingClipboard = NULL;
    pthread_mutex_unlock(&g_clipLock);
    if (!s) return NULL;
    jstring js = (*env)->NewStringUTF(env, s);
    free(s);
    return js;
}

/** Returns the newest host video-config JSON (message 11), or null. */
JNIEXPORT jstring JNICALL
Java_parsec_bindings_Parsec_clientPollVideoConfig(JNIEnv *env, jobject instance)
{
    char *s = NULL;
    pthread_mutex_lock(&g_clipLock);
    s = g_pendingVideoConfig;
    g_pendingVideoConfig = NULL;
    pthread_mutex_unlock(&g_clipLock);
    if (!s) return NULL;
    jstring js = (*env)->NewStringUTF(env, s);
    free(s);
    return js;
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientSendUserData(JNIEnv *env, jobject instance,
    jint id, jstring text)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec || !text) return -1;
    const char *c = (*env)->GetStringUTFChars(env, text, 0);
    ParsecStatus e = ParsecClientSendUserData(parsec, (uint32_t) id, (char *) c);
    (*env)->ReleaseStringUTFChars(env, text, c);
    return (jint) e;
}
