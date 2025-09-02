package com.wavestone.stacamback.repository;

import com.wavestone.stacamback.model.DetectionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DetectionResultRepository extends JpaRepository<DetectionResult, Long> {

    List<DetectionResult> findByStatusOrderByProcessedAtDesc(String status);

    List<DetectionResult> findByFileTypeOrderByProcessedAtDesc(String fileType);

    @Query("SELECT d FROM DetectionResult d WHERE d.processedAt >= :since ORDER BY d.processedAt DESC")
    List<DetectionResult> findRecentDetections(LocalDateTime since);

    List<DetectionResult> findTop10ByOrderByProcessedAtDesc();
}
