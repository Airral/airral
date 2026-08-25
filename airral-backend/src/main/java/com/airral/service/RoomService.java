package com.airral.service;

import com.airral.domain.Room;
import com.airral.domain.RoomInvite;
import com.airral.domain.RoomMember;
import com.airral.domain.RoomMessage;
import com.airral.domain.User;
import com.airral.dto.request.CreateDirectRoomRequest;
import com.airral.dto.request.CreateRoomRequest;
import com.airral.dto.request.SendRoomMessageRequest;
import com.airral.dto.response.RoomInviteResponse;
import com.airral.dto.response.RoomMessageResponse;
import com.airral.dto.response.RoomResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.RoomInviteRepository;
import com.airral.repository.RoomMemberRepository;
import com.airral.repository.RoomMessageRepository;
import com.airral.repository.RoomRepository;
import com.airral.repository.UserRepository;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RoomService {

    private static final int DEFAULT_ROOM_LIMIT = 30;
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final int MESSAGE_RATE_LIMIT = 20;
    private static final Set<String> ROOM_TYPES = Set.of("DIRECT", "JOB", "COMPANY", "FOUNDER", "EVENT", "FEED", "GENERAL");
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "AUTHENTICATED", "PUBLIC");

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomMessageRepository roomMessageRepository;
    private final RoomInviteRepository roomInviteRepository;
    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public RoomService(
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            RoomMessageRepository roomMessageRepository,
            RoomInviteRepository roomInviteRepository,
            UserRepository userRepository) {
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.roomMessageRepository = roomMessageRepository;
        this.roomInviteRepository = roomInviteRepository;
        this.userRepository = userRepository;
    }

    public Flux<RoomResponse> listRooms(Long userId, String roomType, String targetType, String targetId, Integer limit) {
        int resolvedLimit = normalizeLimit(limit, DEFAULT_ROOM_LIMIT, 100);
        String normalizedRoomType = normalizeNullable(roomType, ROOM_TYPES, "roomType");
        String normalizedTargetType = normalizeNullable(targetType, null, "targetType");
        String normalizedTargetId = trimToNull(targetId);

        if ((normalizedTargetType == null) != (normalizedTargetId == null)) {
            throw new BadRequestException("targetType and targetId must be used together");
        }

        Flux<Room> rooms;
        if (normalizedRoomType != null && normalizedTargetType != null) {
            rooms = roomRepository.findVisibleRoomsByRoomTypeAndTarget(userId, normalizedRoomType, normalizedTargetType, normalizedTargetId, resolvedLimit);
        } else if (normalizedRoomType != null) {
            rooms = roomRepository.findVisibleRoomsByRoomType(userId, normalizedRoomType, resolvedLimit);
        } else if (normalizedTargetType != null) {
            rooms = roomRepository.findVisibleRoomsByTarget(userId, normalizedTargetType, normalizedTargetId, resolvedLimit);
        } else {
            rooms = roomRepository.findVisibleRooms(userId, resolvedLimit);
        }

        return rooms
                .flatMap(room -> toResponse(room, userId, false));
    }

    public Mono<RoomResponse> getRoom(Long userId, Long roomId) {
        return roomRepository.findById(roomId)
                .filter(room -> Boolean.TRUE.equals(room.getIsActive()))
                .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                .flatMap(room -> canView(room, userId)
                        .flatMap(canView -> canView
                                ? toResponse(room, userId, true)
                                : Mono.error(new NotFoundException("Room not found"))));
    }

    public Mono<RoomResponse> createRoom(Long userId, CreateRoomRequest request) {
        validateRoomRequest(request);
        LocalDateTime now = LocalDateTime.now();
        String roomType = normalizeRoomType(request.getRoomType());
        String visibility = normalizeVisibility(request.getVisibility(), defaultVisibility(roomType));
        String targetType = normalizeNullable(request.getTargetType(), null, "targetType");
        String targetId = trimToNull(request.getTargetId());

        if ((targetType == null) != (targetId == null)) {
            return Mono.error(new BadRequestException("targetType and targetId must be used together"));
        }

        Room room = Room.builder()
                .roomType(roomType)
                .visibility(visibility)
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .topic(trimToNull(request.getTopic()))
                .targetType(targetType)
                .targetId(targetId)
                .targetLabel(trimToNull(request.getTargetLabel()))
                .createdByUserId(userId)
                .isActive(true)
                .memberCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return findExistingTargetRoom(roomType, targetType, targetId)
                .flatMap(existingRoom -> joinVisibleRoom(existingRoom, userId)
                        .flatMap(updatedRoom -> toResponse(updatedRoom, userId, true)))
                .switchIfEmpty(Mono.defer(() -> roomRepository.save(room)
                        .flatMap(savedRoom -> addMember(savedRoom, userId, "OWNER")
                                .then(updateMemberCount(savedRoom))
                                .flatMap(updatedRoom -> maybeCreateInitialMessage(updatedRoom, userId, request.getInitialMessage()))
                                .flatMap(updatedRoom -> toResponse(updatedRoom, userId, true)))
                        .onErrorResume(DuplicateKeyException.class, error -> findExistingTargetRoom(roomType, targetType, targetId)
                                .flatMap(existingRoom -> joinVisibleRoom(existingRoom, userId))
                                .flatMap(existingRoom -> toResponse(existingRoom, userId, true)))));
    }

    public Mono<RoomResponse> createDirectRoom(Long userId, CreateDirectRoomRequest request) {
        if (request == null || request.getRecipientUserId() == null || userId.equals(request.getRecipientUserId())) {
            return Mono.error(new BadRequestException("Recipient user is required"));
        }

        return userRepository.findById(request.getRecipientUserId())
                .filter(User::isActive)
                .switchIfEmpty(Mono.error(new NotFoundException("Recipient not found")))
                .flatMap(recipient -> roomRepository.findDirectRoom(userId, recipient.getId())
                        .flatMap(room -> toResponse(room, userId, true))
                        .switchIfEmpty(createDirectRoomEntity(userId, recipient)
                                .onErrorResume(DuplicateKeyException.class, error -> roomRepository.findDirectRoom(userId, recipient.getId()))
                                .flatMap(room -> maybeCreateInitialMessage(room, userId, request.getInitialMessage()))
                                .flatMap(room -> toResponse(room, userId, true))));
    }

    public Mono<RoomResponse> joinRoom(Long userId, Long roomId) {
        return roomRepository.findById(roomId)
                .filter(room -> Boolean.TRUE.equals(room.getIsActive()))
                .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                .flatMap(room -> joinVisibleRoom(room, userId)
                        .flatMap(updatedRoom -> toResponse(updatedRoom, userId, true)));
    }

    public Mono<RoomResponse> joinByInvite(Long userId, String inviteToken) {
        if (inviteToken == null || inviteToken.isBlank()) {
            return Mono.error(new BadRequestException("Invite token is required"));
        }

        return roomInviteRepository.findByInviteToken(inviteToken.trim())
                .filter(this::isInviteUsable)
                .switchIfEmpty(Mono.error(new NotFoundException("Room invite not found")))
                .flatMap(invite -> roomRepository.findById(invite.getRoomId())
                        .filter(room -> Boolean.TRUE.equals(room.getIsActive()))
                        .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                        .flatMap(room -> addMember(room, userId, "MEMBER")
                                .then(incrementInviteUse(invite))
                                .then(updateMemberCount(room))
                                .flatMap(updatedRoom -> toResponse(updatedRoom, userId, true))));
    }

    public Flux<RoomMessageResponse> getMessages(Long userId, Long roomId, Integer limit) {
        int resolvedLimit = normalizeLimit(limit, DEFAULT_MESSAGE_LIMIT, 100);
        return roomRepository.findById(roomId)
                .filter(room -> Boolean.TRUE.equals(room.getIsActive()))
                .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                .flatMap(room -> canView(room, userId)
                        .flatMap(canView -> canView
                                ? markRead(room.getId(), userId).thenReturn(room)
                                : Mono.error(new NotFoundException("Room not found"))))
                .flatMapMany(room -> getRecentMessages(userId, room.getId(), resolvedLimit));
    }

    public Mono<RoomMessageResponse> sendMessage(Long userId, Long roomId, SendRoomMessageRequest request) {
        String body = validateMessageBody(request == null ? null : request.getBody());
        return roomRepository.findById(roomId)
                .filter(room -> Boolean.TRUE.equals(room.getIsActive()))
                .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                .flatMap(room -> ensureWritable(room, userId)
                        .flatMap(writableRoom -> ensureMessageRateLimit(writableRoom.getId(), userId).thenReturn(writableRoom))
                        .flatMap(writableRoom -> {
                            LocalDateTime now = LocalDateTime.now();
                            RoomMessage message = RoomMessage.builder()
                                    .roomId(writableRoom.getId())
                                    .senderUserId(userId)
                                    .messageType("TEXT")
                                    .body(body)
                                    .attachments(Json.of("[]"))
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build();

                            writableRoom.setLastMessageAt(now);
                            writableRoom.setUpdatedAt(now);
                            return roomMessageRepository.save(message)
                                    .zipWith(roomRepository.save(writableRoom))
                                    .map(tuple -> tuple.getT1());
                        }))
                .flatMap(message -> toMessageResponse(message, userId));
    }

    public Mono<RoomInviteResponse> createInvite(Long userId, Long roomId) {
        return roomRepository.findById(roomId)
                .filter(room -> Boolean.TRUE.equals(room.getIsActive()))
                .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                .flatMap(room -> requireInvitePermission(room, userId)
                        .flatMap(member -> {
                            LocalDateTime now = LocalDateTime.now();
                            RoomInvite invite = RoomInvite.builder()
                                    .roomId(room.getId())
                                    .inviteToken(generateInviteToken())
                                    .createdByUserId(userId)
                                    .maxUses(defaultInviteMaxUses(room))
                                    .usesCount(0)
                                    .expiresAt(now.plusDays(7))
                                    .createdAt(now)
                                    .build();
                            return roomInviteRepository.save(invite);
                        }))
                .map(invite -> RoomInviteResponse.builder()
                        .roomId(invite.getRoomId())
                        .inviteToken(invite.getInviteToken())
                        .expiresAt(invite.getExpiresAt())
                        .maxUses(invite.getMaxUses())
                        .usesCount(invite.getUsesCount())
                        .build());
    }

    private Mono<Room> createDirectRoomEntity(Long userId, User recipient) {
        LocalDateTime now = LocalDateTime.now();
        Room room = Room.builder()
                .roomType("DIRECT")
                .visibility("PRIVATE")
                .name("Direct message")
                .targetType("USER_PAIR")
                .targetId(directRoomTargetId(userId, recipient.getId()))
                .targetLabel("Direct message")
                .createdByUserId(userId)
                .isActive(true)
                .memberCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return roomRepository.save(room)
                .flatMap(savedRoom -> addMember(savedRoom, userId, "OWNER")
                        .then(addMember(savedRoom, recipient.getId(), "MEMBER"))
                        .then(updateMemberCount(savedRoom)));
    }

    private Mono<Room> findExistingTargetRoom(String roomType, String targetType, String targetId) {
        if (targetType == null || targetId == null) {
            return Mono.empty();
        }
        return roomRepository.findActiveRoomByTarget(roomType, targetType, targetId);
    }

    private Mono<Room> maybeCreateInitialMessage(Room room, Long userId, String body) {
        if (body == null || body.isBlank()) {
            return Mono.just(room);
        }

        return sendMessage(userId, room.getId(), new SendRoomMessageRequest(body))
                .then(roomRepository.findById(room.getId()))
                .defaultIfEmpty(room);
    }

    private Mono<Room> joinVisibleRoom(Room room, Long userId) {
        return roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                .flatMap(existing -> Mono.just(room))
                .switchIfEmpty(Mono.defer(() -> {
                    if ("PRIVATE".equalsIgnoreCase(room.getVisibility())) {
                        return Mono.error(new BadRequestException("This room requires an invite"));
                    }
                    return addMember(room, userId, "MEMBER")
                            .then(updateMemberCount(room));
                }));
    }

    private Mono<Room> ensureWritable(Room room, Long userId) {
        return roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                .map(member -> room)
                .switchIfEmpty(Mono.defer(() -> {
                    if ("PRIVATE".equalsIgnoreCase(room.getVisibility())) {
                        return Mono.error(new NotFoundException("Room not found"));
                    }
                    return addMember(room, userId, "MEMBER")
                            .then(updateMemberCount(room));
                }));
    }

    private Mono<Boolean> canView(Room room, Long userId) {
        if (!"PRIVATE".equalsIgnoreCase(room.getVisibility())) {
            return Mono.just(true);
        }

        return roomMemberRepository.existsByRoomIdAndUserId(room.getId(), userId);
    }

    private Mono<Void> markRead(Long roomId, Long userId) {
        return roomMemberRepository.findByRoomIdAndUserId(roomId, userId)
                .flatMap(member -> {
                    member.setLastReadAt(LocalDateTime.now());
                    return roomMemberRepository.save(member).then();
                })
                .then();
    }

    private Flux<RoomMessageResponse> getRecentMessages(Long userId, Long roomId, int limit) {
        return roomMessageRepository.findRecentByRoomId(roomId, limit)
                .collectList()
                .flatMapMany(messages -> {
                    Collections.reverse(messages);
                    return Flux.fromIterable(messages);
                })
                .flatMap(message -> toMessageResponse(message, userId));
    }

    private Mono<Void> ensureMessageRateLimit(Long roomId, Long userId) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        return roomMessageRepository.countRecentBySender(roomId, userId, since)
                .flatMap(count -> count >= MESSAGE_RATE_LIMIT
                        ? Mono.<Void>error(new BadRequestException("You are sending messages too quickly"))
                        : Mono.empty());
    }

    private Mono<RoomMember> addMember(Room room, Long userId, String role) {
        return roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                .switchIfEmpty(roomMemberRepository.save(RoomMember.builder()
                        .roomId(room.getId())
                        .userId(userId)
                        .memberRole(role)
                        .muted(false)
                        .joinedAt(LocalDateTime.now())
                        .build()));
    }

    private Mono<Room> updateMemberCount(Room room) {
        return roomMemberRepository.countByRoomId(room.getId())
                .flatMap(count -> {
                    room.setMemberCount(Math.toIntExact(count));
                    room.setUpdatedAt(LocalDateTime.now());
                    return roomRepository.save(room);
                });
    }

    private Mono<RoomInvite> incrementInviteUse(RoomInvite invite) {
        invite.setUsesCount(invite.getUsesCount() == null ? 1 : invite.getUsesCount() + 1);
        return roomInviteRepository.save(invite);
    }

    private boolean isInviteUsable(RoomInvite invite) {
        if (invite.getRevokedAt() != null) {
            return false;
        }
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        return invite.getMaxUses() == null || invite.getUsesCount() == null || invite.getUsesCount() < invite.getMaxUses();
    }

    private Mono<RoomMember> requireInvitePermission(Room room, Long userId) {
        return roomMemberRepository.findByRoomIdAndUserId(room.getId(), userId)
                .switchIfEmpty(Mono.error(new NotFoundException("Room not found")))
                .flatMap(member -> isRoomManager(member)
                        ? Mono.just(member)
                        : Mono.<RoomMember>error(new BadRequestException("Only room owners and moderators can create invites")));
    }

    private boolean isRoomManager(RoomMember member) {
        return member != null
                && ("OWNER".equalsIgnoreCase(member.getMemberRole()) || "MODERATOR".equalsIgnoreCase(member.getMemberRole()));
    }

    private int defaultInviteMaxUses(Room room) {
        return "PRIVATE".equalsIgnoreCase(room.getVisibility()) ? 25 : 100;
    }

    private Mono<RoomResponse> toResponse(Room room, Long viewerUserId, boolean includeMessages) {
        Mono<RoomMember> membership = roomMemberRepository.findByRoomIdAndUserId(room.getId(), viewerUserId).defaultIfEmpty(new RoomMember());
        Mono<String> creator = room.getCreatedByUserId() == null
                ? Mono.just("AIRRAL member")
                : userRepository.findById(room.getCreatedByUserId())
                        .map(this::displayName)
                        .defaultIfEmpty("AIRRAL member");

        Mono<List<RoomMessageResponse>> messages = includeMessages
                ? getRecentMessages(viewerUserId, room.getId(), 20).collectList()
                : Mono.just(List.of());
        Mono<String> displayLabel = displayLabel(room, viewerUserId);

        return Mono.zip(membership, creator, messages, displayLabel)
                .map(tuple -> {
                    RoomMember member = tuple.getT1();
                    boolean isMember = member.getId() != null;
                    String roomDisplayLabel = emptyToNull(tuple.getT4());
                    String roomName = "DIRECT".equalsIgnoreCase(room.getRoomType()) && roomDisplayLabel != null
                            ? roomDisplayLabel
                            : room.getName();
                    return RoomResponse.builder()
                            .id(room.getId())
                            .roomType(room.getRoomType())
                            .visibility(room.getVisibility())
                            .name(roomName)
                            .description(room.getDescription())
                            .topic(room.getTopic())
                            .targetType(room.getTargetType())
                            .targetId(room.getTargetId())
                            .targetLabel(roomDisplayLabel != null ? roomDisplayLabel : room.getTargetLabel())
                            .createdByDisplayName(tuple.getT2())
                            .member(isMember)
                            .memberRole(isMember ? member.getMemberRole() : null)
                            .memberCount(room.getMemberCount())
                            .lastMessageAt(room.getLastMessageAt())
                            .createdAt(room.getCreatedAt())
                            .updatedAt(room.getUpdatedAt())
                            .recentMessages(tuple.getT3())
                            .build();
                });
    }

    private Mono<String> displayLabel(Room room, Long viewerUserId) {
        if (!"DIRECT".equalsIgnoreCase(room.getRoomType())) {
            return Mono.just(room.getTargetLabel() == null ? "" : room.getTargetLabel());
        }

        return roomMemberRepository.findByRoomId(room.getId())
                .filter(member -> !viewerUserId.equals(member.getUserId()))
                .next()
                .flatMap(member -> userRepository.findById(member.getUserId()))
                .map(this::displayName)
                .defaultIfEmpty("AIRRAL member");
    }

    private Mono<RoomMessageResponse> toMessageResponse(RoomMessage message, Long viewerUserId) {
        Mono<User> sender = message.getSenderUserId() == null
                ? Mono.empty()
                : userRepository.findById(message.getSenderUserId());

        return sender
                .map(user -> toMessageResponse(message, user, viewerUserId))
                .defaultIfEmpty(RoomMessageResponse.builder()
                        .id(message.getId())
                        .roomId(message.getRoomId())
                        .messageType(message.getMessageType())
                        .body(message.getBody())
                        .senderDisplayName("AIRRAL")
                        .senderInitials("AR")
                        .ownMessage(false)
                        .createdAt(message.getCreatedAt())
                        .updatedAt(message.getUpdatedAt())
                        .build());
    }

    private RoomMessageResponse toMessageResponse(RoomMessage message, User sender, Long viewerUserId) {
        return RoomMessageResponse.builder()
                .id(message.getId())
                .roomId(message.getRoomId())
                .messageType(message.getMessageType())
                .body(message.getBody())
                .senderDisplayName(displayName(sender))
                .senderInitials(initials(sender))
                .ownMessage(sender.getId().equals(viewerUserId))
                .createdAt(message.getCreatedAt())
                .updatedAt(message.getUpdatedAt())
                .build();
    }

    private void validateRoomRequest(CreateRoomRequest request) {
        if (request == null) {
            throw new BadRequestException("Room request is required");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Room name is required");
        }
        if (request.getName().length() > 180) {
            throw new BadRequestException("Room name is too long");
        }
        if (request.getDescription() != null && request.getDescription().length() > 2_000) {
            throw new BadRequestException("Room description is too long");
        }
    }

    private String validateMessageBody(String body) {
        if (body == null || body.isBlank()) {
            throw new BadRequestException("Message body is required");
        }
        String trimmed = body.trim();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException("Message is too long");
        }
        return trimmed;
    }

    private String normalizeRoomType(String value) {
        String roomType = value == null || value.isBlank() ? "GENERAL" : value.trim().toUpperCase(Locale.US);
        if (!ROOM_TYPES.contains(roomType)) {
            throw new BadRequestException("Unsupported room type");
        }
        return roomType;
    }

    private String normalizeVisibility(String value, String fallback) {
        String visibility = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.US);
        if (!VISIBILITIES.contains(visibility)) {
            throw new BadRequestException("Unsupported room visibility");
        }
        return visibility;
    }

    private String normalizeNullable(String value, Set<String> allowedValues, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toUpperCase(Locale.US);
        if (allowedValues != null && !allowedValues.contains(normalized)) {
            throw new BadRequestException("Unsupported " + fieldName);
        }
        return normalized;
    }

    private String defaultVisibility(String roomType) {
        return "FOUNDER".equals(roomType) || "DIRECT".equals(roomType) ? "PRIVATE" : "AUTHENTICATED";
    }

    private int normalizeLimit(Integer limit, int fallback, int max) {
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return Math.min(limit, max);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String directRoomTargetId(Long firstUserId, Long secondUserId) {
        long first = Math.min(firstUserId, secondUserId);
        long second = Math.max(firstUserId, secondUserId);
        return first + ":" + second;
    }

    private String displayName(User user) {
        String fullName = ((user.getFirstName() == null ? "" : user.getFirstName()) + " " + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return fullName.isBlank() ? "AIRRAL member" : fullName;
    }

    private String initials(User user) {
        List<String> parts = new ArrayList<>();
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            parts.add(user.getFirstName().substring(0, 1).toUpperCase(Locale.US));
        }
        if (user.getLastName() != null && !user.getLastName().isBlank()) {
            parts.add(user.getLastName().substring(0, 1).toUpperCase(Locale.US));
        }
        return parts.isEmpty() ? "AR" : String.join("", parts).substring(0, Math.min(2, String.join("", parts).length()));
    }

    private String generateInviteToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
