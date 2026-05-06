package com.seedshiftradio.radio;

abstract class AbstractRadioQueueJob {

	protected final RadioService radioService;

	protected AbstractRadioQueueJob(RadioService radioService) {
		this.radioService = radioService;
	}
}
