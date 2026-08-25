package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CreateJobRequest;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobServiceCatalogLifecycleTest {

    private JobRepository jobRepository;
    private UserRepository userRepository;
    private OrganizationRepository organizationRepository;
    private InternalJobCatalogProjectionService projectionService;
    private JobService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        userRepository = mock(UserRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        projectionService = mock(InternalJobCatalogProjectionService.class);
        service = new JobService(
                jobRepository,
                userRepository,
                organizationRepository,
                mock(ExternalJobPostingStore.class),
                projectionService);
    }

    @Test
    void createsJobAndProjectsSavedRecordBeforeReturning() {
        CreateJobRequest request = CreateJobRequest.builder()
                .title("Product Designer")
                .description("Design calm hiring workflows")
                .status(JobStatus.OPEN)
                .build();
        Organization organization = Organization.builder().id(4L).name("Northstar").build();
        User creator = User.builder().id(9L).firstName("Ari").lastName("Lee").build();

        when(jobRepository.save(any())).thenAnswer(invocation -> {
            Job saved = invocation.getArgument(0);
            saved.setId(22L);
            return Mono.just(saved);
        });
        when(projectionService.sync(any())).thenReturn(Mono.empty());
        when(userRepository.findById(9L)).thenReturn(Mono.just(creator));
        when(organizationRepository.findById(4L)).thenReturn(Mono.just(organization));

        StepVerifier.create(service.createJob(request, 4L, 9L))
                .assertNext(response -> {
                    assertThat(response.getId()).isEqualTo(22L);
                    assertThat(response.getOrganizationName()).isEqualTo("Northstar");
                    assertThat(response.getStatus()).isEqualTo(JobStatus.OPEN);
                })
                .verifyComplete();

        ArgumentCaptor<Job> projectedJob = ArgumentCaptor.forClass(Job.class);
        verify(projectionService).sync(projectedJob.capture());
        assertThat(projectedJob.getValue().getId()).isEqualTo(22L);
    }

    @Test
    void deactivatesCatalogProjectionBeforeDeletingJob() {
        Job job = Job.builder()
                .id(22L)
                .organizationId(4L)
                .status(JobStatus.OPEN)
                .build();
        when(jobRepository.findByIdAndOrganizationId(22L, 4L)).thenReturn(Mono.just(job));
        when(projectionService.deactivate(22L)).thenReturn(Mono.empty());
        when(jobRepository.delete(job)).thenReturn(Mono.empty());

        StepVerifier.create(service.deleteJob(22L, 4L)).verifyComplete();

        verify(projectionService).deactivate(22L);
        verify(jobRepository).delete(job);
    }
}
