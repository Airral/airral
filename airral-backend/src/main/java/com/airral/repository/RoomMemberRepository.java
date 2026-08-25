package com.airral.repository;

import com.airral.domain.RoomMember;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface RoomMemberRepository extends R2dbcRepository<RoomMember, Long> {

    Mono<RoomMember> findByRoomIdAndUserId(Long roomId, Long userId);

    Mono<Boolean> existsByRoomIdAndUserId(Long roomId, Long userId);

    Flux<RoomMember> findByRoomId(Long roomId);

    @Query("SELECT COUNT(*) FROM room_members WHERE room_id = :roomId")
    Mono<Long> countByRoomId(Long roomId);
}
