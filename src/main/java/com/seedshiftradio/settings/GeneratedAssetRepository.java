package com.seedshiftradio.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import com.seedshiftradio.domain.GeneratedAssetType;

public interface GeneratedAssetRepository extends JpaRepository<GeneratedAssetEntity, String> {

	java.util.Optional<GeneratedAssetEntity> findFirstByAssetTypeAndCacheKeyOrderByCreatedAtDesc(
			GeneratedAssetType assetType,
			String cacheKey);

	java.util.Optional<GeneratedAssetEntity> findFirstByAssetTypeAndQueueItemIdOrderByCreatedAtDesc(
			GeneratedAssetType assetType,
			String queueItemId);
}
