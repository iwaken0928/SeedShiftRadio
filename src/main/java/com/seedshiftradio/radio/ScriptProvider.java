package com.seedshiftradio.radio;

import com.seedshiftradio.settings.ProviderRegistry;

public interface ScriptProvider {

	GeneratedScript generate(ProviderRegistry.ResolvedProvider provider, ScriptGenerationContext context);
}
