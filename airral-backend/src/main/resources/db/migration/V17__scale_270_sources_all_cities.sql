-- V17: Scale to 270+ job sources across all major US metros, YC startups, and speed-run companies
-- All sources use public ATS job board APIs (Greenhouse, Lever, Ashby, SmartRecruiters)
-- NOTE: Each company/source is inserted individually to avoid duplicate-within-batch errors

-- ─── HELPER: Insert companies one by one ─────────────────────────────────────
-- Using DO blocks with individual inserts to avoid ON CONFLICT batch issues

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('Stripe', 'stripe', 'stripe.com'),
            ('Airbnb', 'airbnb', 'airbnb.com'),
            ('DoorDash', 'doordash', 'doordash.com'),
            ('Figma', 'figma', 'figma.com'),
            ('Notion', 'notion', 'notion.so'),
            ('Rippling', 'rippling', 'rippling.com'),
            ('Scale AI', 'scale-ai', 'scale.com'),
            ('Anthropic', 'anthropic', 'anthropic.com'),
            ('OpenAI', 'openai', 'openai.com'),
            ('Instacart', 'instacart', 'instacart.com'),
            ('Pinterest', 'pinterest', 'pinterest.com'),
            ('Discord', 'discord', 'discord.com'),
            ('Dropbox', 'dropbox', 'dropbox.com'),
            ('Flexport', 'flexport', 'flexport.com'),
            ('Gusto', 'gusto', 'gusto.com'),
            ('Faire', 'faire', 'faire.com'),
            ('Watershed', 'watershed', 'watershed.com'),
            ('Airtable', 'airtable', 'airtable.com'),
            ('Retool', 'retool', 'retool.com'),
            ('Navan', 'navan', 'navan.com'),
            ('Zip', 'zip-co', 'ziphq.com'),
            ('Persona', 'persona', 'withpersona.com'),
            ('Mercury', 'mercury', 'mercury.com'),
            ('Vanta', 'vanta', 'vanta.com'),
            ('Webflow', 'webflow', 'webflow.com'),
            ('Assembled', 'assembled', 'assembled.com'),
            ('Drata', 'drata', 'drata.com'),
            ('Bloomberg', 'bloomberg', 'bloomberg.com'),
            ('Two Sigma', 'two-sigma', 'twosigma.com'),
            ('Squarespace', 'squarespace', 'squarespace.com'),
            ('MongoDB', 'mongodb', 'mongodb.com'),
            ('Justworks', 'justworks', 'justworks.com'),
            ('Cockroach Labs', 'cockroach-labs', 'cockroachlabs.com'),
            ('Ramp', 'ramp', 'ramp.com'),
            ('Datadog', 'datadog', 'datadoghq.com'),
            ('Oscar Health', 'oscar-health', 'hioscar.com'),
            ('Noom', 'noom', 'noom.com'),
            ('Spring Health', 'spring-health', 'springhealth.com'),
            ('AlphaSense', 'alphasense', 'alpha-sense.com'),
            ('Peloton', 'peloton', 'onepeloton.com'),
            ('Chime', 'chime', 'chime.com'),
            ('Yext', 'yext', 'yext.com'),
            ('Zillow', 'zillow', 'zillow.com'),
            ('Redfin', 'redfin', 'redfin.com'),
            ('Outreach', 'outreach', 'outreach.io'),
            ('Highspot', 'highspot', 'highspot.com'),
            ('Samsara', 'samsara', 'samsara.com'),
            ('CrowdStrike', 'crowdstrike', 'crowdstrike.com'),
            ('WP Engine', 'wp-engine', 'wpengine.com'),
            ('Q2', 'q2', 'q2.com'),
            ('Indeed', 'indeed', 'indeed.com'),
            ('Procore', 'procore', 'procore.com'),
            ('BigCommerce', 'bigcommerce', 'bigcommerce.com'),
            ('SailPoint', 'sailpoint', 'sailpoint.com'),
            ('Match Group', 'match-group', 'matchgroup.com'),
            ('Grubhub', 'grubhub', 'grubhub.com'),
            ('Tempus', 'tempus', 'tempus.com'),
            ('Avant', 'avant', 'avant.com'),
            ('Sprout Social', 'sprout-social', 'sproutsocial.com'),
            ('Groupon', 'groupon', 'groupon.com'),
            ('G2', 'g2', 'g2.com'),
            ('ActiveCampaign', 'activecampaign', 'activecampaign.com'),
            ('HubSpot', 'hubspot', 'hubspot.com'),
            ('Wayfair', 'wayfair', 'wayfair.com'),
            ('Rapid7', 'rapid7', 'rapid7.com'),
            ('Toast', 'toast-inc', 'toasttab.com'),
            ('DataRobot', 'datarobot', 'datarobot.com'),
            ('Klaviyo', 'klaviyo', 'klaviyo.com'),
            ('Snyk', 'snyk', 'snyk.io'),
            ('Formlabs', 'formlabs', 'formlabs.com'),
            ('Guild Education', 'guild-education', 'guildeducation.com'),
            ('Ibotta', 'ibotta', 'ibotta.com'),
            ('Ping Identity', 'ping-identity', 'pingidentity.com'),
            ('Wiz', 'wiz', 'wiz.io'),
            ('Iterable', 'iterable', 'iterable.com'),
            ('Calendly', 'calendly', 'calendly.com'),
            ('Greenlight', 'greenlight', 'greenlight.com'),
            ('OneTrust', 'onetrust', 'onetrust.com'),
            ('Palantir', 'palantir', 'palantir.com'),
            ('Shield AI', 'shield-ai', 'shield.ai'),
            ('Anduril', 'anduril', 'anduril.com'),
            ('Appian', 'appian', 'appian.com'),
            ('Epic Games', 'epic-games', 'epicgames.com'),
            ('Pendo', 'pendo', 'pendo.io'),
            ('Qualtrics', 'qualtrics', 'qualtrics.com'),
            ('Lucid Software', 'lucid-software', 'lucid.co'),
            ('Podium', 'podium', 'podium.com'),
            ('Domo', 'domo', 'domo.com'),
            ('Carvana', 'carvana', 'carvana.com'),
            ('Axon', 'axon', 'axon.com'),
            ('GoDaddy', 'godaddy', 'godaddy.com'),
            ('Hims & Hers', 'hims-hers', 'forhims.com'),
            ('Included Health', 'included-health', 'includedhealth.com'),
            ('Lyra Health', 'lyra-health', 'lyrahealth.com'),
            ('Devoted Health', 'devoted-health', 'devoted.com'),
            ('Cityblock Health', 'cityblock', 'cityblock.com'),
            ('Headway', 'headway', 'headway.co'),
            ('Garner Health', 'garner-health', 'getgarner.com'),
            ('Intuit', 'intuit', 'intuit.com'),
            ('ServiceNow', 'servicenow', 'servicenow.com'),
            ('GitLab', 'gitlab', 'gitlab.com'),
            ('Zapier', 'zapier', 'zapier.com'),
            ('Automattic', 'automattic', 'automattic.com'),
            ('Deel', 'deel', 'deel.com'),
            ('Vercel', 'vercel', 'vercel.com'),
            ('Linear', 'linear', 'linear.app'),
            ('PostHog', 'posthog', 'posthog.com'),
            ('Supabase', 'supabase', 'supabase.com'),
            ('Resend', 'resend', 'resend.com'),
            ('Capital One', 'capital-one', 'capitalone.com'),
            ('Salesforce', 'salesforce', 'salesforce.com'),
            ('Block', 'block', 'block.xyz'),
            ('Coinbase', 'coinbase', 'coinbase.com'),
            ('Plaid', 'plaid', 'plaid.com'),
            ('Brex', 'brex', 'brex.com'),
            ('Cloudflare', 'cloudflare', 'cloudflare.com'),
            ('Twilio', 'twilio', 'twilio.com'),
            ('Okta', 'okta', 'okta.com'),
            ('Confluent', 'confluent', 'confluent.io'),
            ('HashiCorp', 'hashicorp', 'hashicorp.com'),
            ('Elastic', 'elastic', 'elastic.co'),
            ('Snowflake', 'snowflake', 'snowflake.com'),
            ('Databricks', 'databricks', 'databricks.com'),
            ('Fivetran', 'fivetran', 'fivetran.com'),
            ('dbt Labs', 'dbt-labs', 'getdbt.com'),
            ('Amplitude', 'amplitude', 'amplitude.com'),
            ('LaunchDarkly', 'launchdarkly', 'launchdarkly.com'),
            ('Sentry', 'sentry', 'sentry.io'),
            ('PagerDuty', 'pagerduty', 'pagerduty.com'),
            ('Contentful', 'contentful', 'contentful.com'),
            ('Miro', 'miro', 'miro.com'),
            ('Canva', 'canva', 'canva.com'),
            ('Loom', 'loom', 'loom.com'),
            ('1Password', 'onepassword', '1password.com'),
            ('Grafana Labs', 'grafana-labs', 'grafana.com'),
            ('Robinhood', 'robinhood', 'robinhood.com'),
            ('Affirm', 'affirm', 'affirm.com'),
            ('SoFi', 'sofi', 'sofi.com'),
            ('Marqeta', 'marqeta', 'marqeta.com'),
            ('Column', 'column', 'column.com'),
            ('Cohere', 'cohere', 'cohere.com'),
            ('Perplexity', 'perplexity', 'perplexity.ai'),
            ('Hugging Face', 'hugging-face', 'huggingface.co'),
            ('Weights & Biases', 'wandb', 'wandb.ai'),
            ('Labelbox', 'labelbox', 'labelbox.com'),
            ('Deepgram', 'deepgram', 'deepgram.com'),
            ('Jasper', 'jasper', 'jasper.ai'),
            ('Writer', 'writer', 'writer.com'),
            ('project44', 'project44', 'project44.com'),
            ('FourKites', 'fourkites', 'fourkites.com'),
            ('Shippo', 'shippo', 'goshippo.com'),
            ('Veeva Systems', 'veeva', 'veeva.com'),
            ('Doximity', 'doximity', 'doximity.com'),
            ('Flatiron Health', 'flatiron-health', 'flatiron.com'),
            ('Ro', 'ro', 'ro.co'),
            ('Zocdoc', 'zocdoc', 'zocdoc.com'),
            ('Duolingo', 'duolingo', 'duolingo.com'),
            ('Coursera', 'coursera', 'coursera.org'),
            ('Handshake', 'handshake', 'joinhandshake.com'),
            ('Khan Academy', 'khan-academy', 'khanacademy.org'),
            ('Hightouch', 'hightouch', 'hightouch.com'),
            ('Baseten', 'baseten', 'baseten.co'),
            ('Mintlify', 'mintlify', 'mintlify.com'),
            ('Orb', 'orb', 'withorb.com'),
            ('Knock', 'knock', 'knock.app'),
            ('Tinybird', 'tinybird', 'tinybird.co'),
            ('Modal', 'modal', 'modal.com'),
            ('Harvey', 'harvey', 'harvey.ai'),
            ('Glean', 'glean', 'glean.com'),
            ('Cognition', 'cognition', 'cognition.ai'),
            ('Sierra', 'sierra', 'sierra.ai'),
            ('Clay', 'clay', 'clay.com'),
            ('Hume AI', 'hume-ai', 'hume.ai'),
            ('Cerebras', 'cerebras', 'cerebras.net'),
            ('Together AI', 'together-ai', 'together.ai'),
            ('Groq', 'groq', 'groq.com'),
            ('Anyscale', 'anyscale', 'anyscale.com'),
            ('Airbyte', 'airbyte', 'airbyte.com'),
            ('Neon', 'neon', 'neon.tech'),
            ('Clerk', 'clerk', 'clerk.com'),
            ('WorkOS', 'workos', 'workos.com'),
            ('Stytch', 'stytch', 'stytch.com'),
            ('Render', 'render', 'render.com'),
            ('Sardine', 'sardine', 'sardine.ai'),
            ('Unit', 'unit', 'unit.co'),
            ('Moov', 'moov', 'moov.io'),
            ('Lithic', 'lithic', 'lithic.com'),
            ('Levels', 'levels', 'levels.fyi'),
            ('Cursor', 'cursor', 'cursor.com'),
            ('Augment', 'augment', 'augmentcode.com'),
            ('Codeium', 'codeium', 'codeium.com'),
            ('Tabnine', 'tabnine', 'tabnine.com'),
            ('Replit', 'replit', 'replit.com'),
            ('Adept', 'adept', 'adept.ai'),
            ('Character AI', 'character-ai', 'character.ai'),
            ('Runway', 'runway', 'runwayml.com'),
            ('ElevenLabs', 'elevenlabs', 'elevenlabs.io'),
            ('Synthesia', 'synthesia', 'synthesia.io'),
            ('Descript', 'descript', 'descript.com'),
            ('Pika', 'pika', 'pika.art'),
            ('Stability AI', 'stability-ai', 'stability.ai'),
            ('Warp', 'warp', 'warp.dev'),
            ('Zed', 'zed', 'zed.dev'),
            ('Raycast', 'raycast', 'raycast.com'),
            ('Dagger', 'dagger', 'dagger.io'),
            ('Pulumi', 'pulumi', 'pulumi.com'),
            ('Temporal', 'temporal', 'temporal.io'),
            ('Prefect', 'prefect', 'prefect.io'),
            ('Dagster', 'dagster', 'dagster.io'),
            ('MotherDuck', 'motherduck', 'motherduck.com'),
            ('Pinecone', 'pinecone', 'pinecone.io'),
            ('Weaviate', 'weaviate', 'weaviate.io'),
            ('Alchemy', 'alchemy', 'alchemy.com'),
            ('Phantom', 'phantom', 'phantom.app'),
            ('Privy', 'privy', 'privy.io'),
            ('Dynamic', 'dynamic', 'dynamic.xyz'),
            ('Pylon', 'pylon', 'usepylon.com'),
            ('Fern', 'fern', 'buildwithfern.com'),
            ('Stainless', 'stainless', 'stainlessapi.com'),
            ('Depot', 'depot', 'depot.dev'),
            ('Inngest', 'inngest', 'inngest.com'),
            ('Trigger.dev', 'trigger-dev', 'trigger.dev'),
            ('Nango', 'nango', 'nango.dev'),
            ('Loops', 'loops', 'loops.so'),
            ('E2B', 'e2b', 'e2b.dev'),
            ('Cartesia', 'cartesia', 'cartesia.ai'),
            ('Luma AI', 'luma-ai', 'lumalabs.ai'),
            ('Moveworks', 'moveworks', 'moveworks.com'),
            ('PlanetScale', 'planetscale', 'planetscale.com'),
            ('Turso', 'turso', 'turso.tech'),
            ('Convoy', 'convoy', 'convoy.com'),
            ('Unriddle', 'unriddle', 'unriddle.ai'),
            ('Lantern', 'lantern', 'withlantern.com'),
            ('Truewind', 'truewind', 'truewind.ai'),
            ('Svix', 'svix', 'svix.com'),
            ('Braintrust', 'braintrust-ai', 'braintrustdata.com')
        ) AS t(name, normalized_name, domain)
    LOOP
        INSERT INTO external_companies (name, normalized_name, domain)
        VALUES (r.name, r.normalized_name, r.domain)
        ON CONFLICT (normalized_name) DO UPDATE SET
            name = EXCLUDED.name, domain = EXCLUDED.domain,
            is_active = true, updated_at = CURRENT_TIMESTAMP;
    END LOOP;
END $$;

-- ─── GREENHOUSE sources (individual inserts) ─────────────────────────────────

DO $$
DECLARE
    r RECORD;
    cid BIGINT;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('stripe','stripe'),('airbnb','airbnb'),('doordash','doordashusa'),('figma','figma'),
            ('notion','notion'),('rippling','rippling'),('scale-ai','scaleai'),('anthropic','anthropic'),
            ('openai','openai'),('instacart','instacart'),('pinterest','pinterest'),('discord','discord'),
            ('dropbox','dropbox'),('flexport','flexport'),('gusto','gusto'),('faire','faire'),
            ('watershed','watershed'),('airtable','airtable'),('retool','retool'),('navan','navan'),
            ('zip-co','ziphq'),('persona','persona'),('mercury','mercury'),('vanta','vanta'),
            ('webflow','webflow'),('assembled','assembled'),('drata','drata'),
            ('bloomberg','bloomberg'),('two-sigma','twosigma'),('squarespace','squarespace'),
            ('mongodb','mongodb'),('justworks','justworks'),('cockroach-labs','cockroachlabs'),
            ('ramp','ramp'),('datadog','datadog'),('oscar-health','oscar'),('noom','noom'),
            ('spring-health','springhealth'),('alphasense','alphasense'),('peloton','peloton'),
            ('chime','chime'),('yext','yext'),
            ('zillow','zillow'),('redfin','redfin'),('outreach','outreach'),('highspot','highspot'),
            ('samsara','samsara'),
            ('crowdstrike','crowdstrike'),('wp-engine','wpengine'),('q2','q2ebanking'),
            ('procore','procore'),('bigcommerce','bigcommerce'),('sailpoint','sailpoint'),
            ('grubhub','grubhub'),('tempus','tempus'),('avant','avant'),('sprout-social','sproutsocial'),
            ('groupon','groupon'),('g2','g2crowd'),('activecampaign','activecampaign'),
            ('hubspot','hubspot'),('wayfair','wayfair'),('rapid7','rapid7'),('toast-inc','toast'),
            ('datarobot','datarobot'),('klaviyo','klaviyo'),('snyk','snyk'),('formlabs','formlabs'),
            ('guild-education','guild'),('ibotta','ibotta'),('ping-identity','pingidentity'),
            ('wiz','wiz'),('iterable','iterable'),
            ('calendly','calendly'),('greenlight','greenlight'),('onetrust','onetrust'),
            ('palantir','palantir'),('shield-ai','shieldai'),('anduril','andurilindustries'),('appian','appian'),
            ('epic-games','epicgames'),('pendo','pendo'),
            ('qualtrics','qualtrics'),('lucid-software','lucidsoftware'),('podium','podium'),('domo','domo'),
            ('carvana','carvana'),('axon','axon'),('godaddy','godaddy'),
            ('hims-hers','himshers'),('included-health','includedhealth'),('lyra-health','lyrahealth'),
            ('devoted-health','devotedhealth'),('cityblock','cityblockhealth'),('headway','headway'),
            ('garner-health','garnerhealth'),('intuit','intuit'),
            ('gitlab','gitlab'),('zapier','zapier'),('deel','deel'),('vercel','vercel'),
            ('linear','linear'),('posthog','posthog'),('supabase','supabase'),('resend','resend'),
            ('capital-one','capitalone'),('salesforce','salesforce'),('block','block'),
            ('coinbase','coinbase'),('plaid','plaid'),('brex','brex'),('cloudflare','cloudflare'),
            ('twilio','twilio'),('okta','okta'),('confluent','confluent'),('hashicorp','hashicorp'),
            ('elastic','elastic'),('snowflake','snowflakecomputing'),('databricks','databricks'),
            ('fivetran','fivetran'),('dbt-labs','dbtlabs'),('amplitude','amplitude'),
            ('launchdarkly','launchdarkly'),('sentry','sentry'),('pagerduty','pagerduty'),
            ('contentful','contentful'),('miro','miro'),('canva','canva'),('loom','loom'),
            ('onepassword','1password'),('grafana-labs','grafanalabs'),
            ('robinhood','robinhood'),('affirm','affirm'),('sofi','sofi'),('marqeta','marqeta'),('column','column'),
            ('cohere','cohere'),('perplexity','perplexity'),('hugging-face','huggingface'),
            ('wandb','wandb'),('labelbox','labelbox'),('deepgram','deepgram'),('jasper','jasper'),('writer','writer'),
            ('project44','project44'),('fourkites','fourkites'),('shippo','shippo'),
            ('veeva','veeva'),('doximity','doximity'),('flatiron-health','flatironhealth'),('ro','ro'),('zocdoc','zocdoc'),
            ('duolingo','duolingo'),('coursera','coursera'),('handshake','joinhandshake'),('khan-academy','khanacademy'),
            ('hightouch','hightouch'),('baseten','baseten'),('mintlify','mintlify'),('orb','withorb'),
            ('knock','knock'),('tinybird','tinybird'),('modal','modal'),
            ('harvey','harvey'),('glean','glean'),('cognition','cognition'),('sierra','sierra'),
            ('clay','clay'),('hume-ai','humeai'),('cerebras','cerebras'),('together-ai','togetherai'),
            ('groq','groq'),('anyscale','anyscale'),
            ('airbyte','airbyte'),('neon','neondatabase'),('clerk','clerk'),('workos','workos'),
            ('stytch','stytch'),('render','render'),
            ('sardine','sardine'),('unit','unit'),('moov','moov'),('lithic','lithic'),('levels','levels'),
            ('cursor','cursor'),('augment','augmentcode'),('codeium','codeium'),('tabnine','tabnine'),
            ('replit','replit'),('adept','adept'),('character-ai','character'),('runway','runwayml'),
            ('elevenlabs','elevenlabs'),('synthesia','synthesia'),('descript','descript'),
            ('pika','pika'),('stability-ai','stabilityai'),
            ('warp','warp'),('zed','zed'),('raycast','raycast'),('dagger','dagger'),('pulumi','pulumi'),
            ('temporal','temporal'),('prefect','prefect'),('dagster','dagster'),
            ('motherduck','motherduck'),('pinecone','pinecone'),('weaviate','weaviate'),
            ('alchemy','alchemy'),('phantom','phantom'),('privy','privy'),('dynamic','dynamic')
        ) AS t(normalized_name, board_token)
    LOOP
        SELECT id INTO cid FROM external_companies WHERE normalized_name = r.normalized_name;
        IF cid IS NOT NULL THEN
            INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
            VALUES (cid, 'GREENHOUSE', r.board_token, 'Greenhouse')
            ON CONFLICT (source_type, board_token) DO UPDATE SET
                source_name = 'Greenhouse', is_active = true, updated_at = CURRENT_TIMESTAMP;
        END IF;
    END LOOP;
END $$;

-- ─── LEVER sources ───────────────────────────────────────────────────────────

DO $$
DECLARE
    r RECORD;
    cid BIGINT;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('automattic','automattic'),('indeed','indeed'),('match-group','matchgroup'),
            ('convoy','convoy'),('moveworks','moveworks'),('planetscale','planetscale'),
            ('turso','turso'),('pylon','usepylon'),('fern','buildwithfern'),
            ('stainless','stainlessapi'),('depot','depot'),('inngest','inngest'),
            ('trigger-dev','triggerdev'),('nango','nango'),('loops','loops'),('e2b','e2b'),
            ('cartesia','cartesia'),('luma-ai','lumalabs')
        ) AS t(normalized_name, board_token)
    LOOP
        SELECT id INTO cid FROM external_companies WHERE normalized_name = r.normalized_name;
        IF cid IS NOT NULL THEN
            INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
            VALUES (cid, 'LEVER', r.board_token, 'Lever')
            ON CONFLICT (source_type, board_token) DO UPDATE SET
                source_name = 'Lever', is_active = true, updated_at = CURRENT_TIMESTAMP;
        END IF;
    END LOOP;
END $$;

-- ─── ASHBY sources ───────────────────────────────────────────────────────────

DO $$
DECLARE
    r RECORD;
    cid BIGINT;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('linear','Linear'),('notion','Notion'),('ramp','Ramp'),('vanta','Vanta'),
            ('assembled','Assembled'),('watershed','Watershed'),('persona','Persona'),
            ('mercury','Mercury'),('unriddle','Unriddle'),('lantern','Lantern'),
            ('truewind','Truewind'),('braintrust-ai','Braintrust'),('svix','Svix'),('resend','Resend')
        ) AS t(normalized_name, board_token)
    LOOP
        SELECT id INTO cid FROM external_companies WHERE normalized_name = r.normalized_name;
        IF cid IS NOT NULL THEN
            INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
            VALUES (cid, 'ASHBY', r.board_token, 'Ashby')
            ON CONFLICT (source_type, board_token) DO UPDATE SET
                source_name = 'Ashby', is_active = true, updated_at = CURRENT_TIMESTAMP;
        END IF;
    END LOOP;
END $$;

-- ─── SMARTRECRUITERS sources ─────────────────────────────────────────────────

DO $$
DECLARE
    r RECORD;
    cid BIGINT;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('servicenow','ServiceNow')
        ) AS t(normalized_name, board_token)
    LOOP
        SELECT id INTO cid FROM external_companies WHERE normalized_name = r.normalized_name;
        IF cid IS NOT NULL THEN
            INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
            VALUES (cid, 'SMARTRECRUITERS', r.board_token, 'SmartRecruiters')
            ON CONFLICT (source_type, board_token) DO UPDATE SET
                source_name = 'SmartRecruiters', is_active = true, updated_at = CURRENT_TIMESTAMP;
        END IF;
    END LOOP;
END $$;
