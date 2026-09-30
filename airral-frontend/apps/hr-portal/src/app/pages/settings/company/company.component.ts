import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CompanyApiService } from '@airral/shared-api';
import { CompanyProfile } from '@airral/shared-types';
import { browserTimeZone } from '@airral/shared-utils';
import { finalize } from 'rxjs';
import { messageFrom } from '../api-message';

interface ProfileDraft {
  website: string;
  logoUrl: string;
  industry: string;
  companySizeRange: string;
  timezone: string;
  country: string;
  about: string;
}

/**
 * What the company says about itself. It shows on its open jobs on
 * apply.airral.com, and the time zone names interview times in emails.
 */
@Component({
  selector: 'app-company-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './company.component.html',
  styleUrls: ['../settings-page.css', './company.component.css'],
})
export class CompanyComponent implements OnInit {
  private readonly companyApi = inject(CompanyApiService);

  readonly sizes = ['1-10', '11-50', '51-200', '201-500', '501-1000', '1001+'];
  readonly timeZones = timeZones();

  profile: CompanyProfile | null = null;
  draft: ProfileDraft = { website: '', logoUrl: '', industry: '', companySizeRange: '', timezone: '', country: '', about: '' };
  loading = true;
  loadError = '';
  saving = false;
  message = '';
  error = '';
  logoBroken = false;

  ngOnInit(): void {
    this.companyApi
      .get()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (profile) => this.show(profile),
        error: (error) => (this.loadError = messageFrom(error, 'Your company profile could not be loaded. Try again.')),
      });
  }

  save(): void {
    if (this.saving) return;
    this.saving = true;
    this.message = '';
    this.error = '';
    const draft = this.draft;
    this.companyApi
      .update({
        website: draft.website.trim() || null,
        logoUrl: draft.logoUrl.trim() || null,
        industry: draft.industry.trim() || null,
        companySizeRange: draft.companySizeRange || null,
        timezone: draft.timezone || null,
        country: draft.country.trim() || null,
        about: draft.about.trim() || null,
      })
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: (profile) => {
          this.show(profile);
          this.message = profile.verificationStatus === 'VERIFIED'
            ? 'Saved. Your open jobs on apply.airral.com show it now.'
            : 'Saved. It shows on your jobs once AIRRAL has verified your company.';
        },
        error: (error) => (this.error = messageFrom(error, 'Your company profile could not be saved. Try again.')),
      });
  }

  private show(profile: CompanyProfile): void {
    this.profile = profile;
    this.logoBroken = false;
    this.draft = {
      website: profile.website ?? '',
      logoUrl: profile.logoUrl ?? '',
      industry: profile.industry ?? '',
      companySizeRange: profile.companySizeRange ?? '',
      // A company that has not chosen one starts from the browser's zone.
      timezone: profile.timezone ?? browserTimeZone() ?? '',
      country: profile.country ?? '',
      about: profile.about ?? '',
    };
  }
}

/** The zones this browser knows, or at least its own. */
function timeZones(): string[] {
  const intl = Intl as { supportedValuesOf?: (key: string) => string[] };
  try {
    const zones = intl.supportedValuesOf?.('timeZone') ?? [];
    if (zones.length) return zones;
  } catch {
    // Older browsers: fall through.
  }
  const own = browserTimeZone();
  return own ? [own] : [];
}
