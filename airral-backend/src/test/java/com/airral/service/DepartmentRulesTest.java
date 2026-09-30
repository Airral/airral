package com.airral.service;

import com.airral.domain.Department;
import com.airral.dto.request.CreateDepartmentRequest;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Renaming and deleting departments. Jobs, people and invitations carry the
 * department's name beside its id, so both have to reach them.
 */
class DepartmentRulesTest {

    private static final long ACME = 1L;

    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserInvitationRepository invitations = mock(UserInvitationRepository.class);
    private DepartmentService service;
    private Department engineering;

    @BeforeEach
    void setUp() {
        service = new DepartmentService(departments, jobs, users, invitations);
        engineering = Department.builder().id(5L).organizationId(ACME).name("Engineering").isActive(true).build();
        when(departments.findByIdAndOrganizationId(5L, ACME)).thenReturn(Mono.just(engineering));
        when(departments.save(any(Department.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(departments.delete(any(Department.class))).thenReturn(Mono.empty());
        when(jobs.setDepartmentName(anyLong(), anyString())).thenReturn(Mono.just(2L));
        when(users.setDepartmentName(anyLong(), anyString())).thenReturn(Mono.just(3L));
        when(invitations.setDepartmentName(anyLong(), anyString())).thenReturn(Mono.just(0L));
        when(jobs.clearDepartment(anyLong())).thenReturn(Mono.just(2L));
        when(users.clearDepartment(anyLong())).thenReturn(Mono.just(3L));
        when(invitations.clearDepartment(anyLong())).thenReturn(Mono.just(0L));
    }

    private static CreateDepartmentRequest named(String name) {
        return CreateDepartmentRequest.builder().name(name).build();
    }

    @Test
    @DisplayName("renaming to another department's name is refused")
    void renameToTakenName() {
        when(departments.existsByOrganizationIdAndName(ACME, "Sales")).thenReturn(Mono.just(true));

        StepVerifier.create(service.updateDepartment(5L, named(" Sales "), ACME))
                .expectError(ConflictException.class)
                .verify();
        verify(departments, never()).save(any());
    }

    @Test
    @DisplayName("a rename reaches the jobs, people and invitations in that department")
    void renameReachesEveryCopy() {
        when(departments.existsByOrganizationIdAndName(ACME, "Product engineering")).thenReturn(Mono.just(false));

        StepVerifier.create(service.updateDepartment(5L, named("Product engineering"), ACME))
                .assertNext(response -> assertThat(response.getName()).isEqualTo("Product engineering"))
                .verifyComplete();
        verify(jobs).setDepartmentName(5L, "Product engineering");
        verify(users).setDepartmentName(5L, "Product engineering");
        verify(invitations).setDepartmentName(5L, "Product engineering");
    }

    @Test
    @DisplayName("changing only the capitals of a name is allowed")
    void recaseIsAllowed() {
        StepVerifier.create(service.updateDepartment(5L, named("ENGINEERING"), ACME))
                .assertNext(response -> assertThat(response.getName()).isEqualTo("ENGINEERING"))
                .verifyComplete();
        verify(departments, never()).existsByOrganizationIdAndName(any(), any());
    }

    @Test
    @DisplayName("deleting a department takes it off its jobs, people and invitations first")
    void deleteClearsEveryCopy() {
        StepVerifier.create(service.deleteDepartment(5L, ACME)).verifyComplete();

        var order = inOrder(jobs, users, invitations, departments);
        order.verify(jobs).clearDepartment(5L);
        order.verify(users).clearDepartment(5L);
        order.verify(invitations).clearDepartment(5L);
        order.verify(departments).delete(engineering);
    }

    @Test
    @DisplayName("a company cannot delete another company's department")
    void deleteOtherCompanysDepartment() {
        when(departments.findByIdAndOrganizationId(6L, ACME)).thenReturn(Mono.empty());

        StepVerifier.create(service.deleteDepartment(6L, ACME)).expectError(NotFoundException.class).verify();
        verify(jobs, never()).clearDepartment(anyLong());
    }
}
