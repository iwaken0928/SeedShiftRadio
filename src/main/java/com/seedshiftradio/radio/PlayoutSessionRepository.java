package com.seedshiftradio.radio;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayoutSessionRepository extends JpaRepository<PlayoutSessionEntity, String> {

	Optional<PlayoutSessionEntity> findFirstByOrderByStartedAtDesc();
}
