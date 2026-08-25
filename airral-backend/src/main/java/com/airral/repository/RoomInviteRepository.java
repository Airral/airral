package com.airral.repository;

import com.airral.domain.RoomInvite;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface RoomInviteRepository extends R2dbcRepository<RoomInvite, Long> {

    Mono<RoomInvite> findByInviteToken(String inviteToken);
}
