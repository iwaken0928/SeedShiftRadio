package com.seedshiftradio.radio;

import java.util.List;

public record GeneratedScript(
		String text,
		List<String> safetyFlags) {

	public GeneratedScript {
		safetyFlags = safetyFlags == null ? List.of() : List.copyOf(safetyFlags);
	}
}
