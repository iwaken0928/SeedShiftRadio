package com.seedshiftradio.radio;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramBlockRepository extends JpaRepository<ProgramBlockEntity, String> {

	Optional<ProgramBlockEntity> findTopBySessionIdOrderByStartedAtDesc(String sessionId);
}
