package com.seedshiftradio.settings;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.seedshiftradio.domain.ProviderJobStatus;

public interface ProviderJobRepository extends JpaRepository<ProviderJobEntity, String> {

	List<ProviderJobEntity> findTop10ByStatusOrderByUpdatedAtDesc(ProviderJobStatus status);
}
