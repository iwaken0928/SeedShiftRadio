package com.seedshiftradio.letter;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "letter_reply")
public class LetterReplyEntity {

	@Id
	private String id;

	@Column(name = "letter_id", nullable = false)
	private String letterId;

	@Column(name = "reply_text", nullable = false, columnDefinition = "text")
	private String replyText;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public LetterReplyEntity(String id, String letterId, String replyText) {
		this.id = id;
		this.letterId = letterId;
		this.replyText = replyText;
	}

	@PrePersist
	void onCreate() {
		createdAt = Instant.now();
	}
}
