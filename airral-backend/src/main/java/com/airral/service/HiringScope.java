package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.enums.UserRole;
import com.airral.repository.JobRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

/**
 * Works out a caller's {@link JobScope}. For launch a company's team is its
 * hiring team: a manager works on the jobs they are hiring manager on, and HR
 * on every job.
 */
@Service
public class HiringScope {

    private final JobRepository jobRepository;

    public HiringScope(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public Mono<JobScope> of(Long organizationId, Long userId, String role) {
        if (UserRole.MANAGER.name().equals(role)) {
            return jobRepository.findByOrganizationIdAndHiringManagerId(organizationId, userId)
                    .map(Job::getId)
                    .collect(Collectors.toSet())
                    .map(JobScope::only);
        }
        return Mono.just(JobScope.wholeCompany());
    }
}
