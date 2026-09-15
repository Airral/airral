package com.airral.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one role taxonomy, shared by the onboarding picker and the ranker.
 *
 * <p>It lives in its own class because it was previously two tables that did not
 * know about each other. Onboarding counted its options out of the live corpus
 * with the table below, so a candidate could tick "Warehouse"; the ranker used
 * {@link RoleMatchClassifier}, which has no warehouse family at all. The result
 * was that answering the role question actively demoted the work the candidate
 * asked for -- see {@code RoleTargetMatchingTest}. A list of options the matcher
 * cannot score is not a preference, it is a form field.
 *
 * <p>{@link RoleMatchClassifier} is still used, for the one thing this cannot
 * do: it reads resume skills and separates the IC track from the management
 * track. This class answers a narrower question -- which family a piece of role
 * text belongs to -- and it answers it the same way for a posting title and for
 * a label the candidate picked.
 */
final class RoleFamilyTaxonomy {

    private RoleFamilyTaxonomy() {
    }

    /**
     * The role families onboarding offers, and the title text that lands in each.
     *
     * <p>Measured over the 16,520 active postings on 2026-09-15, the largest
     * families are Retail (13.5%), Software engineer (10.3%), Warehouse (10.2%)
     * and Sales (9.6%). The hardcoded list this replaces had no option at all for
     * warehouse, fulfillment, cashier, driver, cook or housekeeping work -- about
     * a third of the catalogue -- so most candidates had no way to say what they
     * wanted.
     *
     * <p>Order is significant: first match wins, and it is deliberately not
     * alphabetical. The frontline families come first because their titles are
     * full of words the professional families would otherwise claim. "Sales
     * Associate - Building Materials" and "Sales Floor Dept Supervisor" are
     * Lowe's store jobs, not business development, and "Target Security
     * Specialist" is store security, not security engineering. The classifier
     * this borrows its shape from, {@link RoleMatchClassifier}, has the reverse
     * order and puts 15.2% of the corpus into a "Sales" bucket that is almost
     * entirely retail floor staff.
     *
     * <p>Matching is plain substring containment over a normalized title, which
     * is why several entries carry deliberate leading or trailing spaces
     * (" auto ", " tire ", " rn ", " lab "): without them they match inside
     * longer words. The truncated entries ("merchandis", "housekeep",
     * "fabricat") are deliberate too, so one entry covers the -ing and -er forms.
     *
     * <p>Labels are one or two words on purpose, and they double as the search
     * term the first job feed is seeded with. Compound labels measurably break
     * that: the feed seeds a {@code plainto_tsquery}, which ANDs its terms, so
     * "Warehouse &amp; Fulfillment" returns 161 live jobs where "Warehouse"
     * alone returns 513 and "Fulfillment" alone returns 1,233. "Store security"
     * over "Loss prevention" is the same measurement -- 337 against 33.
     *
     * <p>Be clear about how good this is: it resolves 86.4% of live titles. The
     * remaining 13.6% is a long tail whose largest single title is 11 postings,
     * and it does misfile things -- "Forward Deployed Engineer (FDE) -
     * Communications, Media" lands in Marketing on the word "communications".
     * That is why {@link ExternalJobPostingStore.RoleFamilyCatalog} also carries the unclassified count
     * and the page always keeps a free-text box. We show the families we can
     * count, and say plainly how many jobs fit none of them, rather than
     * presenting a tidy list as if it were the whole catalogue.
     */
    static final List<RoleFamilyRule> ROLE_FAMILY_RULES = List.of(
            new RoleFamilyRule("Warehouse", List.of(
                    // "inbound operations" rather than a bare "inbound", and no
                    // "outbound" at all. Measured over the live corpus: the bare
                    // keywords decided 38 postings and not one was warehouse work
                    // -- 13 were "Staff Inbound/Outbound Product Manager", 6 were
                    // inbound and outbound SDRs, and the rest were Target's
                    // "Inbound Operations Team Leader", which the narrowed form
                    // still keeps. The truck-unload titles that do belong here
                    // ("Seasonal: 4am Inbound (Stocking)") carry "stocking" too.
                    "warehouse", "fulfillment", "fulfilment", "distribution center", "stocker",
                    "stocking", "receiver", "inbound operations", "loader", "order picker",
                    "order selector", "packer", "freight", "material handler", "forklift",
                    "cart attendant", "cart associate")),
            new RoleFamilyRule("Driver", List.of(
                    "driver", "cdl", "courier", "delivery associate", "delivery specialist",
                    "transportation")),
            new RoleFamilyRule("Food service", List.of(
                    "barista", "cook", "chef", "food and beverage", "food service", "kitchen",
                    "dishwasher", "bakery", " deli ", "bartender", "restaurant", "starbucks",
                    "cafe", "banquet", "steward")),
            new RoleFamilyRule("Retail", List.of(
                    "cashier", "sales associate", "sales specialist", "retail", "store associate",
                    // "front of store" but deliberately not "front end", after
                    // trying both. Retail is scanned before Software engineer, so
                    // the bare phrase claimed "Front End Web Developer", "Front
                    // End React Developer" and every posting whose only signal was
                    // a Front End department. PRIORITY_RULES cannot rescue those:
                    // it matches exact suffixes, so any intervening word gets
                    // through. The cost is that "Front End Associate" and a bare
                    // "Front End" department now place nowhere, and that is the
                    // better failure -- a posting we could not read is reported as
                    // unplaced, where a software posting filed under Retail is a
                    // wrong answer we would state to a candidate.
                    "merchandis", "team member", "guest advocate",
                    "front of store", "checkout",
                    "general merchandise", "service and engagement", "style consultant",
                    "sales floor", "specialty sales", "dept supervisor", "department supervisor",
                    "beauty", "fitting room")),
            new RoleFamilyRule("Housekeeping", List.of(
                    "custodian", "janitor", "cleaner", "cleaning", "housekeep", "houseperson",
                    "groundskeep", "landscap", "lawn care", " porter ", "room attendant")),
            new RoleFamilyRule("Store security", List.of(
                    "security specialist", "security officer", "security guard", "loss prevention",
                    "asset protection", "assets protection", "safety specialist")),
            new RoleFamilyRule("Automotive", List.of(
                    // " mechanic " is padded because the unpadded form is a
                    // substring of "mechanical": measured over the live corpus it
                    // swept 86 mechanical-engineering postings into Automotive
                    // ("Mechanical Engineer", "Senior Mechanical Design Engineer",
                    // "Mechanical Project Manager - Data Center Construction")
                    // against only 30 real mechanic jobs. All 30 keep their family
                    // with the padding; the engineering titles go to Construction
                    // or to unplaced, and unplaced is the honest answer for them.
                    "automotive", " auto ", "auto technician", "detailer", " mechanic ", " tire ",
                    "collision", "body shop", "lot attendant", "parts associate")),
            new RoleFamilyRule("Maintenance", List.of(
                    "electrician", "plumber", "hvac", "welder", "carpenter", "machinist",
                    "millwright", "pipefitter", "installer", "maintenance technician",
                    "facilities technician", "field technician", "service technician",
                    "heat pump")),
            new RoleFamilyRule("Healthcare", List.of(
                    "nurse", "nursing", " rn ", "lpn", "cna", "caregiver", "patient", "clinical",
                    "physician", "medical assistant", "pharmacist", "dental", "therapist",
                    "phlebotom", "veterinar", "massage")),
            new RoleFamilyRule("Manufacturing", List.of(
                    "manufacturing", "production associate", "production operator", "assembler",
                    "machine operator", " plant ", "fabricat", "press operator",
                    "quality inspector", "inspector")),
            new RoleFamilyRule("Construction", List.of(
                    "construction", "surveyor", "quantity survey", "estimator", "superintendent",
                    "field service", "rigger")),
            new RoleFamilyRule("Software engineer", List.of(
                    "software engineer", "software developer", "full stack", "fullstack",
                    "frontend", "front end engineer", "backend", "back end engineer",
                    "web developer", "application developer", "java developer", "python developer",
                    "platform engineer", "site reliability", " sre ", "devops", "cloud engineer",
                    "mobile engineer", "android engineer", "ios engineer", "firmware",
                    "embedded engineer", "qa engineer", "test engineer",
                    "member of technical staff", "engineering manager", "software architect",
                    "solutions architect", "enterprise architect", "developer")),
            new RoleFamilyRule("Data science", List.of(
                    "data engineer", "data scientist", "machine learning", "ml engineer",
                    "ai engineer", "applied scientist", "research scientist", "analytics engineer",
                    "data platform", "data infrastructure")),
            new RoleFamilyRule("Analytics", List.of(
                    "data analyst", "business analyst", "analytics", "business intelligence",
                    "reporting analyst", "insights analyst")),
            new RoleFamilyRule("Security engineer", List.of(
                    "security engineer", "application security", "cloud security", "cybersecurity",
                    "infosec", "security architect")),
            new RoleFamilyRule("IT support", List.of(
                    "help desk", "helpdesk", "it support", "desktop support",
                    "system administrator", "systems administrator", "network engineer",
                    "network administrator", "salesforce administrator")),
            new RoleFamilyRule("Product manager", List.of(
                    "product manager", "product owner", "product lead", "technical product")),
            new RoleFamilyRule("Design", List.of(
                    "designer", "product design", "ux ", "ui ux", "user experience",
                    "user research", "creative director")),
            new RoleFamilyRule("Project manager", List.of(
                    "project manager", "program manager", "scrum master", "project coordinator",
                    "program coordinator", "project management")),
            new RoleFamilyRule("Sales", List.of(
                    "account executive", "business development", "sales manager",
                    "sales representative", "sales engineer", "account manager",
                    "solutions consultant", "solution consultant", "inside sales", "outside sales",
                    "sales director", "seller", "sales ")),
            new RoleFamilyRule("Customer service", List.of(
                    "customer success", "customer support", "customer service",
                    "customer experience", "client success", "support specialist", "call center",
                    "contact center", "technical support", "advocate")),
            new RoleFamilyRule("Marketing", List.of(
                    "marketing", "brand ", "communications", "demand generation",
                    "public relations", "social media", " seo ", "community manager")),
            new RoleFamilyRule("Finance", List.of(
                    "finance", "financial", "accounting", "accountant", "controller", "payroll",
                    "treasury", "audit", " tax ")),
            new RoleFamilyRule("Recruiting", List.of(
                    "recruiter", "recruiting", "talent acquisition", "people partner",
                    "human resource", "hr business", "people operations",
                    "compensation and benefits")),
            new RoleFamilyRule("Legal", List.of(
                    "legal", "counsel", "paralegal", "compliance", "regulatory", "privacy",
                    "risk manager")),
            new RoleFamilyRule("Operations", List.of(
                    "operations", "logistics", "supply chain", "procurement", "planner",
                    "scheduling", "dispatch", "inventory")),
            new RoleFamilyRule("Administrative", List.of(
                    "executive assistant", "administrative assistant", "office manager",
                    "receptionist", "front desk", "data entry", "office coordinator",
                    "staffing admin")),
            new RoleFamilyRule("Laboratory", List.of(
                    "chemist", "microbiolog", "laboratory", " lab ", "lab technician", "biolog",
                    "toxicolog", "petroleum inspector")),
            new RoleFamilyRule("Teaching", List.of(
                    "teacher", "tutor", "instructor", "childcare", "child care", "preschool",
                    "educator", "camp counselor")));

    /**
     * Families a candidate who asked for one would plausibly take the other.
     *
     * <p>Declared one way and made symmetric below, so the table cannot say that
     * Warehouse is near Driver while Driver is far from Warehouse. A test asserts
     * the symmetry rather than trusting the declaration.
     *
     * <p>These are deliberately narrow. Adjacency lifts a posting above the
     * "outside your target titles" line, which is a statement to the candidate
     * that we think the job is worth their time -- so the bar is shared work,
     * shared setting or a route people actually walk, not merely the same
     * industry. Warehouse to Driver is the same building and the same shift
     * patterns; Retail to Customer service is the same job indoors or on a
     * phone. Retail to Finance is not on the list even though both happen at a
     * bank, and Teaching has no neighbours at all because the licensing makes
     * every apparent neighbour a career change rather than a near miss.
     *
     * <p>Retail and Sales are deliberately NOT neighbours, reversing an earlier
     * call. The reasoning for pairing them was that a store "Sales Associate"
     * and an "Account Executive" share a word, and that a retail candidate might
     * want sales floor work -- but sales floor work is classified as Retail by
     * the frontline-first ordering, so what is left in Sales is enterprise B2B.
     * Measured on the live feed, the pairing put "Mid-Market Account Executive,
     * Commerce" and "Sr. Client Partner, Amazon" at the top of a Retail
     * candidate's results labelled "Near your target: Sales". Those are not
     * near that candidate's target by any reading.
     */
    /**
     * Entry-level hourly work with no licence or degree gate, mutually adjacent.
     *
     * <p>Declared as a cluster rather than fifteen pairs because that is what it
     * is: one labour market whose members people move between freely, often at
     * the same employer. Leaving it pairwise is what produced the gap this fixes
     * -- Retail had four neighbours while Warehouse, the second largest family in
     * the catalogue, had only Driver, Manufacturing and Operations. A candidate
     * who picked Warehouse had Cashier, Line Cook, Custodian and Security
     * Officer hidden from them with "Role is outside your target titles" on
     * each, which is not true of that market.
     *
     * <p>Driver is not in the cluster: the CDL entries gate much of that family
     * behind a licence, so it stays pairwise with Warehouse and Operations.
     * Manufacturing stays out for the same reason -- certification and shift
     * work make it adjacent to Warehouse and Maintenance but not to a cafe.
     */
    private static final List<String> FRONTLINE_CLUSTER = List.of(
            "Warehouse", "Retail", "Food service", "Housekeeping", "Store security");

    private static final List<List<String>> ADJACENT_PAIRS = List.of(
            List.of("Warehouse", "Driver"),
            List.of("Warehouse", "Manufacturing"),
            List.of("Warehouse", "Operations"),
            List.of("Driver", "Operations"),
            List.of("Retail", "Customer service"),
            List.of("Housekeeping", "Maintenance"),
            List.of("Manufacturing", "Maintenance"),
            List.of("Manufacturing", "Laboratory"),
            List.of("Maintenance", "Construction"),
            List.of("Maintenance", "Automotive"),
            List.of("Healthcare", "Laboratory"),
            List.of("Software engineer", "Data science"),
            List.of("Software engineer", "Security engineer"),
            List.of("Software engineer", "IT support"),
            List.of("Data science", "Analytics"),
            List.of("Security engineer", "IT support"),
            List.of("Analytics", "Finance"),
            List.of("Analytics", "Operations"),
            List.of("Product manager", "Project manager"),
            List.of("Product manager", "Design"),
            List.of("Design", "Marketing"),
            List.of("Project manager", "Operations"),
            List.of("Sales", "Customer service"),
            List.of("Sales", "Marketing"),
            List.of("Customer service", "Administrative"),
            List.of("Finance", "Legal"),
            List.of("Recruiting", "Administrative"));

    private static final Map<String, Set<String>> ADJACENT_FAMILIES = buildAdjacency();

    private static Map<String, Set<String>> buildAdjacency() {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (List<String> pair : declaredPairs()) {
            adjacency.computeIfAbsent(pair.get(0), ignored -> new LinkedHashSet<>()).add(pair.get(1));
            adjacency.computeIfAbsent(pair.get(1), ignored -> new LinkedHashSet<>()).add(pair.get(0));
        }
        adjacency.replaceAll((family, neighbours) -> Collections.unmodifiableSet(neighbours));
        return Collections.unmodifiableMap(adjacency);
    }

    /**
     * Every adjacency as it was written down, before it is made symmetric.
     *
     * <p>Exposed because the tests that check this table have to read the
     * declaration, not the built map. Iterating the built map proves nothing: it
     * inserts both directions unconditionally, so a symmetry assertion over it
     * can never fail, and a misspelled family name simply never matches any job
     * and is invisible. Two earlier tests did exactly that and passed on a table
     * containing whatever was typed.
     */
    static List<List<String>> declaredPairs() {
        List<List<String>> pairs = new ArrayList<>(ADJACENT_PAIRS);
        for (int i = 0; i < FRONTLINE_CLUSTER.size(); i++) {
            for (int j = i + 1; j < FRONTLINE_CLUSTER.size(); j++) {
                pairs.add(List.of(FRONTLINE_CLUSTER.get(i), FRONTLINE_CLUSTER.get(j)));
            }
        }
        return List.copyOf(pairs);
    }

    /**
     * The family a posting belongs to, or null when none of them fit.
     *
     * <p>Null is the point. A title we cannot place is reported as unplaced
     * rather than pushed into the nearest bucket, because the count next to a
     * family label is a claim about how many jobs a candidate would find there,
     * and because the ranker now turns this answer into a sentence shown to the
     * candidate. "Outside your target titles" about a job we simply could not
     * read is a false statement, not a conservative one.
     *
     * <p>Department is only a fallback, and only when the title says nothing.
     * Measured on the live corpus it lifts coverage from 78.3% to 86.4% and is
     * mostly right ("Shift Lead" under "112 Order Fulfillment" is warehouse
     * work), but it is the weaker signal: "Sr. Forward Deployed Engineer" under
     * "Professional Services Operations" becomes Operations, which is not what
     * that job is. Reading it before the title would make that worse, not better.
     */
    static String classify(String title, String department) {
        String fromTitle = match(normalize(title));
        if (fromTitle != null) {
            return fromTitle;
        }
        return match(normalize(department));
    }

    /**
     * Phrases checked before the family table, where a frontline keyword is a
     * prefix of a professional title.
     *
     * <p>The family table is ordered frontline-first on purpose, and first match
     * wins, which is what keeps "Sales Associate - Building Materials" out of
     * business development. The cost is that a short frontline keyword can be a
     * prefix of a longer professional title: Retail's "front end" is a prefix of
     * "Front End Engineer". Only the longer, unambiguous form belongs here --
     * this list is not a second taxonomy and must not grow into one.
     *
     * <p>The second group is the mirror case: an unambiguous professional title
     * carrying a domain word that an earlier family claims. "Staff Software
     * Engineer, Clinical Fit" was Healthcare, because Healthcare is scanned
     * first and holds "clinical", and it arrived at the top of a Healthcare
     * candidate's feed labelled "Role fit: Healthcare".
     *
     * <p>Measured over 1,384 distinct live (title, department) pairs, these
     * entries move 3 postings and change coverage not at all: one each out of
     * Healthcare, Manufacturing and Warehouse into Software engineer and Data
     * science. "product manager", "project manager" and "program manager" were
     * measured for this list and left out -- they moved 10 postings, nine of
     * them titles like "Senior Project Manager - Data Center Construction" out
     * of Construction, which takes real work away from a Construction candidate
     * to no one's benefit.
     *
     * <p>Reordering the whole table longest-keyword-first was tried and rejected:
     * it fixes this case and breaks others, because a short keyword is often the
     * right answer. "Warehouse Operations Manager" would go to Operations on
     * "operations" (10 characters) over Warehouse on "warehouse" (9), and
     * "Retail Customer Service Associate" would leave Retail for Customer
     * service. Both are wrong, and both are silent.
     */
    private static final List<RoleFamilyRule> PRIORITY_RULES = List.of(
            new RoleFamilyRule("Software engineer", List.of(
                    "front end engineer", "front end developer", "front end architect",
                    "front end lead", "back end engineer", "back end developer",
                    "back end architect", "back end lead",
                    "software engineer", "software developer")),
            new RoleFamilyRule("Data science", List.of(
                    "data scientist", "machine learning engineer")));

    private static final Map<String, String> LABELS_BY_NORMALIZED_FORM = buildLabelIndex();

    private static Map<String, String> buildLabelIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (RoleFamilyRule rule : ROLE_FAMILY_RULES) {
            index.put(normalize(rule.label()), rule.label());
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * The family a single piece of role text belongs to, or null.
     *
     * <p>Used for what the candidate typed or ticked, and the label is checked
     * before the keywords on purpose. Seven labels do not contain any of their
     * own family's keywords -- "Store security" has "security officer" and
     * "loss prevention" but not the label; "Healthcare" has "nurse" and
     * "clinical"; "Design" has "designer"; also "Maintenance", "Data science",
     * "Administrative" and "Teaching". Without this, ticking any of those
     * offered options produced a preference the ranker could not read, which
     * is the same silent inertness this whole change is fixing, one layer down.
     *
     * <p>Fixing it by adding the bare labels as keywords instead was rejected:
     * those keywords would also match posting titles, so "Healthcare Data
     * Analyst" would move from Analytics to Healthcare and the counts shown
     * during onboarding would shift as a side effect of a candidate-side fix.
     * A label that came from our own list identifies its family by definition;
     * a posting that merely contains the word does not.
     *
     * <p>Free text still falls through to the keyword table, so "warehouse
     * picker" is read the way a posting title would be.
     */
    static String classifyTerm(String roleText) {
        String normalized = normalize(roleText);
        String exactLabel = LABELS_BY_NORMALIZED_FORM.get(normalized);
        if (exactLabel != null) {
            return exactLabel;
        }
        return match(normalized);
    }

    /**
     * The offered label this text exactly is, or null if it is anything else.
     *
     * <p>This is the identity check, and it is deliberately not
     * {@link #classifyTerm}. A label the candidate ticked from our own list says
     * what family they want by definition. A family we guessed from free text
     * they typed is an inference off one substring hit, and the table is a
     * 297-keyword first-match-wins scan that misfiles things: "Privacy Engineer"
     * hits Legal on "privacy", "Technical Support Engineer" hits Customer
     * service on "technical support", "Driving Instructor" hits Teaching on
     * "instructor". Those inferences are fine for ranking something up. They
     * must never hide a job, which is why the filter reads this and not that.
     */
    static String pickedLabel(String roleText) {
        return LABELS_BY_NORMALIZED_FORM.get(normalize(roleText));
    }

    /** The offered labels a candidate actually picked, first mention first. */
    static Set<String> pickedLabels(Iterable<String> roleTexts) {
        Set<String> labels = new LinkedHashSet<>();
        if (roleTexts == null) {
            return labels;
        }
        for (String roleText : roleTexts) {
            String label = pickedLabel(roleText);
            if (label != null) {
                labels.add(label);
            }
        }
        return labels;
    }

    /** Families behind a candidate's stated roles, first mention first. */
    static Set<String> classifyTerms(Iterable<String> roleTexts) {
        Set<String> families = new LinkedHashSet<>();
        if (roleTexts == null) {
            return families;
        }
        for (String roleText : roleTexts) {
            String family = classifyTerm(roleText);
            if (family != null) {
                families.add(family);
            }
        }
        return families;
    }

    /**
     * Extra text-search seeds for a picked family, chosen by measured yield.
     *
     * <p>A picked label is already seeded into retrieval on its own, and that is
     * not enough. The label is a word people use to describe a kind of work, not
     * a word that appears in its job titles. Measured against the live API on
     * 2026-09-15, searching "maintenance" returned 50 postings of which
     * <em>zero</em> were maintenance work -- finance analysts, an HR director, a
     * sales engineer, all matched on the word appearing in a description. A
     * candidate who picked Maintenance saw a feed with nothing for them in it.
     *
     * <p>An earlier pass measured only whether each label returned results at
     * all, found every one of the 29 returned over 100, and concluded retrieval
     * needed no change. That counted hits, not relevant hits, and was wrong.
     *
     * <p>Each seed below was measured by classifying its first 50 results and
     * counting how many land in the family it is meant to retrieve. The figure
     * in each comment is that count. Selection is by absolute on-family yield
     * rather than by percentage, because a seed cannot mislead anyone: the feed
     * filter removes off-family postings anyway, so a seed that brings 18 real
     * jobs among 50 is worth more than one that brings 5 out of 7.
     *
     * <p>Families absent from this table are absent on purpose. No seed found
     * meaningful volume for IT support (29 live postings), Teaching (5) or
     * Recruiting, because the catalogue genuinely holds almost none of that
     * work. Housekeeping is absent for a different reason: its best seed was
     * the bare word "housekeeping", which is its own label and therefore
     * already searched, and its next candidates returned 8, 3 and 2 postings.
     * A seed cannot retrieve what is not there, and onboarding shows the count
     * next to the label before the candidate picks it.
     */
    private static final Map<String, List<String>> RETRIEVAL_SEEDS = Map.ofEntries(
            // 50 and 49 on-family of 50 results each.
            Map.entry("Warehouse", List.of("order picker", "forklift")),
            // 50 and 28.
            Map.entry("Driver", List.of("cdl driver", "truck driver")),
            // 36 and 36. The label "retail" itself returned car detailers,
            // recruiters and enterprise account executives.
            Map.entry("Retail", List.of("sales associate", "cashier")),
            // 50 and 18.
            Map.entry("Food service", List.of("barista", "line cook")),
            // 44 and 43.
            Map.entry("Automotive", List.of("automotive technician", "tire technician")),
            // 35 and 21.
            Map.entry("Laboratory", List.of("laboratory technician", "chemist")),
            // 34 and 23.
            Map.entry("Store security", List.of("security specialist", "loss prevention")),
            // 21 and 14, together most of the 73 postings this family holds.
            Map.entry("Maintenance", List.of("hvac", "maintenance technician")),
            // 19 and 5. Small family, 45 live postings.
            Map.entry("Healthcare", List.of("clinical", "medical assistant")),
            // 18 on-family of 50. "manufacturing" measured 16 but is the label
            // itself, which retrieval already searches, so it is not repeated
            // here; a test asserts no seed equals its own label.
            Map.entry("Manufacturing", List.of("assembler")),
            // 23 and 8.
            Map.entry("Administrative", List.of("front desk", "administrative assistant")));

    /** Measured text-search seeds that retrieve this family, or empty. */
    static List<String> retrievalSeeds(String family) {
        if (family == null) {
            return List.of();
        }
        return RETRIEVAL_SEEDS.getOrDefault(family, List.of());
    }

    /** True when a candidate asking for {@code family} would plausibly take {@code other}. */
    static boolean adjacent(String family, String other) {
        if (family == null || other == null) {
            return false;
        }
        return ADJACENT_FAMILIES.getOrDefault(family, Set.of()).contains(other);
    }

    /** Every declared label, in table order. Onboarding order is by job count instead. */
    static List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (RoleFamilyRule rule : ROLE_FAMILY_RULES) {
            labels.add(rule.label());
        }
        return List.copyOf(labels);
    }

    private static String match(String normalizedText) {
        if (normalizedText.isBlank()) {
            return null;
        }

        for (RoleFamilyRule rule : PRIORITY_RULES) {
            for (String keyword : rule.keywords()) {
                if (normalizedText.contains(keyword)) {
                    return rule.label();
                }
            }
        }

        for (RoleFamilyRule rule : ROLE_FAMILY_RULES) {
            for (String keyword : rule.keywords()) {
                if (normalizedText.contains(keyword)) {
                    return rule.label();
                }
            }
        }
        return null;
    }

    /**
     * Lowercase, strip punctuation to single spaces, and pad with one space.
     *
     * <p>The padding is what lets a keyword written as " auto " behave like a
     * whole word even at the very start or end of a title: "Auto Technician"
     * normalizes to " auto technician " and matches, while "Automation Engineer"
     * normalizes to " automation engineer " and does not. Keywords in
     * {@link #ROLE_FAMILY_RULES} are written already in this form; the test suite
     * asserts that, because a keyword that cannot survive its own normalization
     * matches nothing and fails silently.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return " ";
        }

        String normalized = value.toLowerCase(Locale.US)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isEmpty() ? " " : " " + normalized + " ";
    }

    /** One family label and the normalized title fragments that land in it. */
    record RoleFamilyRule(String label, List<String> keywords) {
    }
}
