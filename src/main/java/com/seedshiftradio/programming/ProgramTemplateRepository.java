package com.seedshiftradio.programming;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProgramTemplateRepository extends JpaRepository<ProgramTemplateEntity, String> {

	List<ProgramTemplateEntity> findAllByOrderByNameAsc();

	@Query("""
			SELECT COUNT(template)
			FROM ProgramTemplateEntity template
			WHERE template.active = true
				AND (UPPER(template.scope) = 'GLOBAL' OR template.stationId = :stationId)
			""")
	long countActiveApplicableToStation(@Param("stationId") String stationId);
}
