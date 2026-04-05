package com.seedshiftradio.settings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.springframework.stereotype.Component;

@Component
public class PlaceholderAudioFactory {

	private static final int SAMPLE_RATE = 8_000;

	public byte[] createSilentWav(int durationMs) {
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
			buffer.put((byte) 0x80);
		}
		return buffer.array();
	}
}
