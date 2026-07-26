package com.seedshiftradio.settings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.springframework.stereotype.Component;

@Component
public class PlaceholderAudioFactory {

	private static final int SAMPLE_RATE = 8_000;
	private static final int NEUTRAL_SAMPLE = 0x80;
	private static final int CUE_AMPLITUDE = 28;
	private static final int CUE_INTERVAL_MS = 4_000;
	private static final int CUE_DURATION_MS = 650;
	private static final double MUSIC_BPM = 96.0d;
	private static final int[] CHORD_ROOT_MIDI = {48, 45, 41, 43};
	private static final boolean[] MINOR_CHORD = {false, true, false, false};
	private static final int[] MELODY_STEPS = {12, 16, 19, 16, 14, 12, 9, 11};

	public byte[] createFallbackWav(int durationMs) {
		int safeDurationMs = Math.max(250, durationMs);
		int dataSize = Math.max(1, SAMPLE_RATE * safeDurationMs / 1_000);
		ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.putInt(36 + dataSize);
		buffer.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.putInt(16);
		buffer.putShort((short) 1);
		buffer.putShort((short) 1);
		buffer.putInt(SAMPLE_RATE);
		buffer.putInt(SAMPLE_RATE);
		buffer.putShort((short) 1);
		buffer.putShort((short) 8);
		buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.putInt(dataSize);
		for (int i = 0; i < dataSize; i++) {
			int elapsedMs = i * 1_000 / SAMPLE_RATE;
			int cueElapsedMs = elapsedMs % CUE_INTERVAL_MS;
			if (cueElapsedMs < CUE_DURATION_MS) {
				double frequency = cueElapsedMs < CUE_DURATION_MS / 2 ? 440.0d : 554.37d;
				double envelope = Math.sin(Math.PI * cueElapsedMs / CUE_DURATION_MS);
				double wave = Math.sin(2.0d * Math.PI * frequency * i / SAMPLE_RATE);
				buffer.put((byte) Math.round(NEUTRAL_SAMPLE + CUE_AMPLITUDE * envelope * wave));
			} else {
				buffer.put((byte) NEUTRAL_SAMPLE);
			}
		}
		return buffer.array();
	}

	public byte[] createMusicPlaceholderWav(int durationMs) {
		int safeDurationMs = Math.max(1_000, durationMs);
		int dataSize = Math.max(1, SAMPLE_RATE * safeDurationMs / 1_000);
		ByteBuffer buffer = createWavBuffer(dataSize);
		double beatDurationSec = 60.0d / MUSIC_BPM;
		double eighthDurationSec = beatDurationSec / 2.0d;
		for (int i = 0; i < dataSize; i++) {
			double timeSec = (double) i / SAMPLE_RATE;
			double beatPosition = timeSec / beatDurationSec;
			int beatIndex = (int) Math.floor(beatPosition);
			int barIndex = Math.floorMod(beatIndex / 4, CHORD_ROOT_MIDI.length);
			int rootMidi = CHORD_ROOT_MIDI[barIndex];
			int thirdInterval = MINOR_CHORD[barIndex] ? 3 : 4;

			double barEnvelope = smoothPulse(beatPosition % 4.0d, 4.0d, 0.08d);
			double pad = (
					sine(midiFrequency(rootMidi), timeSec)
					+ 0.8d * sine(midiFrequency(rootMidi + thirdInterval), timeSec)
					+ 0.65d * sine(midiFrequency(rootMidi + 7), timeSec))
					/ 2.45d
					* (0.55d + 0.45d * barEnvelope);

			double beatPhase = beatPosition - Math.floor(beatPosition);
			double bassEnvelope = Math.exp(-4.0d * beatPhase);
			double bass = sine(midiFrequency(rootMidi - 12), timeSec) * bassEnvelope;

			double eighthPosition = timeSec / eighthDurationSec;
			int melodyIndex = Math.floorMod((int) Math.floor(eighthPosition), MELODY_STEPS.length);
			double melodyPhase = eighthPosition - Math.floor(eighthPosition);
			double melodyEnvelope = Math.exp(-5.5d * melodyPhase);
			double melody = triangle(midiFrequency(rootMidi + MELODY_STEPS[melodyIndex]), timeSec) * melodyEnvelope;

			double kick = Math.sin(2.0d * Math.PI * (64.0d - 22.0d * beatPhase) * beatPhase)
					* Math.exp(-11.0d * beatPhase);
			double hatPhase = eighthPosition - Math.floor(eighthPosition);
			double hat = deterministicNoise(i) * Math.exp(-30.0d * hatPhase);
			double snare = beatIndex % 4 == 1 || beatIndex % 4 == 3
					? deterministicNoise(i * 31 + 17) * Math.exp(-16.0d * beatPhase)
					: 0.0d;

			double fadeIn = Math.min(1.0d, timeSec / 0.8d);
			double remainingSec = safeDurationMs / 1_000.0d - timeSec;
			double fadeOut = Math.min(1.0d, remainingSec / 1.2d);
			double mix = 0.34d * pad
					+ 0.22d * bass
					+ 0.24d * melody
					+ 0.14d * kick
					+ 0.035d * hat
					+ 0.045d * snare;
			double mastered = Math.tanh(mix * 1.35d) * fadeIn * fadeOut;
			buffer.put((byte) Math.round(NEUTRAL_SAMPLE + 52.0d * mastered));
		}
		return buffer.array();
	}

	private ByteBuffer createWavBuffer(int dataSize) {
		ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.putInt(36 + dataSize);
		buffer.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.putInt(16);
		buffer.putShort((short) 1);
		buffer.putShort((short) 1);
		buffer.putInt(SAMPLE_RATE);
		buffer.putInt(SAMPLE_RATE);
		buffer.putShort((short) 1);
		buffer.putShort((short) 8);
		buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		buffer.putInt(dataSize);
		return buffer;
	}

	private double midiFrequency(int midiNote) {
		return 440.0d * Math.pow(2.0d, (midiNote - 69) / 12.0d);
	}

	private double sine(double frequency, double timeSec) {
		return Math.sin(2.0d * Math.PI * frequency * timeSec);
	}

	private double triangle(double frequency, double timeSec) {
		return 2.0d * Math.asin(sine(frequency, timeSec)) / Math.PI;
	}

	private double smoothPulse(double position, double length, double edge) {
		double attack = Math.min(1.0d, position / edge);
		double release = Math.min(1.0d, (length - position) / edge);
		return Math.max(0.0d, Math.min(attack, release));
	}

	private double deterministicNoise(int sampleIndex) {
		int value = sampleIndex;
		value ^= value << 13;
		value ^= value >>> 17;
		value ^= value << 5;
		return ((value & 0xffff) / 32767.5d) - 1.0d;
	}
}
