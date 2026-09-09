package com.airral.service;

import com.airral.dto.ashby.AshbyJobBoardResponse;
import com.airral.dto.greenhouse.GreenhouseJobBoardResponse;
import com.airral.dto.lever.LeverPostingResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pay figures carry a unit, and we were dropping it.
 *
 * <p>Every board we read states the interval alongside the number -- Greenhouse
 * in the range title ("Hourly Rate:" against "Annual base salary range"), Lever
 * in {@code interval} ("per-year-salary"), Ashby in {@code interval} ("1 HOUR").
 * We read the number and discarded the unit, so an hourly intern rate was
 * rendered by the same annual formatter as a staff engineer's base.
 *
 * <p>That formatter divides by 1000 and rounds, so $50/hr became "$0k". Ten of
 * sixty Coinbase postings on the live site read "USD $0k-$0k", and each one
 * still carried the "Employer posted" chip vouching for it -- we were not
 * failing to state the pay, we were stating that the job paid nothing. The same
 * number reached Google for Jobs as {@code minValue: 50, unitText: "YEAR"}.
 *
 * <p>The rule these tests hold: a figure is rendered in the unit it was
 * published in, and no non-zero amount is ever rounded away to "$0k".
 */
class SalaryUnitTest {

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class),
            mock(GreenhouseJobBoardClient.class),
            mock(LeverJobBoardClient.class),
            mock(AshbyJobBoardClient.class),
            mock(SmartRecruitersJobBoardClient.class),
            mock(WorkableJobBoardClient.class),
            mock(WorkdayJobBoardClient.class),
            mock(BambooHrJobBoardClient.class),
            mock(CareerPageJobBoardClient.class),
            mock(CandidateProfileRepository.class),
            mock(UserRepository.class),
            new ObjectMapper(),
            "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 0, 1);

    private GreenhouseJobBoardResponse.GreenhousePayRange greenhouse(String title, long minCents, long maxCents) {
        GreenhouseJobBoardResponse.GreenhousePayRange range = new GreenhouseJobBoardResponse.GreenhousePayRange();
        range.setTitle(title);
        range.setMinCents(BigDecimal.valueOf(minCents));
        range.setMaxCents(BigDecimal.valueOf(maxCents));
        range.setCurrencyType("USD");
        return range;
    }

    private String greenhouseLabel(GreenhouseJobBoardResponse.GreenhousePayRange range) {
        return ReflectionTestUtils.invokeMethod(service, "formatSalary", range);
    }

    private String period(String raw) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeSalaryPeriod", raw);
    }

    private String trusted(String rawInterval, BigDecimal min, BigDecimal max) {
        return ReflectionTestUtils.invokeMethod(service, "statedSalaryPeriod", rawInterval, min, max);
    }

    private String leverLabel(String interval, BigDecimal min, BigDecimal max) {
        LeverPostingResponse.LeverSalaryRange range = new LeverPostingResponse.LeverSalaryRange();
        range.setInterval(interval);
        range.setMin(min);
        range.setMax(max);
        range.setCurrency("USD");
        LeverPostingResponse posting = new LeverPostingResponse();
        posting.setSalaryRange(range);
        return ReflectionTestUtils.invokeMethod(service, "formatLeverSalary", posting);
    }

    @Test
    @DisplayName("an hourly intern rate is not rounded away to $0k")
    void hourlyRateSurvivesFormatting() {
        // Coinbase, "Accelerations Programs Intern": min_cents 4000 == $40.00/hr.
        String label = greenhouseLabel(greenhouse("Hourly Rate:", 4000, 5000));

        assertThat(label).doesNotContain("$0k");
        assertThat(label).contains("40");
        assertThat(label).contains("50");
    }

    @Test
    @DisplayName("an hourly rate says so, rather than reading as an annual salary")
    void hourlyRateIsLabelledHourly() {
        assertThat(greenhouseLabel(greenhouse("Hourly Rate:", 4000, 5000)))
                .containsIgnoringCase("hr");
    }

    @Test
    @DisplayName("an annual range still renders in thousands")
    void annualRangeIsUnchanged() {
        // Coinbase, "Accountant, Cyprus": 3610000 cents == $36,100/yr.
        String label = greenhouseLabel(
                greenhouse("Annual base salary range (excluding equity and bonus):", 3610000, 5000000));

        assertThat(label).isEqualTo("USD $36k-$50k");
    }

    @Test
    @DisplayName("Lever's per-year-salary interval reads as an annual figure")
    void leverAnnualInterval() {
        assertThat(leverLabel("per-year-salary", BigDecimal.valueOf(150000), BigDecimal.valueOf(180000)))
                .isEqualTo("USD $150k-$180k");
    }

    @Test
    @DisplayName("Lever's hourly interval is not rendered as an annual figure")
    void leverHourlyInterval() {
        String label = leverLabel("per-hour-wage", BigDecimal.valueOf(40), BigDecimal.valueOf(50));

        assertThat(label).doesNotContain("$0k");
        assertThat(label).containsIgnoringCase("hr");
    }

    @Test
    @DisplayName("the source's own interval vocabulary is understood, and nothing else is invented")
    void periodVocabulary() {
        assertThat(period("Hourly Rate:")).isEqualTo("HOUR");
        assertThat(period("Annual base salary range (excluding equity and bonus):")).isEqualTo("YEAR");
        assertThat(period("per-year-salary")).isEqualTo("YEAR");
        assertThat(period("per-hour-wage")).isEqualTo("HOUR");
        assertThat(period("1 YEAR")).isEqualTo("YEAR");
        assertThat(period("1 HOUR")).isEqualTo("HOUR");

        // Ashby sends NONE on non-salary components; a blank or unknown interval
        // must stay unknown rather than defaulting to YEAR, because defaulting is
        // what turned $50/hr into a $50 salary in the first place.
        assertThat(period("NONE")).isNull();
        assertThat(period(null)).isNull();
        assertThat(period("")).isNull();
        assertThat(period("per-fortnight-doubloons")).isNull();
    }

    @Test
    @DisplayName("a non-dollar currency does not get a dollar sign")
    void nonDollarCurrenciesDropTheSymbol() {
        // One Coinbase board publishes nine currencies; 60 of its 215 ranges are
        // not US dollars, and every one of them used to render as "EUR $36k".
        GreenhouseJobBoardResponse.GreenhousePayRange euros =
                greenhouse("Annual base salary range (excluding equity and bonus):", 3610000, 5000000);
        euros.setCurrencyType("EUR");
        assertThat(greenhouseLabel(euros)).isEqualTo("EUR 36k-50k");

        GreenhouseJobBoardResponse.GreenhousePayRange loonies =
                greenhouse("Annual base salary range (excluding equity and bonus):", 17000000, 17000000);
        loonies.setCurrencyType("CAD");
        assertThat(greenhouseLabel(loonies)).isEqualTo("CAD $170k");
    }

    @Test
    @DisplayName("a source that calls $39.15 an annual salary is not believed")
    void implausibleAnnualFigureLosesItsInterval() {
        // Coinbase, "Analyst II, Treasury Operations": titled "Annual base salary
        // range (excluding bonus):" with min_cents 3915. An employer slip, not a
        // job paying $39 a year -- so we publish the figure without the claim.
        GreenhouseJobBoardResponse.GreenhousePayRange range =
                greenhouse("Annual base salary range (excluding bonus):", 3915, 4606);

        assertThat(period("Annual base salary range (excluding bonus):")).isEqualTo("YEAR");
        assertThat(trusted("Annual base salary range (excluding bonus):",
                BigDecimal.valueOf(39.15), BigDecimal.valueOf(46.06))).isNull();
        assertThat(greenhouseLabel(range)).isEqualTo("USD $39.15-$46.06");
    }

    @Test
    @DisplayName("a real annual salary keeps its interval")
    void plausibleAnnualFigureIsBelieved() {
        assertThat(trusted("Annual base salary range (excluding bonus):",
                BigDecimal.valueOf(150000), BigDecimal.valueOf(180000))).isEqualTo("YEAR");
    }

    @Test
    @DisplayName("an unknown interval renders the amount plainly rather than guessing a unit")
    void unknownIntervalDoesNotGuess() {
        AshbyJobBoardResponse.AshbyCompensationComponent component =
                new AshbyJobBoardResponse.AshbyCompensationComponent();
        component.setCompensationType("Salary");
        component.setInterval("NONE");
        component.setMinValue(BigDecimal.valueOf(50));
        component.setMaxValue(BigDecimal.valueOf(60));
        component.setCurrencyCode("USD");

        String label = ReflectionTestUtils.invokeMethod(
                service, "formatMoneyRange",
                component.getMinValue(), component.getMaxValue(),
                component.getCurrencyCode(), null);

        assertThat(label).doesNotContain("$0k");
        assertThat(label).doesNotContainIgnoringCase("hr");
        assertThat(label).isEqualTo("USD $50-$60");
    }
}
