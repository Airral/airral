package com.airral.repository;

import com.airral.domain.Room;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface RoomRepository extends R2dbcRepository<Room, Long> {

    @Query("""
            SELECT DISTINCT r.*
            FROM rooms r
            LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = :userId
            WHERE r.is_active = true
              AND (m.id IS NOT NULL OR r.visibility IN ('PUBLIC', 'AUTHENTICATED'))
            ORDER BY r.last_message_at DESC NULLS LAST, r.updated_at DESC
            LIMIT :limit
            """)
    Flux<Room> findVisibleRooms(
            @Param("userId") Long userId,
            @Param("limit") int limit);

    @Query("""
            SELECT DISTINCT r.*
            FROM rooms r
            LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = :userId
            WHERE r.is_active = true
              AND (m.id IS NOT NULL OR r.visibility IN ('PUBLIC', 'AUTHENTICATED'))
              AND r.room_type = :roomType
            ORDER BY r.last_message_at DESC NULLS LAST, r.updated_at DESC
            LIMIT :limit
            """)
    Flux<Room> findVisibleRoomsByRoomType(
            @Param("userId") Long userId,
            @Param("roomType") String roomType,
            @Param("limit") int limit);

    @Query("""
            SELECT DISTINCT r.*
            FROM rooms r
            LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = :userId
            WHERE r.is_active = true
              AND (m.id IS NOT NULL OR r.visibility IN ('PUBLIC', 'AUTHENTICATED'))
              AND r.target_type = :targetType
              AND r.target_id = :targetId
            ORDER BY r.last_message_at DESC NULLS LAST, r.updated_at DESC
            LIMIT :limit
            """)
    Flux<Room> findVisibleRoomsByTarget(
            @Param("userId") Long userId,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("limit") int limit);

    @Query("""
            SELECT DISTINCT r.*
            FROM rooms r
            LEFT JOIN room_members m ON m.room_id = r.id AND m.user_id = :userId
            WHERE r.is_active = true
              AND (m.id IS NOT NULL OR r.visibility IN ('PUBLIC', 'AUTHENTICATED'))
              AND r.room_type = :roomType
              AND r.target_type = :targetType
              AND r.target_id = :targetId
            ORDER BY r.last_message_at DESC NULLS LAST, r.updated_at DESC
            LIMIT :limit
            """)
    Flux<Room> findVisibleRoomsByRoomTypeAndTarget(
            @Param("userId") Long userId,
            @Param("roomType") String roomType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("limit") int limit);

    @Query("""
            SELECT *
            FROM rooms
            WHERE room_type = :roomType
              AND target_type = :targetType
              AND target_id = :targetId
              AND is_active = true
            ORDER BY created_at ASC
            LIMIT 1
            """)
    Mono<Room> findActiveRoomByTarget(
            @Param("roomType") String roomType,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId);

    @Query("""
            SELECT r.*
            FROM rooms r
            JOIN room_members first_member ON first_member.room_id = r.id AND first_member.user_id = :userId
            JOIN room_members second_member ON second_member.room_id = r.id AND second_member.user_id = :recipientUserId
            WHERE r.room_type = 'DIRECT'
              AND r.is_active = true
            ORDER BY r.created_at ASC
            LIMIT 1
            """)
    Mono<Room> findDirectRoom(
            @Param("userId") Long userId,
            @Param("recipientUserId") Long recipientUserId);
}
