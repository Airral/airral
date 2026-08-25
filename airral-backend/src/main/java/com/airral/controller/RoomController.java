package com.airral.controller;

import com.airral.dto.request.CreateDirectRoomRequest;
import com.airral.dto.request.CreateRoomRequest;
import com.airral.dto.request.SendRoomMessageRequest;
import com.airral.dto.response.RoomInviteResponse;
import com.airral.dto.response.RoomMessageResponse;
import com.airral.dto.response.RoomResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.RoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@PreAuthorize("isAuthenticated()")
public class RoomController {

    private final RoomService roomService;
    private final JwtTokenProvider jwtTokenProvider;

    public RoomController(RoomService roomService, JwtTokenProvider jwtTokenProvider) {
        this.roomService = roomService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping
    public Mono<ResponseEntity<List<RoomResponse>>> listRooms(
            @RequestParam(required = false) String roomType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = userId(authHeader);
        return roomService.listRooms(userId, roomType, targetType, targetId, limit)
                .collectList()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{roomId}")
    public Mono<ResponseEntity<RoomResponse>> getRoom(
            @PathVariable Long roomId,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.getRoom(userId(authHeader), roomId)
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<RoomResponse>> createRoom(
            @RequestBody CreateRoomRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.createRoom(userId(authHeader), request)
                .map(room -> ResponseEntity.status(HttpStatus.CREATED).body(room));
    }

    @PostMapping("/direct")
    public Mono<ResponseEntity<RoomResponse>> createDirectRoom(
            @RequestBody CreateDirectRoomRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.createDirectRoom(userId(authHeader), request)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{roomId}/join")
    public Mono<ResponseEntity<RoomResponse>> joinRoom(
            @PathVariable Long roomId,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.joinRoom(userId(authHeader), roomId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/join/{inviteToken}")
    public Mono<ResponseEntity<RoomResponse>> joinByInvite(
            @PathVariable String inviteToken,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.joinByInvite(userId(authHeader), inviteToken)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{roomId}/messages")
    public Mono<ResponseEntity<List<RoomMessageResponse>>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = userId(authHeader);
        return roomService.getMessages(userId, roomId, limit)
                .collectList()
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{roomId}/messages")
    public Mono<ResponseEntity<RoomMessageResponse>> sendMessage(
            @PathVariable Long roomId,
            @RequestBody SendRoomMessageRequest request,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.sendMessage(userId(authHeader), roomId, request)
                .map(message -> ResponseEntity.status(HttpStatus.CREATED).body(message));
    }

    @PostMapping("/{roomId}/invites")
    public Mono<ResponseEntity<RoomInviteResponse>> createInvite(
            @PathVariable Long roomId,
            @RequestHeader("Authorization") String authHeader) {

        return roomService.createInvite(userId(authHeader), roomId)
                .map(invite -> ResponseEntity.status(HttpStatus.CREATED).body(invite));
    }

    private Long userId(String authHeader) {
        return jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
