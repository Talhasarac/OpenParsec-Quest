package com.example.parsecdemo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class PerformanceWarningTrackerTest {
    private PerformanceWarningTracker tracker;
    private long packets;
    private long retransmits;

    @Before
    public void setUp() {
        tracker = new PerformanceWarningTracker();
        packets = 1000;
        retransmits = 10;
        sample(false, 20f, 8f, 0, false);
    }

    @Test
    public void oneBadSampleDoesNotFlashWarnings() {
        PerformanceWarningTracker.State state =
                sample(false, 120f, 35f, 4, false);
        assertFalse(state.networkWarning);
        assertFalse(state.deviceWarning);
    }

    @Test
    public void sustainedLatencyShowsThenSustainedRecoveryHidesNetwork() {
        PerformanceWarningTracker.State state = null;
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            state = sample(false, 100f, 8f, 0, false);
        }
        assertTrue(state.networkWarning);

        // Hysteresis-band values must not make an active warning flicker.
        state = sample(false, 70f, 8f, 0, false);
        assertTrue(state.networkWarning);

        for (int i = 0; i < PerformanceWarningTracker.HIDE_AFTER_GOOD_SAMPLES; i++) {
            state = sample(false, 30f, 8f, 0, false);
        }
        assertFalse(state.networkWarning);
    }

    @Test
    public void retransmissionRatioTriggersNetworkWarningAcrossCounterWrap() {
        tracker.reset();
        packets = 0xFFFFFFD0L;
        retransmits = 0xFFFFFFF0L;
        sample(false, 20f, 8f, 0, false);

        PerformanceWarningTracker.State state = null;
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            packets = (packets + 100) & 0xFFFFFFFFL;
            retransmits = (retransmits + 5) & 0xFFFFFFFFL;
            state = tracker.update(true, false, 20f, 8f, 60, 0, false,
                    packets, retransmits);
        }
        assertTrue(state.networkWarning);
    }

    @Test
    public void queuedFramesTriggerDeviceWarningAndRecoveryClearsIt() {
        PerformanceWarningTracker.State state = null;
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            state = sample(false, 20f, 12f, 3, false);
        }
        assertTrue(state.deviceWarning);

        for (int i = 0; i < PerformanceWarningTracker.HIDE_AFTER_GOOD_SAMPLES; i++) {
            state = sample(false, 20f, 10f, 0, false);
        }
        assertFalse(state.deviceWarning);
    }

    @Test
    public void decoderFallbackTriggersDeviceWarning() {
        PerformanceWarningTracker.State state = null;
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            state = sample(false, 20f, 10f, 0, true);
        }
        assertTrue(state.deviceWarning);
    }

    @Test
    public void decodeThresholdScalesWithRequestedFrameRate() {
        PerformanceWarningTracker.State state = null;
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            packets += 100;
            state = tracker.update(true, false, 20f, 30f,
                    30, 0, false, packets, retransmits);
        }
        assertFalse(state.deviceWarning);

        tracker.reset();
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            packets += 100;
            state = tracker.update(true, false, 20f, 13f,
                    120, 0, false, packets, retransmits);
        }
        assertTrue(state.deviceWarning);
    }

    @Test
    public void inactiveStreamAndResetClearWarningsAndCounterHistory() {
        PerformanceWarningTracker.State state = null;
        for (int i = 0; i < PerformanceWarningTracker.SHOW_AFTER_BAD_SAMPLES; i++) {
            state = sample(true, 20f, 10f, 0, false);
        }
        assertTrue(state.networkWarning);

        state = tracker.update(
                false, false, 0f, 0f, 60, 0, false, 0, 0);
        assertFalse(state.networkWarning);
        assertFalse(state.deviceWarning);

        // A fresh baseline must not interpret the reconnect's reset counters
        // as a 32-bit wrap with massive packet loss.
        state = tracker.update(true, false, 20f, 10f,
                60, 0, false, 2, 0);
        assertFalse(state.networkWarning);
    }

    private PerformanceWarningTracker.State sample(
            boolean networkFailure,
            float networkLatencyMs,
            float decodeLatencyMs,
            long queuedFrames,
            boolean decoderFellBack) {
        packets = (packets + 100) & 0xFFFFFFFFL;
        return tracker.update(true, networkFailure, networkLatencyMs,
                decodeLatencyMs, 60, queuedFrames, decoderFellBack,
                packets, retransmits);
    }
}
