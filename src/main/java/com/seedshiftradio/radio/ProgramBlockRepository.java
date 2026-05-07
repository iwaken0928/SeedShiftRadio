package com.seedshiftradio.radio;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramBlockRepository extends JpaRepository<ProgramBlockEntity, String> {

	List<ProgramBlockEntity> findBySessionIdOrderByStartedAtAsc(String sessionId);

	Optional<ProgramBlockEntity> findTopBySessionIdOrderByStartedAtDesc(String sessionId);
}
