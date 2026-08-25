package com.airral.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, canonical skill vocabulary used by resume parsing and job-fit analysis. */
final class ResumeSkillCatalog {

    private static final List<SkillSignal> SIGNALS = List.of(
            skill("Java", "java"), skill("Spring Boot", "spring boot", "spring framework"),
            skill("Kotlin", "kotlin"), skill("Python", "python"), skill("Django", "django"),
            skill("FastAPI", "fastapi"), skill("JavaScript", "javascript"),
            skill("TypeScript", "typescript"), skill("Angular", "angular"),
            skill("React", "react.js", "reactjs", "react"), skill("Vue", "vue.js", "vuejs"),
            skill("Node.js", "node.js", "nodejs"), skill("C#", "c#", "c sharp"),
            skill(".NET", ".net", "dotnet"), skill("C/C++", "c/c++", "c++"),
            skill("Ruby", "ruby"), skill("Ruby on Rails", "ruby on rails", "rails"),
            skill("PHP", "php"), skill("Laravel", "laravel"), skill("Go", "golang"),
            skill("Rust", "rust"), skill("Swift", "swift"), skill("HTML", "html"),
            skill("CSS", "css"), skill("Sass", "sass", "scss"),
            skill("Tailwind CSS", "tailwind css", "tailwind"),
            skill("SQL", "sql"), skill("PostgreSQL", "postgresql", "postgres"),
            skill("MySQL", "mysql"), skill("SQL Server", "sql server", "mssql"),
            skill("Oracle", "oracle database"), skill("MongoDB", "mongodb", "mongo db"),
            skill("Redis", "redis"), skill("DynamoDB", "dynamodb"),
            skill("Snowflake", "snowflake"), skill("BigQuery", "bigquery", "big query"),
            skill("GraphQL", "graphql"), skill("REST APIs", "rest api", "restful api", "restful services"),
            skill("gRPC", "grpc"), skill("Kafka", "apache kafka", "kafka"),
            skill("RabbitMQ", "rabbitmq"), skill("Microservices", "microservices", "microservice architecture"),
            skill("Distributed Systems", "distributed systems", "distributed computing"),
            skill("AWS", "amazon web services", "aws"), skill("GCP", "google cloud platform", "google cloud", "gcp"),
            skill("Azure", "microsoft azure", "azure"), skill("Docker", "docker"),
            skill("Kubernetes", "kubernetes", "k8s"), skill("Terraform", "terraform"),
            skill("Ansible", "ansible"), skill("CI/CD", "ci/cd", "continuous integration", "continuous delivery"),
            skill("GitHub Actions", "github actions"), skill("GitLab CI", "gitlab ci"),
            skill("Jenkins", "jenkins"), skill("Git", "git"), skill("Linux", "linux"),
            skill("Monitoring", "monitoring"), skill("Observability", "observability"),
            skill("Datadog", "datadog"), skill("Splunk", "splunk"),
            skill("Prometheus", "prometheus"), skill("Grafana", "grafana"),
            skill("Machine Learning", "machine learning"), skill("Generative AI", "generative ai", "genai"),
            skill("LLMs", "large language models", "llms", "llm"), skill("NLP", "natural language processing", "nlp"),
            skill("TensorFlow", "tensorflow"), skill("PyTorch", "pytorch"),
            skill("scikit-learn", "scikit-learn", "sklearn"), skill("Pandas", "pandas"),
            skill("NumPy", "numpy"), skill("Spark", "apache spark", "pyspark", "spark"),
            skill("Airflow", "apache airflow", "airflow"), skill("dbt", "dbt"),
            skill("ETL", "etl"), skill("Data Modeling", "data modeling", "data modelling"),
            skill("Data Analysis", "data analysis", "data analytics"),
            skill("Statistical Analysis", "statistical analysis", "statistics"),
            skill("Tableau", "tableau"), skill("Power BI", "power bi"),
            skill("Looker", "looker"), skill("Excel", "microsoft excel", "excel"),
            skill("Salesforce", "salesforce"), skill("HubSpot", "hubspot"),
            skill("SAP", "sap"), skill("Workday", "workday"), skill("ServiceNow", "servicenow"),
            skill("Product Management", "product management"), skill("Product Strategy", "product strategy"),
            skill("Roadmapping", "product roadmap", "roadmapping"), skill("User Research", "user research"),
            skill("Project Management", "project management"), skill("Program Management", "program management"),
            skill("Agile", "agile"), skill("Scrum", "scrum"), skill("Jira", "jira"),
            skill("Figma", "figma"), skill("UI/UX", "ui/ux", "user experience design", "ux design"),
            skill("Design Systems", "design systems", "design system"),
            skill("Accessibility", "web accessibility", "digital accessibility", "wcag"),
            skill("Customer Success", "customer success"), skill("Customer Support", "customer support"),
            skill("Account Management", "account management"), skill("Sales", "sales"),
            skill("Business Development", "business development"), skill("Lead Generation", "lead generation"),
            skill("Negotiation", "negotiation"), skill("Marketing", "marketing"),
            skill("Digital Marketing", "digital marketing"), skill("SEO", "search engine optimization", "seo"),
            skill("SEM", "search engine marketing", "sem"), skill("Content Marketing", "content marketing"),
            skill("Google Analytics", "google analytics", "ga4"), skill("Market Research", "market research"),
            skill("Accounting", "accounting"), skill("Financial Analysis", "financial analysis"),
            skill("Financial Modeling", "financial modeling", "financial modelling"),
            skill("Forecasting", "forecasting"), skill("Budgeting", "budgeting"),
            skill("QuickBooks", "quickbooks"), skill("GAAP", "gaap"),
            skill("Operations", "operations management", "business operations"),
            skill("Supply Chain", "supply chain"), skill("Logistics", "logistics"),
            skill("Procurement", "procurement"), skill("Inventory Management", "inventory management"),
            skill("Lean Six Sigma", "lean six sigma", "six sigma"),
            skill("Recruiting", "recruiting", "talent acquisition"), skill("Employee Relations", "employee relations"),
            skill("HRIS", "hris"), skill("Payroll", "payroll"),
            skill("Healthcare", "healthcare"), skill("Patient Care", "patient care"),
            skill("Clinical Research", "clinical research"), skill("Epic", "epic systems", "epic emr"),
            skill("HIPAA", "hipaa"), skill("CPR", "cpr"),
            skill("Compliance", "regulatory compliance", "compliance"), skill("Risk Management", "risk management"),
            skill("Audit", "auditing", "audit"), skill("SOC 2", "soc 2", "soc2"),
            skill("ISO 27001", "iso 27001"), skill("Cybersecurity", "cybersecurity", "information security"),
            skill("Network Security", "network security"), skill("Incident Response", "incident response"),
            skill("Technical Writing", "technical writing"), skill("Public Speaking", "public speaking"),
            skill("Stakeholder Management", "stakeholder management"),
            skill("Change Management", "change management"), skill("Leadership", "team leadership", "people leadership")
    );

    private ResumeSkillCatalog() {
    }

    static List<String> findSkills(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (SkillSignal signal : SIGNALS) {
            if (signal.pattern().matcher(text).find()) {
                found.add(signal.canonical());
            }
        }
        return new ArrayList<>(found);
    }

    static String canonicalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SkillSignal signal : SIGNALS) {
            if (signal.pattern().matcher(value.strip()).find()) {
                return signal.canonical();
            }
        }
        return null;
    }

    static List<SkillSignal> signals() {
        return SIGNALS;
    }

    static int mentionCount(String text, String skill) {
        if (text == null || text.isBlank() || skill == null || skill.isBlank()) {
            return 0;
        }
        SkillSignal signal = SIGNALS.stream()
                .filter(candidate -> candidate.canonical().equalsIgnoreCase(skill))
                .findFirst()
                .orElse(null);
        if (signal == null) {
            return containsPhrase(text, skill) ? 1 : 0;
        }
        int count = 0;
        Matcher matcher = signal.pattern().matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    static boolean containsPhrase(String text, String value) {
        if (text == null || text.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        String normalizedText = normalize(text);
        String normalizedValue = normalize(value);
        return (" " + normalizedText + " ").contains(" " + normalizedValue + " ");
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.US)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9+#.]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static SkillSignal skill(String canonical, String... aliases) {
        String alternatives = java.util.Arrays.stream(aliases)
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|"));
        Pattern pattern = Pattern.compile("(?i)(^|[^a-z0-9])(?:" + alternatives + ")(?=$|[^a-z0-9])");
        return new SkillSignal(canonical, pattern);
    }

    record SkillSignal(String canonical, Pattern pattern) {
    }
}
