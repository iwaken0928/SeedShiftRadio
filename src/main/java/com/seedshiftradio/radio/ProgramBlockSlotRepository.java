package com.seedshiftradio.radio;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramBlockSlotRepository extends JpaRepository<ProgramBlockSlotEntity, String> {

	List<ProgramBlockSlotEntity> findByProgramBlockIdOrderBySequenceNoAsc(String programBlockId);
}
