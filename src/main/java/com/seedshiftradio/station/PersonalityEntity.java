package com.seedshiftradio.station;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "personality")
public class PersonalityEntity {

	@Id
	private String id;

	@Column(name = "station_id")
	private String stationId;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "language_tone", nullable = false)
	private String languageTone;

	@Column(name = "first_person", nullable = false)
	private String firstPerson;

	@Column(name = "sentence_style", nullable = false)
	private String sentenceStyle;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ng_policy", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> ngPolicy = new LinkedHashMap<>();

	@Column(name = "pronunciation_dictionary_ref")
	private String pronunciationDictionaryRef;
}
