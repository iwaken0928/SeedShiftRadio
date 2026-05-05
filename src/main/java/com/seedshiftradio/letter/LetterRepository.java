package com.seedshiftradio.letter;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.seedshiftradio.domain.LetterStatus;

public interface LetterRepository extends JpaRepository<LetterEntity, String> {

	long countByStationIdAndStatusIn(String stationId, Collection<LetterStatus> statuses);

	List<LetterEntity> findByStationIdOrderByCreatedAtDesc(String stationId);

	List<LetterEntity> findByAdoptedInSessionIdAndStatusOrderByCreatedAtAsc(String adoptedInSessionId, LetterStatus status);

	Optional<LetterEntity> findByIdempotencyKey(String idempotencyKey);
}
