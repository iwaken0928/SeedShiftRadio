package com.seedshiftradio.letter;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LetterReplyRepository extends JpaRepository<LetterReplyEntity, String> {

	List<LetterReplyEntity> findByLetterIdOrderByCreatedAtAsc(String letterId);
}
