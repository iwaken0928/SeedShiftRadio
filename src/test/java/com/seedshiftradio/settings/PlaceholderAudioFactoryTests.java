package com.seedshiftradio.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaceholderAudioFactoryTests {

	@Test
	void fallbackWavContainsAudibleSamplesAndKeepsRequestedDuration() {
		byte[] wav = new PlaceholderAudioFactory().createFallbackWav(1_000);

		assertEquals("RIFF", new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
		assertEquals(8_044, wav.length);
		assertTrue(hasAudibleSample(wav), "fallback WAV must not be completely silent");
	}

	@Test
	void fallbackWavClampsTooShortDuration() {
		byte[] wav = new PlaceholderAudioFactory().createFallbackWav(1);

		assertEquals(2_044, wav.length);
		assertTrue(hasAudibleSample(wav));
	}

	private boolean hasAudibleSample(byte[] wav) {
		for (int index = 44; index < wav.length; index++) {
			if (Byte.toUnsignedInt(wav[index]) != 0x80) {
				return true;
			}
		}
		return false;
	}
}
