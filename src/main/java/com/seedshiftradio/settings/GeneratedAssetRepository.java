package com.seedshiftradio.settings;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.seedshiftradio.domain.GeneratedAssetType;

public interface GeneratedAssetRepository extends JpaRepository<GeneratedAssetEntity, String> {

	java.util.Optional<GeneratedAssetEntity> findFirstByAssetTypeAndCacheKeyOrderByCreatedAtDesc(
			GeneratedAssetType assetType,
			String cacheKey);

	java.util.Optional<GeneratedAssetEntity> findFirstByAssetTypeAndQueueItemIdOrderByCreatedAtDesc(
			GeneratedAssetType assetType,
			String queueItemId);

	@Query("""
			SELECT asset
			FROM GeneratedAssetEntity asset
			WHERE asset.archiveEligible = false
				AND asset.expiresAt IS NOT NULL
				AND asset.expiresAt <= :now
				AND asset.byteSize > 0
			ORDER BY asset.expiresAt ASC, asset.lastAccessedAt ASC, asset.createdAt ASC
			""")
	List<GeneratedAssetEntity> findExpiredEvictionCandidates(@Param("now") Instant now, Pageable pageable);

	@Query("""
			SELECT asset
			FROM GeneratedAssetEntity asset
			WHERE asset.assetType = :assetType
				AND asset.archiveEligible = false
				AND asset.byteSize > 0
			ORDER BY asset.lastAccessedAt ASC, asset.reuseCount ASC, asset.createdAt ASC
			""")
	List<GeneratedAssetEntity> findCapacityEvictionCandidates(
			@Param("assetType") GeneratedAssetType assetType,
			Pageable pageable);

	@Query("""
			SELECT COUNT(asset)
			FROM GeneratedAssetEntity asset
			WHERE asset.archiveEligible = false
				AND asset.expiresAt IS NOT NULL
				AND asset.expiresAt <= :now
				AND asset.byteSize > 0
			""")
	long countExpiredEvictionCandidates(@Param("now") Instant now);

	@Query("""
			SELECT COUNT(asset)
			FROM GeneratedAssetEntity asset
			WHERE asset.storagePath = :storagePath
				AND asset.byteSize > 0
			""")
	long countActivePayloadReferences(@Param("storagePath") String storagePath);

	@Query("""
			SELECT asset.assetType AS assetType,
				COUNT(asset) AS assetCount,
				COALESCE(SUM(asset.byteSize), 0) AS byteSize,
				COALESCE(SUM(asset.reuseCount), 0) AS cacheHitCount
			FROM GeneratedAssetEntity asset
			GROUP BY asset.assetType
			""")
	List<AssetTypeStats> summarizeByAssetType();

	@Query(value = """
			SELECT ga.asset_type AS assetType,
				COUNT(ga.id) AS assetCount,
				COALESCE(SUM(ga.byte_size), 0) AS byteSize,
				MAX(ga.created_at) AS latestCreatedAt
			FROM generated_asset ga
			JOIN queue_item qi ON qi.id = ga.queue_item_id
			JOIN program_block pb ON pb.id = qi.program_block_id
			WHERE pb.station_id = :stationId
				AND ga.byte_size > 0
			GROUP BY ga.asset_type
			""", nativeQuery = true)
	List<StationAssetStats> summarizeByStationId(@Param("stationId") String stationId);

	interface AssetTypeStats {

		GeneratedAssetType getAssetType();

		long getAssetCount();

		long getByteSize();

		long getCacheHitCount();
	}

	interface StationAssetStats {

		String getAssetType();

		long getAssetCount();

		long getByteSize();

		Instant getLatestCreatedAt();
	}
}
