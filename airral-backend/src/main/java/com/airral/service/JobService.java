package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CreateJobRequest;
import com.airral.dto.response.JobResponse;
import com.airral.dto.response.PublicStatisticsResponse;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final ExternalJobPostingStore externalJobPostingStore;
    private final InternalJobCatalogProjectionService internalJobCatalogProjectionService;

    public JobService(
            JobRepository jobRepository,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            ExternalJobPostingStore externalJobPostingStore,
            InternalJobCatalogProjectionService internalJobCatalogProjectionService) {
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.externalJobPostingStore = externalJobPostingStore;
        this.internalJobCatalogProjectionService = internalJobCatalogProjectionService;
    }

    /**
     * Create a new job
     */
    @Transactional
    public Mono<JobResponse> createJob(CreateJobRequest request, Long organizationId, Long userId) {
        Job job = Job.builder()
                .organizationId(organizationId)
                .createdById(userId)
                .title(request.getTitle())
                .description(request.getDescription())
                .departmentId(request.getDepartmentId())
                .department(request.getDepartment())
                .location(request.getLocation())
                .employmentType(request.getEmploymentType())
                .salaryMin(request.getSalaryMin())
                .salaryMax(request.getSalaryMax())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .requirements(request.getRequirements())
                .niceToHave(request.getNiceToHave())
                .status(request.getStatus() != null ? request.getStatus() : JobStatus.DRAFT)
                .atsKeywords(request.getAtsKeywords() != null
                        ? request.getAtsKeywords().toArray(String[]::new)
                        : null)
                .atsWeights(request.getAtsWeights() != null ? Json.of(request.getAtsWeights()) : null)
                .atsMinScore(request.getAtsMinScore() != null ? request.getAtsMinScore() : 70)
                .linkedInEnabled(request.getLinkedInEnabled())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return jobRepository.save(job)
                .flatMap(savedJob -> internalJobCatalogProjectionService.sync(savedJob).thenReturn(savedJob))
                .flatMap(this::toJobResponse);
    }

    /**
     * Get all jobs for an organization
     */
    public Flux<JobResponse> getAllJobs(Long organizationId) {
        return jobRepository.findByOrganizationId(organizationId)
                .flatMap(this::toJobResponse);
    }

    /**
     * Get open jobs (public - for job board)
     */
    public Flux<JobResponse> getOpenJobs() {
        return jobRepository.findOpenJobs()
                .flatMap(this::toJobResponse);
    }

    /**
     * Get open jobs for public pages without internal hiring configuration.
     */
    public Flux<JobResponse> getPublicOpenJobs() {
        return jobRepository.findOpenJobs()
                .flatMap(this::toPublicJobResponse);
    }

    /**
     * Search open jobs for public pages without internal hiring configuration.
     */
    public Flux<JobResponse> getPublicOpenJobs(String query, String department) {
        String normalizedQuery = normalizeSearchQuery(query);
        String normalizedDepartment = normalizeExactFilter(department);

        Flux<Job> jobs;
        if (normalizedQuery != null && normalizedDepartment != null) {
            jobs = jobRepository.searchOpenJobsByDepartment(normalizedQuery, normalizedDepartment);
        } else if (normalizedQuery != null) {
            jobs = jobRepository.searchOpenJobs(normalizedQuery);
        } else if (normalizedDepartment != null) {
            jobs = jobRepository.findOpenJobsByDepartment(normalizedDepartment);
        } else {
            jobs = jobRepository.findOpenJobs();
        }

        return jobs.flatMap(this::toPublicJobResponse);
    }

    /**
     * Get one open job for public pages without internal hiring configuration.
     */
    public Mono<JobResponse> getPublicOpenJobById(Long id) {
        return jobRepository.findOpenJobById(id)
                .flatMap(this::toPublicJobResponse);
    }

    /**
     * Get job by ID
     */
    public Mono<JobResponse> getJobById(Long id, Long organizationId) {
        return jobRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(this::toJobResponse);
    }

    /**
     * Update a job
     */
    @Transactional
    public Mono<JobResponse> updateJob(Long id, CreateJobRequest request, Long organizationId) {
        return jobRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(job -> {
                    // Update fields
                    job.setTitle(request.getTitle());
                    job.setDescription(request.getDescription());
                    job.setDepartmentId(request.getDepartmentId());
                    job.setDepartment(request.getDepartment());
                    job.setLocation(request.getLocation());
                    job.setEmploymentType(request.getEmploymentType());
                    job.setSalaryMin(request.getSalaryMin());
                    job.setSalaryMax(request.getSalaryMax());
                    job.setCurrency(request.getCurrency());
                    job.setRequirements(request.getRequirements());
                    job.setNiceToHave(request.getNiceToHave());
                    job.setStatus(request.getStatus());
                    job.setAtsKeywords(request.getAtsKeywords() != null
                            ? request.getAtsKeywords().toArray(String[]::new)
                            : null);
                    job.setAtsWeights(request.getAtsWeights() != null ? Json.of(request.getAtsWeights()) : null);
                    job.setAtsMinScore(request.getAtsMinScore());
                    job.setLinkedInEnabled(request.getLinkedInEnabled());
                    job.setUpdatedAt(LocalDateTime.now());

                    return jobRepository.save(job);
                })
                .flatMap(savedJob -> internalJobCatalogProjectionService.sync(savedJob).thenReturn(savedJob))
                .flatMap(this::toJobResponse);
    }

    /**
     * Delete a job
     */
    @Transactional
    public Mono<Void> deleteJob(Long id, Long organizationId) {
        return jobRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(job -> internalJobCatalogProjectionService.deactivate(job.getId())
                        .then(jobRepository.delete(job)));
    }

    /**
     * Get jobs by status
     */
    public Flux<JobResponse> getJobsByStatus(Long organizationId, JobStatus status) {
        return jobRepository.findByOrganizationIdAndStatus(organizationId, status)
                .flatMap(this::toJobResponse);
    }

    /**
     * Convert Job entity to JobResponse DTO
     */
    private Mono<JobResponse> toJobResponse(Job job) {
        Mono<String> createdByName = job.getCreatedById() == null
                ? Mono.just("Unknown")
                : userRepository.findById(job.getCreatedById())
                        .map(user -> user.getFullName())
                        .defaultIfEmpty("Unknown");

        Mono<Organization> organization = findOrganization(job.getOrganizationId());

        return createdByName.flatMap(createdBy ->
                organization
                        .map(org -> buildJobResponse(job, createdBy, org))
                        .defaultIfEmpty(buildJobResponse(job, createdBy, null)));
    }

    private JobResponse buildJobResponse(Job job, String createdBy, Organization organization) {
        return JobResponse.builder()
                .id(job.getId())
                .organizationId(job.getOrganizationId())
                .organizationName(organization != null ? organization.getName() : null)
                .organizationDomain(organization != null ? organization.getDomain() : null)
                .organizationLogoUrl(organization != null ? organization.getLogoUrl() : null)
                .title(job.getTitle())
                .description(job.getDescription())
                .departmentId(job.getDepartmentId())
                .department(job.getDepartment())
                .location(job.getLocation())
                .employmentType(job.getEmploymentType())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .currency(job.getCurrency())
                .requirements(job.getRequirements())
                .niceToHave(job.getNiceToHave())
                .status(job.getStatus())
                .atsKeywords(job.getAtsKeywords() != null
                        ? Arrays.asList(job.getAtsKeywords())
                        : null)
                .atsWeights(job.getAtsWeights() != null ? job.getAtsWeights().asString() : null)
                .atsMinScore(job.getAtsMinScore())
                .linkedInEnabled(job.getLinkedInEnabled())
                .linkedinPostId(job.getLinkedinPostId())
                .createdBy(createdBy)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    private Mono<JobResponse> toPublicJobResponse(Job job) {
        return findOrganization(job.getOrganizationId())
                .map(org -> buildPublicJobResponse(job, org))
                .defaultIfEmpty(buildPublicJobResponse(job, null));
    }

    private Mono<Organization> findOrganization(Long organizationId) {
        return organizationId == null
                ? Mono.empty()
                : organizationRepository.findById(organizationId);
    }

    private String normalizeSearchQuery(String value) {
        String normalized = normalizeExactFilter(value);
        return normalized == null ? null : "%" + normalized + "%";
    }

    private String normalizeExactFilter(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim().toLowerCase();
        return normalized.isEmpty() || "all".equals(normalized) ? null : normalized;
    }

    private JobResponse buildPublicJobResponse(Job job, Organization organization) {
        return JobResponse.builder()
                .id(job.getId())
                .organizationName(organization != null ? organization.getName() : null)
                .organizationDomain(organization != null ? organization.getDomain() : null)
                .organizationLogoUrl(organization != null ? organization.getLogoUrl() : null)
                .title(job.getTitle())
                .description(job.getDescription())
                .department(job.getDepartment())
                .location(job.getLocation())
                .employmentType(job.getEmploymentType())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .currency(job.getCurrency())
                .requirements(job.getRequirements())
                .niceToHave(job.getNiceToHave())
                .status(job.getStatus())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    /**
     * Get public statistics: total companies with open jobs and total open jobs
     */
    public Mono<PublicStatisticsResponse> getPublicStatistics() {
        return Mono.zip(
                jobRepository.countOpenJobs(),
                organizationRepository.countOrganizationsWithOpenJobs(),
                externalJobPostingStore.countActivePostings(),
                externalJobPostingStore.countCompaniesWithActivePostings()
        ).map(tuple -> PublicStatisticsResponse.builder()
                .totalJobs(tuple.getT1() + tuple.getT3())
                .totalCompanies(tuple.getT2() + tuple.getT4())
                .build());
    }
}
