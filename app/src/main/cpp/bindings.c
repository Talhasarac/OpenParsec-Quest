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
static pthread_mutex_t g_clipLock = PTHREAD_MUTEX_INITIALIZER;
static char *g_pendingClipboard = NULL;     // owned malloc'd copy of latest host user-data text

/* User-data message id used for clipboard interop. The official Parsec
 * desktop host's reserved clipboard id isn't in this public header, so the
 * SEND path is best-effort/experimental; the RECEIVE path treats ANY host
 * user-data as clipboard text (a desktop host's only user-data to a guest
 * is the clipboard). */
#define CLIPBOARD_MSG_ID 1

static void logCallback(ParsecLogLevel level, char *msg, void *opaque)
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

    setPointer(env, instance, "parsec", NULL);
    setPointer(env, instance, "aaudio", NULL);
}

JNIEXPORT jint JNICALL
Java_parsec_bindings_Parsec_clientConnect(JNIEnv *env, jobject instance, jstring sessionID,
    jstring peerID, jint decoderSoftware, jint resolutionX, jint resolutionY, jint refreshRate)
{
    Parsec *parsec = getPointer(env, instance, "parsec");

    const char *cSessionID = (*env)->GetStringUTFChars(env, sessionID, 0);
    const char *cPeerID = (*env)->GetStringUTFChars(env, peerID, 0);

    // Build a real ParsecClientConfig instead of passing NULL so the user's
    // Settings actually reach the SDK. NOTE: resolutionX/Y + refreshRate only
    // take effect when this client is the FIRST connection AND the owner of a
    // HOST_DESKTOP machine (per the SDK docs) — i.e. when streaming your own
    // PC. They're harmlessly ignored otherwise. Bitrate / H.265 / encoder-FPS
    // are host-side encoder settings (ParsecHostConfig) with no client field,
    // so they are intentionally NOT here.
    ParsecClientConfig cfg = {0};
    cfg.mediaContainer = CONTAINER_PARSEC; // native decode path
    cfg.protocol = PROTO_MODE_BUD;         // Parsec's low-latency transport
    cfg.decoderSoftware = decoderSoftware ? 1 : 0;
    cfg.resolutionX = resolutionX;
    cfg.resolutionY = resolutionY;
    cfg.refreshRate = refreshRate;
    cfg.pngCursor = false;                 // we render our own cursor

    // Fresh session — clear any stale event state.
    g_cursorRelative = 0;
    g_rumbleNew = 0;

    ParsecStatus e = ParsecClientConnect(parsec, &cfg, (char *) cSessionID, (char *) cPeerID);

    (*env)->ReleaseStringUTFChars(env, sessionID, cSessionID);
    (*env)->ReleaseStringUTFChars(env, peerID, cPeerID);

    return (jint) e;
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientPollAudio(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    struct aaudio *aaudio = getPointer(env, instance, "aaudio");

    ParsecClientPollAudio(parsec, aaudio_play, 0, aaudio);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientDestroy(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecClientDisconnect(parsec);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientSetDimensions(JNIEnv *env, jobject instance,
    jint x, jint y)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecClientSetDimensions(parsec, (uint32_t) x, (uint32_t) y, 1.0f);
}

JNIEXPORT void JNICALL
Java_parsec_bindings_Parsec_clientGLRenderFrame(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    ParsecClientGLRenderFrame(parsec, 0);
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
    jlong dec = (jlong) (status.metrics.decodeLatency * 1000.0f);
    jlong net = (jlong) (status.metrics.networkLatency * 1000.0f);
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
    return status.metrics.decodeLatency;
}

JNIEXPORT jfloat JNICALL
Java_parsec_bindings_Parsec_clientGetNetworkLatency(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0.0f;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0.0f;
    return status.metrics.networkLatency;
}

JNIEXPORT jfloat JNICALL
Java_parsec_bindings_Parsec_clientGetEncodeLatency(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return 0.0f;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return 0.0f;
    return status.metrics.encodeLatency;
}

JNIEXPORT jboolean JNICALL
Java_parsec_bindings_Parsec_clientDecoderFellBack(JNIEnv *env, jobject instance)
{
    Parsec *parsec = getPointer(env, instance, "parsec");
    if (!parsec) return JNI_FALSE;
    ParsecClientStatus status = {0};
    if (ParsecClientGetStatus(parsec, &status) != PARSEC_OK) return JNI_FALSE;
    return status.decoderFallback ? JNI_TRUE : JNI_FALSE;
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
                if (evt.cursor.cursor.modeUpdate)
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
                    char *copy = strdup((const char *) buf);
                    ParsecFree(buf);
                    if (copy) {
                        pthread_mutex_lock(&g_clipLock);
                        free(g_pendingClipboard);
                        g_pendingClipboard = copy;
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
