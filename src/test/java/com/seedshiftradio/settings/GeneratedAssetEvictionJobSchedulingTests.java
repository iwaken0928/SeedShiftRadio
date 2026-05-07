package com.seedshiftradio.settings;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

class GeneratedAssetEvictionJobSchedulingTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(
					PropertyPlaceholderAutoConfiguration.class,
					TaskSchedulingAutoConfiguration.class))
			.withUserConfiguration(TestConfig.class, GeneratedAssetEvictionJob.class);

	@Test
	void contextLoadsWithoutExplicitCronProperty() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GeneratedAssetEvictionJob.class);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	static class TestConfig {

		@Bean
		GeneratedAssetService generatedAssetService() {
			return Mockito.mock(GeneratedAssetService.class);
		}
	}
}
