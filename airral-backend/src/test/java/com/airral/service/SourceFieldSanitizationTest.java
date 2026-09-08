package com.airral.service;

import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Rejecting source values that are not what the column claims to hold.
 *
 * <p>Nothing validated these, so whatever an employer put in a field was stored
 * as though it were the thing the column is named after. A large retail Workday
 * board puts the requisition id in the field we read as the department, and it
 * reached the department column, the tags array and the match score. The site
 * address arrived as the location, so there was no city to filter on.
 *
 * <p>Both sanitizers are deliberately conservative. Discarding a real department
 * or mangling a real location is worse than keeping an ugly one, so each test
 * below pairs the values that must go with the ones that must survive.
 */
class SourceFieldSanitizationTest {

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

    private String department(String value) {
        return ReflectionTestUtils.invokeMethod(service, "sanitizeDepartment", value);
    }

    private String location(String value) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeLocation", value);
    }

    @SuppressWarnings("unchecked")
    private List<String> tags(List<String> value) {
        return (List<String>) ReflectionTestUtils.invokeMethod(service, "sanitizeTags", value);
    }

    @Test
    @DisplayName("a requisition number is not a department")
    void requisitionIdsAreRejected() {
        // The exact value that reached 65 of 79 synced postings.
        assertThat(department("R0000448755")).isNull();
        assertThat(department("JR-12345")).isNull();
        assertThat(department("REQ-2024-001")).isNull();
        assertThat(department("12345")).isNull();
    }

    @Test
    @DisplayName("a real department survives, including the awkward ones")
    void realDepartmentsSurvive() {
        assertThat(department("Engineering")).isEqualTo("Engineering");
        assertThat(department("R&D Operations")).isEqualTo("R&D Operations");
        assertThat(department("Production")).isEqualTo("Production");
        // Digits are not disqualifying on their own.
        assertThat(department("Team 360")).isEqualTo("Team 360");
        assertThat(department("Sales2024")).isEqualTo("Sales2024");
        assertThat(department("G&A")).isEqualTo("G&A");
    }

    @Test
    @DisplayName("a street address is reduced to the place people search for")
    void streetAddressesAreReduced() {
        assertThat(location("960 Lititz Pike, Lititz,PA 17543-9328")).isEqualTo("Lititz, PA");
        assertThat(location("1 Infinite Loop, Cupertino, CA 95014")).isEqualTo("Cupertino, CA");
    }

    @Test
    @DisplayName("house numbers that are not plain digits are still recognised")
    void awkwardHouseNumbersAreHandled() {
        // Queens hyphenates and Wisconsin uses a grid prefix; a bare \\d+ missed both.
        assertThat(location("22-11 31st St, Astoria, NY")).isEqualTo("Astoria, NY");
        assertThat(location("N95 W Shady Ln, Menomonee Falls, WI")).isEqualTo("Menomonee Falls, WI");
    }

    @Test
    @DisplayName("a street line is kept when dropping it would leave no city")
    void streetIsKeptWhenItIsAllWeHave() {
        // "29 Palms, CA" is a real place, and a single segment is all there is --
        // dropping the leading segment in either case destroys the location.
        assertThat(location("29 Palms, CA")).isEqualTo("29 Palms, CA");
        assertThat(location("500 Oracle Parkway")).isEqualTo("500 Oracle Parkway");
    }

    @Test
    @DisplayName("suite remnants and site codes are removed")
    void secondaryUnitsAndSiteCodesGo() {
        assertThat(location("Ste 140 Middletown, NY")).isEqualTo("Middletown, NY");
        assertThat(location("St Peters, MO (O'Fallon) 0753")).isEqualTo("St Peters, MO (O'Fallon)");
        // "St Peters" is a city, not a street, and must not be mistaken for one.
        assertThat(location("St Peters, MO")).isEqualTo("St Peters, MO");
    }

    @Test
    @DisplayName("a location that is already clean is returned untouched")
    void cleanLocationsArePreserved() {
        // Multi-site and prose forms must not be rewritten -- rejoining would
        // silently reformat strings the source got right.
        String multi = "New York City, NY; San Francisco, CA; Seattle, WA";
        assertThat(location(multi)).isEqualTo(multi);

        String prose = "San Francisco Bay Area or Los Angeles Area";
        assertThat(location(prose)).isEqualTo(prose);

        assertThat(location("Chicago, IL, United States")).isEqualTo("Chicago, IL, United States");
        assertThat(location("Remote")).isEqualTo("Remote");
        assertThat(location("Location not listed")).isEqualTo("Location not listed");
    }

    @Test
    @DisplayName("nothing is invented when the value is missing")
    void missingValuesPassThrough() {
        assertThat(department(null)).isNull();
        assertThat(location(null)).isNull();
        assertThat(department("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("requisition numbers are kept out of the skills array")
    void tagsAreCleaned() {
        // Workday put the id in twice: once as the department, once via the
        // bullet-field passthrough, so the skills index was a list of req numbers.
        assertThat(tags(List.of("R0000448755"))).isEmpty();
        assertThat(tags(List.of("R0000448755", "Engineering", "Remote")))
                .containsExactly("Engineering", "Remote");
        assertThat(tags(List.of("Production", "Hospitality", "Full-time", "Mid-Senior Level")))
                .containsExactly("Production", "Hospitality", "Full-time", "Mid-Senior Level");
    }
}
