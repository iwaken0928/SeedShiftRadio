package com.seedshiftradio.monitor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.radio.HealthResponse;
import com.seedshiftradio.radio.RadioService;

@RestController
@RequestMapping("/api")
public class HealthController {

	private final RadioService radioService;

	public HealthController(RadioService radioService) {
		this.radioService = radioService;
	}

	@GetMapping("/health")
	public HealthResponse health() {
		return radioService.health();
	}
}
