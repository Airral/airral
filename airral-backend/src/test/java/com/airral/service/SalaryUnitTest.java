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
 *
 * <p>Workday, SmartRecruiters, Workable, career pages and the schema.org path
 * publish no pay fields at all, so their unit is only ever a phrase in the
 * description. Target's "Full Time Hourly Warehouse Operations Openings" listed
 * "$21.00 to $23.93" and nothing else -- an hourly wage sitting on the page in
 * the shape of a salary. Those sources are about a third of the catalogue,
 * Workday alone 28%, so the second half of these tests holds the same rule over
 * pay read out of prose, and holds the line on the honest answer: a figure whose
 * unit the text never states is published as a bare figure.
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

    private String proseLabel(String descriptionText) {
        Object salary = ReflectionTestUtils.invokeMethod(service, "extractProseSalary", descriptionText);
        if (salary == null) {
            return null;
        }

        return ReflectionTestUtils.invokeMethod(salary, "label");
    }

    private String prosePeriod(String descriptionText) {
        Object salary = ReflectionTestUtils.invokeMethod(service, "extractProseSalary", descriptionText);
        if (salary == null) {
            return null;
        }

        return ReflectionTestUtils.invokeMethod(salary, "period");
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
    @DisplayName("a unit word belonging to a different noun is not borrowed by the pay")
    void aUnitWordOnAnotherNounIsNotBorrowed() {
        // Every one of these was measured returning the wrong interval: the pay
        // clause contains a unit word, but it modifies an allowance, a stipend, a
        // bonus or how often the wage is handed over -- not the figure. The worst
        // of them published an annual salary as "USD $120000-$150000/mo".
        assertThat(prosePeriod("Salary $120,000 - $150,000 plus a monthly car allowance.")).isNull();
        assertThat(prosePeriod("Pay: $18.00 - $22.00 with weekly pay and full benefits")).isNull();
        assertThat(prosePeriod("Base pay $30.00 - $40.00 with a daily meal stipend")).isNull();
        assertThat(prosePeriod("Compensation: $120,000 - $150,000 and an annual bonus")).isNull();
        assertThat(prosePeriod("Paid weekly with base pay of $21.00 - $23.93.")).isNull();

        // Two deliberate misses, kept here so neither is "fixed" by accident.
        //
        // The first states a real unit, but in the previous sentence, and
        // reaching across a full stop is what lets a bonus or an allowance two
        // clauses away claim the wage.
        assertThat(prosePeriod("This role is paid hourly. Range is $21.00 to $23.93.")).isNull();

        // The second is "paid <adverb>", which cannot be read either way round:
        // it is the same construction as "Paid weekly with base pay of ...", where
        // the adverb is how often the wage is handed over and not the unit it is
        // quoted in. Accepting "paid hourly" while rejecting "paid weekly" would
        // be guessing which of the two an employer meant. A bare figure is the
        // honest answer to both.
        assertThat(prosePeriod("This role is paid hourly and pays $21.00 to $23.93")).isNull();
    }

    @Test
    @DisplayName("a genuine interval is still read, on both sides of the figure")
    void genuineIntervalsSurviveTheTightening() {
        // The tightening must not cost the cases it exists to serve.
        assertThat(prosePeriod("$21.00 to $23.93 per hour")).isEqualTo("HOUR");
        assertThat(prosePeriod("Base pay $21.00 to $23.93/hr")).isEqualTo("HOUR");
        assertThat(prosePeriod("The hourly rate for this role is $21.00 to $23.93")).isEqualTo("HOUR");
        assertThat(prosePeriod("Annual base salary range: $120,000 - $150,000")).isEqualTo("YEAR");
        assertThat(prosePeriod("The annual pay range for this role is $120,000 - $150,000")).isEqualTo("YEAR");
    }

    @Test
    @DisplayName("a list flattened into one line cannot lend its unit across items")
    void flattenedListDoesNotLeakItsUnit() {
        // stripHtml turns "<li>Pay: $18-$22</li><li>Weekly pay</li>" into one line
        // with no punctuation between the items, so the clause rule alone does not
        // separate them. The narrower after-the-amount vocabulary is what does.
        assertThat(prosePeriod("Pay: $18.00 - $22.00 Weekly pay Medical dental and vision")).isNull();
        assertThat(prosePeriod("Rate: $21.00 to $23.93 per hour Medical dental and vision")).isEqualTo("HOUR");
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

    @Test
    @DisplayName("an interval stated in prose is read, and renders like a board's own")
    void proseHourlyRateIsLabelledHourly() {
        // Target, "Full Time Hourly Warehouse Operations Openings", via Workday --
        // no pay fields on the payload, the whole of the pay is this sentence.
        assertThat(prosePeriod("Pay Range: $21.00 to $23.93 per hour")).isEqualTo("HOUR");
        assertThat(proseLabel("Pay Range: $21.00 to $23.93 per hour")).isEqualTo("USD $21-$23.93/hr");
    }

    @Test
    @DisplayName("prose states the unit before the amount just as often as after it")
    void proseIntervalBeforeTheAmount() {
        assertThat(prosePeriod("The hourly rate for this role is $21.00 to $23.93."))
                .isEqualTo("HOUR");
        assertThat(proseLabel("The hourly rate for this role is $21.00 to $23.93."))
                .isEqualTo("USD $21-$23.93/hr");
    }

    @Test
    @DisplayName("an annual figure stated in prose still renders in thousands")
    void proseAnnualRangeRendersInThousands() {
        assertThat(prosePeriod("The pay range for this position is $120,000 - $150,000 annually."))
                .isEqualTo("YEAR");
        assertThat(proseLabel("The pay range for this position is $120,000 - $150,000 annually."))
                .isEqualTo("USD $120k-$150k");
    }

    @Test
    @DisplayName("prose that states no unit publishes a bare figure, not an annual one")
    void proseWithoutAnIntervalStatesNoInterval() {
        // The Target posting as it actually reads. An hourly wage and an annual
        // salary are written identically here, so the only honest answer is the
        // number on its own -- inferring YEAR from the text having no unit is the
        // same guess that published "$0k-$0k".
        assertThat(prosePeriod("Pay Range: $21.00 to $23.93")).isNull();
        assertThat(proseLabel("Pay Range: $21.00 to $23.93")).isEqualTo("USD $21-$23.93");
        assertThat(proseLabel("Pay Range: $21.00 to $23.93")).doesNotContainIgnoringCase("hr");
    }

    @Test
    @DisplayName("a benefits number near the pay does not lend the pay its unit")
    void proseIgnoresAnIntervalBelongingToAnotherNumber() {
        // Every benefits paragraph carries numbers with units of their own. Reading
        // across to one of them is worse than reading nothing: it puts "/hr" or a
        // "k" on a figure the employer never described that way.
        String bonusFirst = "We offer a 401(k) with 4% match and a 10% annual bonus. "
                + "The pay range for this role is $21.00 to $23.93.";
        assertThat(prosePeriod(bonusFirst)).isNull();
        assertThat(proseLabel(bonusFirst)).isEqualTo("USD $21-$23.93");

        String bonusAfter = "The pay range for this role is $21.00 to $23.93, "
                + "plus a 10% annual bonus and a 401(k) with 4% match.";
        assertThat(prosePeriod(bonusAfter)).isNull();
        assertThat(proseLabel(bonusAfter)).isEqualTo("USD $21-$23.93");
    }

    @Test
    @DisplayName("the pay's own unit is still read when a bonus percentage sits beside it")
    void proseReadsTheIntervalThatDoesBelongToThePay() {
        // The guard above must not cost us the interval that is genuinely there.
        String text = "Rate of pay: $21.00 to $23.93 per hour. 10% annual bonus available.";

        assertThat(prosePeriod(text)).isEqualTo("HOUR");
        assertThat(proseLabel(text)).isEqualTo("USD $21-$23.93/hr");
    }

    @Test
    @DisplayName("prose goes through the same sanity check the boards do")
    void proseAnnualClaimIsCheckedAgainstTheNumber() {
        // Nothing about prose earns it more trust than a board field: "annually"
        // against $39.15 is the Coinbase slip again, and loses its interval the
        // same way.
        assertThat(prosePeriod("Salary $39.15 to $46.06 annually")).isNull();
        assertThat(proseLabel("Salary $39.15 to $46.06 annually")).isEqualTo("USD $39.15-$46.06");
    }

    @Test
    @DisplayName("a thousands separator does not carry the match past the clause break")
    void proseAmountStopsAtTheCommaThatEndsTheClause() {
        // Why the amount groups end on a digit. A greedy [\d,]* takes the comma
        // after "$150,000" as part of the number, so the match ends past the
        // break, the clause runs on into "plus an annual bonus", and the bonus
        // lends the salary its unit. The two tests above miss this: their commas
        // follow "$23.93", where the decimal already stops the digit run.
        String text = "The pay range is $120,000 - $150,000, plus an annual bonus.";

        assertThat(prosePeriod(text)).isNull();
        assertThat(proseLabel(text)).isEqualTo("USD $120k-$150k");
    }

    @Test
    @DisplayName("a unit is not borrowed across the figure it belongs to")
    void proseIgnoresAnIntervalGuardedByAnInterveningFigure() {
        // No punctuation between the two here, so the clause bound alone would
        // read "monthly" onto an hourly wage and publish "$21-$23.93/mo" -- and
        // MONTH gets no plausibility check to catch it afterwards. The $500
        // standing in between is the whole of what says the unit is the
        // stipend's, so it is worth a test of its own.
        String text = "Base pay $21.00 to $23.93 with a $500 monthly stipend";

        assertThat(prosePeriod(text)).isNull();
        assertThat(proseLabel(text)).isEqualTo("USD $21-$23.93");
    }
}
