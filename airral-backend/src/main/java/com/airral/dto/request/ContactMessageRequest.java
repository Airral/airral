package com.airral.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A message from the website's contact form. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactMessageRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    @Size(max = 150, message = "Subject must be at most 150 characters")
    private String subject;

    @NotBlank(message = "Message is required")
    @Size(max = 5000, message = "Message must be at most 5000 characters")
    private String message;
}
