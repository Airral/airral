package com.airral.repository;

import com.airral.domain.RoomMessage;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface RoomMessageRepository extends R2dbcRepository<RoomMessage, Long> {

    @Query("""
            SELECT *
            FROM room_messages
            WHERE room_id = :roomId
              AND deleted_at IS NULL
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    Flux<RoomMessage> findRecentByRoomId(
            @Param("roomId") Long roomId,
            @Param("limit") int limit);

    @Query("""
            SELECT COUNT(*)
            FROM room_messages
            WHERE room_id = :roomId
              AND sender_user_id = :senderUserId
              AND deleted_at IS NULL
              AND created_at >= :since
            """)
    Mono<Long> countRecentBySender(
            @Param("roomId") Long roomId,
            @Param("senderUserId") Long senderUserId,
            @Param("since") LocalDateTime since);
}
