#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080/api}"

echo "Checking applicant job feed at ${API_BASE_URL}"
echo

curl -sS "${API_BASE_URL}/candidate/jobs/recommended?source=all&limit=20" \
  | node -e '
      let body = "";
      process.stdin.on("data", chunk => body += chunk);
      process.stdin.on("end", () => {
        const jobs = JSON.parse(body);
        const bySource = jobs.reduce((acc, job) => {
          const key = `${job.companyName} · ${job.sourceName}`;
          acc[key] = (acc[key] || 0) + 1;
          return acc;
        }, {});

        console.log(`Total returned: ${jobs.length}`);
        console.log("By source:");
        Object.entries(bySource).forEach(([source, count]) => console.log(`- ${source}: ${count}`));
        console.log("\nSample:");
        jobs.slice(0, 8).forEach(job => {
          console.log(`- ${job.title} | ${job.companyName} | ${job.location} | ${job.postedLabel}`);
        });
      });
    '
