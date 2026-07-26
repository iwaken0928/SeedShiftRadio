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
}
