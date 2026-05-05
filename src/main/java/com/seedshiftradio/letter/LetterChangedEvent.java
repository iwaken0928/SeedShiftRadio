package com.seedshiftradio.letter;

import com.seedshiftradio.letter.LetterDtos.LetterSummaryResponse;

public record LetterChangedEvent(LetterSummaryResponse summary) {
}
