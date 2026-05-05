package com.seedshiftradio.programming;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramTemplateSlotRepository extends JpaRepository<ProgramTemplateSlotEntity, String> {

	List<ProgramTemplateSlotEntity> findByProgramTemplateIdOrderBySequenceNoAsc(String programTemplateId);

	void deleteByProgramTemplateId(String programTemplateId);
}
