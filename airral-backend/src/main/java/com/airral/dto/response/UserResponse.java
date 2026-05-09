package com.airral.dto.response;

import com.airral.domain.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    
    private Long organizationId;
    private String organizationName;
    
    private UserRole role;
    private Boolean isPlatformAdmin;
    
    private Long managerId;
    private String managerName;
    private String department;
    private String jobTitle;
    private Long departmentId;
    
    private Boolean isActive;
    private Boolean emailVerified;
    
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}
