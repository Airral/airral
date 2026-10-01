import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

/**
 * The AIRRAL mark: the teal rounded square with the white A, the same drawing
 * as apps/website/public/assets/brand/airral-logo.svg and every app's favicon.
 * Inline, so it never waits on a request or breaks on a wrong asset path.
 * Decorative by default; the wordmark next to it carries the name.
 */
@Component({
  selector: 'airral-logo',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      viewBox="0 0 512 512"
      [attr.width]="size"
      [attr.height]="size"
      [attr.aria-hidden]="label ? null : 'true'"
      [attr.role]="label ? 'img' : null"
      [attr.aria-label]="label || null"
      focusable="false"
    >
      <rect width="512" height="512" rx="112" fill="#007C6D" />
      <path fill="#fff" d="M113 352 221 128h70l108 224h-67l-20-45H199l-20 45h-66Zm108-96h68l-34-77-34 77Z" />
    </svg>
  `,
  styles: [':host { display: inline-flex; flex: none; line-height: 0; } svg { display: block; }'],
})
export class AirralLogoComponent {
  /** Width and height in px. */
  @Input() size = 28;
  /** Set when the mark stands alone and must be announced, e.g. "AIRRAL". */
  @Input() label = '';
}
