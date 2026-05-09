package com.airral.controller;

import com.airral.domain.UserInvitation;
import com.airral.dto.request.InviteUserRequest;
import com.airral.dto.request.UpdateUserRequest;
import com.airral.dto.response.UserResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class UserController {

    private final UserService userService;
    private final JwtTokenProvider jwtTokenProvider;

    public UserController(UserService userService, JwtTokenProvider jwtTokenProvider) {
        this.userService = userService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Get all users in organization
     * GET /api/users
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<UserResponse>>> getAllUsers(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(userService.getAllUsers(organizationId)));
    }

    /**
     * Get user by ID
     * GET /api/users/{id}
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN', 'EMPLOYEE')")
    public Mono<ResponseEntity<UserResponse>> getUserById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return userService.getUserById(id, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Get team members for a manager
     * GET /api/users/team/{managerId}
     */
    @GetMapping("/team/{managerId}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<UserResponse>>> getTeamMembers(@PathVariable Long managerId) {
        return Mono.just(ResponseEntity.ok(userService.getTeamMembers(managerId)));
    }

    /**
     * Update user profile
     * PUT /api/users/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN', 'EMPLOYEE')")
    public Mono<ResponseEntity<UserResponse>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return userService.updateUser(id, request, organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Invite a new team member
     * POST /api/users/invite
     */
    @PostMapping("/invite")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<UserInvitation>> inviteUser(
            @Valid @RequestBody InviteUserRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return userService.inviteUser(request, organizationId, userId)
                .map(invitation -> ResponseEntity.status(HttpStatus.CREATED).body(invitation));
    }

    /**
     * Get pending invitations
     * GET /api/users/invitations
     */
    @GetMapping("/invitations")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<UserInvitation>>> getPendingInvitations(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(userService.getPendingInvitations(organizationId)));
    }

    /**
     * Helper method to extract JWT token from Authorization header
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
