package com.seedshiftradio.programming;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProgramRuleRepository extends JpaRepository<ProgramRuleEntity, String> {

	List<ProgramRuleEntity> findByPolicyIdOrderByPriorityDesc(String policyId);

	void deleteByPolicyId(String policyId);
}
