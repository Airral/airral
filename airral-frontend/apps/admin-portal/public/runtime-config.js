// Overwritten at deploy time with the values for the target environment.
// Committed with empty values so local dev and the built bundle always have the
// file present (a missing file would 404 on every page load).
window.AIRRAL_RUNTIME_CONFIG = { apiBaseUrl: '', googleClientId: '' };
