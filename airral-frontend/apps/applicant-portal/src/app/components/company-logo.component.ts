import { ChangeDetectionStrategy, Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * A company's logo, with an honest fallback.
 *
 * Only about one job in eight arrives with a logo URL, but many more carry the
 * company's domain or link to the company's own careers site. So this tries, in
 * order: the logo the API sent, the icon for the company's domain, and the icon
 * for the domain of the job link when that link is the company's own site.
 * When none of those produce a real image it shows the company's initial on a
 * colored tile, never a generic globe.
 *
 * Job links on an ATS host (Greenhouse, Lever, Workday...) or on AIRRAL itself
 * are skipped: their icon is the host's, not the employer's.
 */
@Component({
  selector: 'app-company-logo',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="logo" [class.has-image]="!!src" [style.width.px]="size" [style.height.px]="size"
          [style.border-radius.px]="radius" [style.background]="src ? null : color" aria-hidden="true">
      <img *ngIf="src" [src]="src" alt="" loading="lazy" decoding="async"
           [style.width.px]="size - inset * 2" [style.height.px]="size - inset * 2"
           (load)="onLoad($event)" (error)="next()">
      <span *ngIf="!src" class="letter" [style.font-size.px]="size * 0.42">{{ letter }}</span>
    </span>
  `,
  styles: [`
    :host { display: inline-flex; flex: none; }
    .logo {
      display: grid;
      place-items: center;
      overflow: hidden;
      color: #ffffff;
      font-weight: 750;
      text-shadow: 0 1px 1px rgba(0, 0, 0, 0.15);
    }
    .logo.has-image {
      background: #ffffff;
      box-shadow: inset 0 0 0 1px rgba(16, 24, 40, 0.1);
    }
    img { object-fit: contain; display: block; }
    .letter { line-height: 1; }
  `],
})
export class CompanyLogoComponent implements OnChanges {
  @Input() name = '';
  @Input() logoUrl: string | null | undefined;
  @Input() domain: string | null | undefined;
  /** The job or apply link, used only when it points at the company's own site. */
  @Input() url: string | null | undefined;
  @Input() size = 42;

  src: string | null = null;
  private candidates: string[] = [];

  get radius(): number {
    return Math.round(this.size * 0.26);
  }

  get inset(): number {
    return Math.max(4, Math.round(this.size * 0.14));
  }

  get letter(): string {
    return (this.name || '?').trim().charAt(0).toUpperCase() || '?';
  }

  get color(): string {
    return tileColor(this.name || '?');
  }

  ngOnChanges(): void {
    const domains = [normalizeDomain(this.domain), companySiteDomain(this.url)].filter(
      (value): value is string => Boolean(value)
    );
    const list = [
      safeHttpsUrl(this.logoUrl),
      ...domains.map((value) => `https://www.google.com/s2/favicons?domain=${encodeURIComponent(value)}&sz=128`),
    ].filter((value): value is string => Boolean(value));
    this.candidates = Array.from(new Set(list));
    this.src = this.candidates.shift() ?? null;
  }

  onLoad(event: Event): void {
    // The icon service answers an unknown domain with a 16px globe rather than
    // an error, so a tiny image means "no logo found", not "a small logo".
    const image = event.target as HTMLImageElement;
    if (image.naturalWidth > 0 && image.naturalWidth < 32) {
      this.next();
    }
  }

  next(): void {
    this.src = this.candidates.shift() ?? null;
  }
}

// Every color keeps at least 4.5:1 against the white initial drawn on it. The
// orange, green, blue, teal and amber were lighter (3.0-4.0:1) and are darkened
// to the same hue; the order is unchanged, so a company keeps its color's family.
const TILE_COLORS = [
  '#d93a30', '#b1610e', '#278643', '#177dae', '#2f6feb', '#5856d6',
  '#9b4dca', '#d6336c', '#8c6a4a', '#0e8379', '#996d00', '#4a5568',
];

export function tileColor(name: string): string {
  let hash = 0;
  for (let index = 0; index < name.length; index += 1) {
    hash = (hash * 31 + name.charCodeAt(index)) >>> 0;
  }
  return TILE_COLORS[hash % TILE_COLORS.length];
}

const ATS_HOSTS = [
  'greenhouse.io', 'lever.co', 'myworkdayjobs.com', 'myworkdaysite.com', 'ashbyhq.com',
  'smartrecruiters.com', 'workable.com', 'bamboohr.com', 'icims.com', 'jobvite.com',
  'recruitee.com', 'breezy.hr', 'jazzhr.com', 'applytojob.com', 'rippling.com',
  'paylocity.com', 'ultipro.com', 'taleo.net', 'successfactors.com', 'oraclecloud.com',
  'dayforcehcm.com', 'adp.com', 'teamtailor.com', 'personio.de', 'personio.com',
  // AIRRAL's own pages: an employer's job hosted here must not wear our icon.
  'airral.com',
];

const SECOND_LEVEL = new Set(['co', 'com', 'org', 'net', 'ac', 'gov']);

function safeHttpsUrl(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value.trim());
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.toString() : null;
  } catch {
    return null;
  }
}

function normalizeDomain(value: string | null | undefined): string | null {
  if (!value || !value.trim()) return null;
  const raw = value.trim().includes('://') ? value.trim() : `https://${value.trim()}`;
  try {
    return registrableDomain(new URL(raw).hostname);
  } catch {
    return null;
  }
}

/** The employer's domain from a job link, or null when the link is on an ATS. */
function companySiteDomain(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const host = new URL(value).hostname.toLowerCase();
    if (ATS_HOSTS.some((ats) => host === ats || host.endsWith(`.${ats}`))) {
      return null;
    }
    return registrableDomain(host);
  } catch {
    return null;
  }
}

function registrableDomain(hostname: string): string | null {
  const parts = hostname.toLowerCase().replace(/\.$/, '').split('.').filter(Boolean);
  if (parts.length < 2) return null;
  const keep = parts.length >= 3 && SECOND_LEVEL.has(parts[parts.length - 2]) && parts[parts.length - 1].length === 2 ? 3 : 2;
  return parts.slice(-keep).join('.');
}
