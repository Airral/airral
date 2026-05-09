package com.airral.service;

import com.airral.domain.enums.ApplicationStatus;
import com.airral.dto.response.DashboardMetricsResponse;
import com.airral.dto.response.PipelineAnalyticsResponse;
import com.airral.repository.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;
    private final OfferRepository offerRepository;
    
    // Reserved for future analytics features
    @SuppressWarnings("unused")
    private final ReferralRepository referralRepository;
    @SuppressWarnings("unused")
    private final UserRepository userRepository;
    @SuppressWarnings("unused")
    private final DepartmentRepository departmentRepository;

    public AnalyticsService(JobRepository jobRepository,
                           ApplicationRepository applicationRepository,
                           InterviewRepository interviewRepository,
                           OfferRepository offerRepository,
                           ReferralRepository referralRepository,
                           UserRepository userRepository,
                           DepartmentRepository departmentRepository) {
        this.jobRepository = jobRepository;
        this.applicationRepository = applicationRepository;
        this.interviewRepository = interviewRepository;
        this.offerRepository = offerRepository;
        this.referralRepository = referralRepository;
        this.userRepository = userRepository;
        this.departmentRepository = departmentRepository;
    }

    public Mono<DashboardMetricsResponse> getDashboardMetrics(Long organizationId) {
        LocalDateTime now = LocalDateTime.now();

        Mono<Long> totalJobs = jobRepository.countByOrganizationId(organizationId);
        Mono<Long> openJobs = jobRepository.countOpenJobsByOrganizationId(organizationId);
        Mono<Long> totalApplications = applicationRepository.countByOrganizationId(organizationId);
        Mono<Long> totalInterviews = interviewRepository.countByOrganizationId(organizationId);
        Mono<Long> upcomingInterviews = interviewRepository.countUpcomingByOrganizationId(organizationId, now);
        Mono<Long> totalOffers = offerRepository.countByOrganizationId(organizationId);
        Mono<Long> offersAccepted = offerRepository.countAcceptedByOrganizationId(organizationId);
        Mono<Long> totalHires = applicationRepository
                .findByOrganizationIdAndStatus(organizationId, ApplicationStatus.HIRED)
                .count();

        return Mono.zip(totalJobs, openJobs, totalApplications, totalInterviews,
                        upcomingInterviews, totalOffers, offersAccepted, totalHires)
                .map(t -> {
                    long jobs = t.getT1();
                    long open = t.getT2();
                    long apps = t.getT3();
                    long interviews = t.getT4();
                    long upcoming = t.getT5();
                    long offers = t.getT6();
                    long accepted = t.getT7();
                    long hires = t.getT8();

                    double offerRate = offers > 0 ? Math.round((accepted * 100.0 / offers) * 10) / 10.0 : 0.0;
                    double appToInterview = apps > 0 ? Math.round((interviews * 100.0 / apps) * 10) / 10.0 : 0.0;

                    return DashboardMetricsResponse.builder()
                            .totalJobs(jobs)
                            .openJobs(open)
                            .closedJobs(jobs - open)
                            .totalApplications(apps)
                            .newApplicationsToday(0L)
                            .newApplicationsThisWeek(0L)
                            .newApplicationsThisMonth(0L)
                            .totalInterviews(interviews)
                            .upcomingInterviews(upcoming)
                            .completedInterviews(interviews - upcoming)
                            .interviewsThisWeek(0L)
                            .totalOffers(offers)
                            .offersAccepted(accepted)
                            .offersDeclined(0L)
                            .offersPending(offers - accepted)
                            .totalHires(hires)
                            .hiresThisMonth(0L)
                            .hiresThisQuarter(0L)
                            .totalReferrals(0L)
                            .pendingReferrals(0L)
                            .acceptedReferrals(0L)
                            .totalUsers(0L)
                            .activeUsers(0L)
                            .totalDepartments(0L)
                            .averageTimeToHire(0.0)
                            .averageTimeToInterview(0.0)
                            .offerAcceptanceRate(offerRate)
                            .applicationToInterviewRate(appToInterview)
                            .build();
                });
    }

    public Mono<PipelineAnalyticsResponse> getPipelineAnalytics(Long organizationId) {
        return applicationRepository.findAllByOrganizationId(organizationId)
                .collectList()
                .flatMap(apps -> {
                    Map<String, Long> byStatus = apps.stream()
                            .collect(Collectors.groupingBy(
                                    a -> a.getStatus().name(),
                                    Collectors.counting()));

                    long total = apps.size();
                    long hires = byStatus.getOrDefault(ApplicationStatus.HIRED.name(), 0L);
                    long rejected = byStatus.getOrDefault(ApplicationStatus.REJECTED.name(), 0L);

                    Mono<Long> interviewCount = interviewRepository.countByOrganizationId(organizationId);
                    Mono<Long> offerCount = offerRepository.countByOrganizationId(organizationId);
                    Mono<Long> acceptedCount = offerRepository.countAcceptedByOrganizationId(organizationId);

                    return Mono.zip(interviewCount, offerCount, acceptedCount)
                            .map(t -> {
                                long interviews = t.getT1();
                                long offers = t.getT2();
                                long accepted = t.getT3();

                                double appToInterview = total > 0 ? Math.round((interviews * 100.0 / total) * 10) / 10.0 : 0.0;
                                double interviewToOffer = interviews > 0 ? Math.round((offers * 100.0 / interviews) * 10) / 10.0 : 0.0;
                                double offerToHire = offers > 0 ? Math.round((hires * 100.0 / offers) * 10) / 10.0 : 0.0;

                                return PipelineAnalyticsResponse.builder()
                                        .applicationsReceived(total)
                                        .applicationsReviewed(total - byStatus.getOrDefault(ApplicationStatus.SUBMITTED.name(), 0L))
                                        .applicationsRejected(rejected)
                                        .interviewsScheduled(interviews)
                                        .interviewsCompleted(0L)
                                        .offersSent(offers)
                                        .offersAccepted(accepted)
                                        .hires(hires)
                                        .applicationToInterviewRate(appToInterview)
                                        .interviewToOfferRate(interviewToOffer)
                                        .offerToHireRate(offerToHire)
                                        .avgTimeToReview(0.0)
                                        .avgTimeToInterview(0.0)
                                        .avgTimeToOffer(0.0)
                                        .avgTimeToHire(0.0)
                                        .applicationsByStatus(byStatus)
                                        .interviewsByStatus(new HashMap<>())
                                        .offersByStatus(new HashMap<>())
                                        .applicationsByJob(new HashMap<>())
                                        .hiresByJob(new HashMap<>())
                                        .build();
                            });
                });
    }
}
