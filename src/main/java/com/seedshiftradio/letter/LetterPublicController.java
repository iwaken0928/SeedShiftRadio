package com.seedshiftradio.letter;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicLookupRequest;
import com.seedshiftradio.letter.LetterPublicDtos.LetterPublicLookupResponse;

@Validated
@RestController
@RequestMapping("/api/letters/public")
public class LetterPublicController {

	private final LetterPublicService letterPublicService;

	public LetterPublicController(LetterPublicService letterPublicService) {
		this.letterPublicService = letterPublicService;
	}

	@PostMapping("/history")
	public LetterPublicLookupResponse history(@RequestBody LetterPublicLookupRequest request) {
		return letterPublicService.lookup(request);
	}
}
