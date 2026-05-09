package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.CreateJobRequest;
import com.airral.dto.response.JobResponse;
import com.airral.repository.JobRepository;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Arrays;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public JobService(JobRepository jobRepository, UserRepository userRepository) {
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    /**
     * Create a new job
     */
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
                .atsKeywords(request.getAtsKeywords() != null ? 
                    String.join(",", request.getAtsKeywords()) : null)
                .atsWeights(request.getAtsWeights())
                .atsMinScore(request.getAtsMinScore() != null ? request.getAtsMinScore() : 70)
                .linkedInEnabled(request.getLinkedInEnabled())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return jobRepository.save(job)
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
                    job.setAtsKeywords(request.getAtsKeywords() != null ? 
                        String.join(",", request.getAtsKeywords()) : null);
                    job.setAtsWeights(request.getAtsWeights());
                    job.setAtsMinScore(request.getAtsMinScore());
                    job.setLinkedInEnabled(request.getLinkedInEnabled());
                    job.setUpdatedAt(LocalDateTime.now());

                    return jobRepository.save(job);
                })
                .flatMap(this::toJobResponse);
    }

    /**
     * Delete a job
     */
    public Mono<Void> deleteJob(Long id, Long organizationId) {
        return jobRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Job not found")))
                .flatMap(jobRepository::delete);
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
        return userRepository.findById(job.getCreatedById())
                .map(user -> user.getFullName())
                .defaultIfEmpty("Unknown")
                .map(createdBy -> JobResponse.builder()
                        .id(job.getId())
                        .organizationId(job.getOrganizationId())
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
                        .atsKeywords(job.getAtsKeywords() != null ? 
                            Arrays.asList(job.getAtsKeywords().split(",")) : null)
                        .atsWeights(job.getAtsWeights())
                        .atsMinScore(job.getAtsMinScore())
                        .linkedInEnabled(job.getLinkedInEnabled())
                        .linkedinPostId(job.getLinkedinPostId())
                        .createdBy(createdBy)
                        .createdAt(job.getCreatedAt())
                        .updatedAt(job.getUpdatedAt())
                        .build()
                );
    }
}
