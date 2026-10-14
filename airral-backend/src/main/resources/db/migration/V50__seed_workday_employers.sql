-- V50: Seed 42 Workday employers, verified against their live public CxS APIs.
--
-- Workday is where the missing volume is: the six boards already seeded hold ~8,100
-- postings (~1,300 each), and the employers on it -- hospitals, carriers, defence,
-- logistics, banks -- are the non-tech coverage V31 said could not be reached by
-- guessing Greenhouse/Lever/Ashby tokens.
--
-- Probed on 2026-10-14: ~520 guessed tenants were tried; these answered with at
-- least 50 postings, and each was then sampled (12 pages spread across the board)
-- with the same United States location test the sync applies to Workday's list
-- locations. Kept only if at least 100 postings are expected to pass. Estimated US
-- postings behind them: 23,781 (per-board cap 4,000).
-- Workday lists multi-site postings as "N Locations" with no state, which the
-- sync's US test drops; those are not counted here and are not ingested.
--
-- domain and logo_url are left NULL, as in V31: a guessed <name>.com would give the
-- brand enrichment a confidently wrong favicon to cache.
--
-- Target, Salesforce and Workday are already seeded and are not repeated.

WITH companies(name, normalized_name) AS (
    VALUES
        ('T-Mobile', 'tmobile'),
        ('Ochsner Health', 'ochsnerhealth'),
        ('Leidos', 'leidos'),
        ('BrightView', 'brightview'),
        ('Labcorp', 'labcorp'),
        ('PNC', 'pnc'),
        ('CACI', 'caci'),
        ('Thermo Fisher Scientific', 'thermofisherscientific'),
        ('Verizon', 'verizon'),
        ('GDIT', 'gdit'),
        ('KBR', 'kbr'),
        ('Stryker', 'stryker'),
        ('Sysco', 'sysco'),
        ('Washington University in St. Louis', 'washingtonuniversityinstlouis'),
        ('Amgen', 'amgen'),
        ('J.B. Hunt', 'jbhunt'),
        ('Northeastern University', 'northeasternuniversity'),
        ('Terminix', 'terminix'),
        ('Visa', 'visa'),
        ('University of Chicago', 'universityofchicago'),
        ('Ecolab', 'ecolab'),
        ('Cisco', 'cisco'),
        ('Baker Hughes', 'bakerhughes'),
        ('IQVIA', 'iqvia'),
        ('PPG', 'ppg'),
        ('Medtronic', 'medtronic'),
        ('HP', 'hp'),
        ('Cigna', 'cigna'),
        ('Intel', 'intel'),
        ('Centene', 'centene'),
        ('Saia', 'saia'),
        ('Old Dominion Freight Line', 'olddominionfreightline'),
        ('Chewy', 'chewy'),
        ('Meijer', 'meijer'),
        ('Travelers', 'travelers'),
        ('GEICO', 'geico'),
        ('Allstate', 'allstate'),
        ('Eversource', 'eversource'),
        ('Broadridge', 'broadridge'),
        ('Vanguard', 'vanguard'),
        ('Edwards Lifesciences', 'edwardslifesciences'),
        ('Pfizer', 'pfizer')
)
INSERT INTO external_companies (name, normalized_name)
SELECT name, normalized_name FROM companies
ON CONFLICT (normalized_name) DO NOTHING;

WITH sources(normalized_name, source_type, board_token, source_name) AS (
    VALUES
        ('tmobile', 'WORKDAY', 'tmobile.wd1.myworkdayjobs.com|tmobile|External', 'Workday'),
        ('ochsnerhealth', 'WORKDAY', 'ochsner.wd1.myworkdayjobs.com|ochsner|ochsner', 'Workday'),
        ('leidos', 'WORKDAY', 'leidos.wd5.myworkdayjobs.com|leidos|external', 'Workday'),
        ('brightview', 'WORKDAY', 'brightview.wd501.myworkdayjobs.com|brightview|External', 'Workday'),
        ('labcorp', 'WORKDAY', 'labcorp.wd1.myworkdayjobs.com|labcorp|External', 'Workday'),
        ('pnc', 'WORKDAY', 'pnc.wd5.myworkdayjobs.com|pnc|External', 'Workday'),
        ('caci', 'WORKDAY', 'caci.wd1.myworkdayjobs.com|caci|External', 'Workday'),
        ('thermofisherscientific', 'WORKDAY', 'thermofisher.wd5.myworkdayjobs.com|thermofisher|thermofisherCareers', 'Workday'),
        ('verizon', 'WORKDAY', 'verizon.wd12.myworkdayjobs.com|verizon|verizon-careers', 'Workday'),
        ('gdit', 'WORKDAY', 'gdit.wd5.myworkdayjobs.com|gdit|External_Career_Site', 'Workday'),
        ('kbr', 'WORKDAY', 'kbr.wd5.myworkdayjobs.com|kbr|kbr_Careers', 'Workday'),
        ('stryker', 'WORKDAY', 'stryker.wd1.myworkdayjobs.com|stryker|StrykerCareers', 'Workday'),
        ('sysco', 'WORKDAY', 'sysco.wd5.myworkdayjobs.com|sysco|syscoCareers', 'Workday'),
        ('washingtonuniversityinstlouis', 'WORKDAY', 'wustl.wd1.myworkdayjobs.com|wustl|External', 'Workday'),
        ('amgen', 'WORKDAY', 'amgen.wd1.myworkdayjobs.com|amgen|Careers', 'Workday'),
        ('jbhunt', 'WORKDAY', 'jbhunt.wd501.myworkdayjobs.com|jbhunt|Careers', 'Workday'),
        ('northeasternuniversity', 'WORKDAY', 'northeastern.wd1.myworkdayjobs.com|northeastern|Careers', 'Workday'),
        ('terminix', 'WORKDAY', 'terminix.wd1.myworkdayjobs.com|terminix|terminix', 'Workday'),
        ('visa', 'WORKDAY', 'visa.wd5.myworkdayjobs.com|visa|visa', 'Workday'),
        ('universityofchicago', 'WORKDAY', 'uchicago.wd5.myworkdayjobs.com|uchicago|External', 'Workday'),
        ('ecolab', 'WORKDAY', 'ecolab.wd1.myworkdayjobs.com|ecolab|ecolab_External', 'Workday'),
        ('cisco', 'WORKDAY', 'cisco.wd5.myworkdayjobs.com|cisco|cisco_Careers', 'Workday'),
        ('bakerhughes', 'WORKDAY', 'bakerhughes.wd5.myworkdayjobs.com|bakerhughes|bakerhughes', 'Workday'),
        ('iqvia', 'WORKDAY', 'iqvia.wd1.myworkdayjobs.com|iqvia|Iqvia', 'Workday'),
        ('ppg', 'WORKDAY', 'ppg.wd5.myworkdayjobs.com|ppg|ppg_Careers', 'Workday'),
        ('medtronic', 'WORKDAY', 'medtronic.wd1.myworkdayjobs.com|medtronic|medtroniccareers', 'Workday'),
        ('hp', 'WORKDAY', 'hp.wd5.myworkdayjobs.com|hp|ExternalCareerSite', 'Workday'),
        ('cigna', 'WORKDAY', 'cigna.wd5.myworkdayjobs.com|cigna|cignaCareers', 'Workday'),
        ('intel', 'WORKDAY', 'intel.wd1.myworkdayjobs.com|intel|External', 'Workday'),
        ('centene', 'WORKDAY', 'centene.wd5.myworkdayjobs.com|centene|centene_External', 'Workday'),
        ('saia', 'WORKDAY', 'saia.wd1.myworkdayjobs.com|saia|saiaCareers', 'Workday'),
        ('olddominionfreightline', 'WORKDAY', 'odfl.wd1.myworkdayjobs.com|odfl|odfl_Careers', 'Workday'),
        ('chewy', 'WORKDAY', 'chewy.wd5.myworkdayjobs.com|chewy|External', 'Workday'),
        ('meijer', 'WORKDAY', 'meijer.wd5.myworkdayjobs.com|meijer|meijer', 'Workday'),
        ('travelers', 'WORKDAY', 'travelers.wd5.myworkdayjobs.com|travelers|External', 'Workday'),
        ('geico', 'WORKDAY', 'geico.wd1.myworkdayjobs.com|geico|External', 'Workday'),
        ('allstate', 'WORKDAY', 'allstate.wd5.myworkdayjobs.com|allstate|allstate_Careers', 'Workday'),
        ('eversource', 'WORKDAY', 'eversource.wd1.myworkdayjobs.com|eversource|ExternalSite', 'Workday'),
        ('broadridge', 'WORKDAY', 'broadridge.wd5.myworkdayjobs.com|broadridge|Careers', 'Workday'),
        ('vanguard', 'WORKDAY', 'vanguard.wd5.myworkdayjobs.com|vanguard|vanguard_External', 'Workday'),
        ('edwardslifesciences', 'WORKDAY', 'edwards.wd5.myworkdayjobs.com|edwards|edwardsCareers', 'Workday'),
        ('pfizer', 'WORKDAY', 'pfizer.wd1.myworkdayjobs.com|pfizer|pfizerCareers', 'Workday')
)
INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT c.id, s.source_type, s.board_token, s.source_name
FROM sources s
JOIN external_companies c ON c.normalized_name = s.normalized_name
ON CONFLICT (source_type, board_token) DO NOTHING;
