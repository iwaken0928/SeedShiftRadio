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

	@Test
	void musicPlaceholderWavContainsContinuousMusicalMaterial() {
		byte[] wav = new PlaceholderAudioFactory().createMusicPlaceholderWav(5_000);

		assertEquals("RIFF", new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
		assertEquals(40_044, wav.length);
		assertTrue(audibleSampleRatio(wav) > 0.8d, "music placeholder must not be sparse cue tones");
		assertTrue(hasAudibleSamplesInRange(wav, 2_000, 3_000), "music must continue through the middle of the asset");
	}

	private boolean hasAudibleSample(byte[] wav) {
		for (int index = 44; index < wav.length; index++) {
			if (Byte.toUnsignedInt(wav[index]) != 0x80) {
				return true;
			}
		}
		return false;
	}

	private double audibleSampleRatio(byte[] wav) {
		int audible = 0;
		for (int index = 44; index < wav.length; index++) {
			if (Math.abs(Byte.toUnsignedInt(wav[index]) - 0x80) >= 2) {
				audible++;
			}
		}
		return (double) audible / (wav.length - 44);
	}

	private boolean hasAudibleSamplesInRange(byte[] wav, int fromMs, int toMs) {
		int from = 44 + fromMs * 8;
		int to = Math.min(wav.length, 44 + toMs * 8);
		for (int index = from; index < to; index++) {
			if (Math.abs(Byte.toUnsignedInt(wav[index]) - 0x80) >= 2) {
				return true;
			}
		}
		return false;
	}
}
