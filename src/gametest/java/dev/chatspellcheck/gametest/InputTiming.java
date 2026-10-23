package dev.chatspellcheck.gametest;

import java.util.Arrays;

/** Per-frame timings of one widget's draw (see InputTimingMixin). */
public final class InputTiming {
	public static final InputTiming TIMING = new InputTiming();
	public static volatile Object target;

	private long started;
	private long[] samples = new long[0];
	private int count;
	private boolean recording;

	public void start() {
		started = System.nanoTime();
	}

	public void end() {
		if (recording && count < samples.length) {
			samples[count++] = System.nanoTime() - started;
		}
	}

	public void record(int frames) {
		samples = new long[frames];
		count = 0;
		recording = true;
	}

	public boolean done() {
		return count >= samples.length;
	}

	/** Median and 95th percentile in nanoseconds. */
	public long[] stop() {
		recording = false;
		long[] s = Arrays.copyOf(samples, count);
		Arrays.sort(s);
		return s.length == 0 ? new long[] {0, 0} : new long[] {s[s.length / 2], s[(int) (s.length * 0.95)]};
	}
}
