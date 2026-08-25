import { UpdateCandidateProfileRequest } from '@airral/shared-types';

const JOB_SEARCH_SEED_PREFIX = 'airral_job_search_seed:';

export interface OnboardingJobSearchSeed {
  query: string;
  roles: string[];
  location?: string;
  workMode?: string;
  savedAt: string;
}

export function saveOnboardingJobSearchSeed(
  email: string | undefined,
  request: UpdateCandidateProfileRequest
): void {
  if (!email) {
    return;
  }

  const roles = request.matchPreferences?.targetRoles?.filter(Boolean) ?? [];
  const seed: OnboardingJobSearchSeed = {
    query: roles[0] ?? '',
    roles,
    location: request.location,
    workMode: request.preferredWorkMode,
    savedAt: new Date().toISOString(),
  };

  try {
    localStorage.setItem(getSeedKey(email), JSON.stringify(seed));
  } catch {
    // localStorage unavailable
  }
}

export function getOnboardingJobSearchSeed(email: string | undefined): OnboardingJobSearchSeed | null {
  if (!email) {
    return null;
  }

  try {
    const rawSeed = localStorage.getItem(getSeedKey(email));
    return rawSeed ? JSON.parse(rawSeed) as OnboardingJobSearchSeed : null;
  } catch {
    return null;
  }
}

function getSeedKey(email: string): string {
  return `${JOB_SEARCH_SEED_PREFIX}${email.trim().toLowerCase()}`;
}
