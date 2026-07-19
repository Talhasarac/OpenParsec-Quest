package com.example.parsecdemo;

/**
 * Converts noisy Parsec performance samples into stable overlay warnings.
 *
 * <p>Samples arrive twice per second. A warning needs three consecutive bad
 * samples (1.5 seconds) before it appears and six consecutive healthy samples
 * (3 seconds) before it clears. Values between the bad and healthy thresholds
 * keep the current state, which prevents a badge from flickering at a
 * threshold.</p>
 */
final class PerformanceWarningTracker {
    static final int SHOW_AFTER_BAD_SAMPLES = 3;
    static final int HIDE_AFTER_GOOD_SAMPLES = 6;

    static final float NETWORK_BAD_LATENCY_MS = 80f;
    static final float NETWORK_GOOD_LATENCY_MS = 60f;
    static final float NETWORK_BAD_RETRANSMIT_RATIO = 0.02f;
    static final float NETWORK_GOOD_RETRANSMIT_RATIO = 0.005f;
    static final int NETWORK_MIN_PACKETS_FOR_RATIO = 20;
    static final int NETWORK_BAD_RETRANSMITS = 4;

    static final float DEVICE_BAD_DECODE_MS = 25f;
    static final float DEVICE_GOOD_DECODE_MS = 18f;
    static final int DEVICE_BAD_QUEUED_FRAMES = 3;

    static final class State {
        final boolean networkWarning;
        final boolean deviceWarning;

        State(boolean networkWarning, boolean deviceWarning) {
            this.networkWarning = networkWarning;
            this.deviceWarning = deviceWarning;
        }
    }

    private boolean networkWarning;
    private boolean deviceWarning;
    private int networkBadSamples;
    private int networkGoodSamples;
    private int deviceBadSamples;
    private int deviceGoodSamples;

    private boolean hasCounterBaseline;
    private long previousPackets;
    private long previousRetransmits;

    State update(boolean streamActive,
                 boolean networkFailure,
                 float networkLatencyMs,
                 float decodeLatencyMs,
                 int targetFps,
                 long queuedFrames,
                 boolean decoderFellBack,
                 long packets,
                 long retransmits) {
        if (!streamActive) {
            reset();
            return state();
        }

        packets &= 0xFFFFFFFFL;
        retransmits &= 0xFFFFFFFFL;

        boolean lossBad = false;
        boolean lossGood = true;
        if (hasCounterBaseline) {
            long packetDelta = unsignedDelta(packets, previousPackets);
            long retransmitDelta = unsignedDelta(retransmits, previousRetransmits);
            boolean enoughTraffic = packetDelta >= NETWORK_MIN_PACKETS_FOR_RATIO;
            float ratio = enoughTraffic
                    ? retransmitDelta / (float) packetDelta
                    : 0f;
            lossBad = retransmitDelta >= NETWORK_BAD_RETRANSMITS
                    || (enoughTraffic && ratio >= NETWORK_BAD_RETRANSMIT_RATIO);
            lossGood = retransmitDelta == 0
                    || (enoughTraffic && ratio <= NETWORK_GOOD_RETRANSMIT_RATIO);
        }
        previousPackets = packets;
        previousRetransmits = retransmits;
        hasCounterBaseline = true;

        boolean networkBad = networkFailure
                || networkLatencyMs >= NETWORK_BAD_LATENCY_MS
                || lossBad;
        boolean networkGood = !networkFailure
                && networkLatencyMs <= NETWORK_GOOD_LATENCY_MS
                && lossGood;

        int effectiveFps = targetFps >= 30 && targetFps <= 120
                ? targetFps : 60;
        float frameRateScale = 60f / effectiveFps;
        float badDecodeMs = DEVICE_BAD_DECODE_MS * frameRateScale;
        float goodDecodeMs = DEVICE_GOOD_DECODE_MS * frameRateScale;
        boolean deviceBad = decoderFellBack
                || queuedFrames >= DEVICE_BAD_QUEUED_FRAMES
                || decodeLatencyMs >= badDecodeMs;
        boolean deviceGood = !decoderFellBack
                && queuedFrames == 0
                && decodeLatencyMs <= goodDecodeMs;

        updateNetworkState(networkBad, networkGood);
        updateDeviceState(deviceBad, deviceGood);
        return state();
    }

    void reset() {
        networkWarning = false;
        deviceWarning = false;
        networkBadSamples = 0;
        networkGoodSamples = 0;
        deviceBadSamples = 0;
        deviceGoodSamples = 0;
        hasCounterBaseline = false;
        previousPackets = 0L;
        previousRetransmits = 0L;
    }

    private void updateNetworkState(boolean bad, boolean good) {
        if (bad) {
            networkGoodSamples = 0;
            networkBadSamples = Math.min(SHOW_AFTER_BAD_SAMPLES, networkBadSamples + 1);
            if (networkBadSamples >= SHOW_AFTER_BAD_SAMPLES) networkWarning = true;
        } else if (good) {
            networkBadSamples = 0;
            networkGoodSamples = Math.min(HIDE_AFTER_GOOD_SAMPLES, networkGoodSamples + 1);
            if (networkGoodSamples >= HIDE_AFTER_GOOD_SAMPLES) networkWarning = false;
        } else {
            networkBadSamples = 0;
            networkGoodSamples = 0;
        }
    }

    private void updateDeviceState(boolean bad, boolean good) {
        if (bad) {
            deviceGoodSamples = 0;
            deviceBadSamples = Math.min(SHOW_AFTER_BAD_SAMPLES, deviceBadSamples + 1);
            if (deviceBadSamples >= SHOW_AFTER_BAD_SAMPLES) deviceWarning = true;
        } else if (good) {
            deviceBadSamples = 0;
            deviceGoodSamples = Math.min(HIDE_AFTER_GOOD_SAMPLES, deviceGoodSamples + 1);
            if (deviceGoodSamples >= HIDE_AFTER_GOOD_SAMPLES) deviceWarning = false;
        } else {
            deviceBadSamples = 0;
            deviceGoodSamples = 0;
        }
    }

    private State state() {
        return new State(networkWarning, deviceWarning);
    }

    private static long unsignedDelta(long current, long previous) {
        return (current - previous) & 0xFFFFFFFFL;
    }
}
