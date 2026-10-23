package dev.chatspellcheck.gametest;

import java.util.Arrays;

/** Render-thread timings of ChatSpellcheckOverlay.extract, collected by OverlayTimingMixin. */
public final class OverlayTiming {
	private static final com.sun.management.ThreadMXBean THREADS =
		(com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
	private static long started;
	private static long startedBytes;
	private static long totalBytes;
	private static long[] samples = new long[0];
	private static int count;
	private static boolean recording;

	private OverlayTiming() {
	}

	public static void start() {
		startedBytes = THREADS.getCurrentThreadAllocatedBytes();
		started = System.nanoTime();
	}

	public static void end() {
		if (recording && count < samples.length) {
			samples[count++] = System.nanoTime() - started;
			totalBytes += THREADS.getCurrentThreadAllocatedBytes() - startedBytes;
		}
	}

	public static void record(int frames) {
		samples = new long[frames];
		count = 0;
		totalBytes = 0;
		recording = true;
	}

	public static boolean done() {
		return count >= samples.length;
	}

	/** Median and 95th percentile in nanoseconds, and mean bytes allocated per frame. */
	public static long[] stop() {
		recording = false;
		long[] s = Arrays.copyOf(samples, count);
		Arrays.sort(s);
		return s.length == 0 ? new long[] {0, 0, 0} : new long[] {s[s.length / 2], s[(int) (s.length * 0.95)], totalBytes / s.length};
	}
}
