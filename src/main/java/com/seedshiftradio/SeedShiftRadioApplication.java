package com.seedshiftradio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SeedShiftRadioApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(SeedShiftRadioApplication.class, args);
		closeAfterMigrationIfRequested(context);
	}

	static void closeAfterMigrationIfRequested(ConfigurableApplicationContext context) {
		if (context.getEnvironment().getProperty("seedshift.radio.migration-only", Boolean.class, false)) {
			context.close();
		}
	}

}
