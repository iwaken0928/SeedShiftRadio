package com.seedshiftradio.programming;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramTemplateRepository extends JpaRepository<ProgramTemplateEntity, String> {

	List<ProgramTemplateEntity> findAllByOrderByNameAsc();
}
