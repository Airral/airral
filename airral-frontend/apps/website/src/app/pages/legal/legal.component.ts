// apps/website/src/app/pages/legal/legal.component.ts
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

export type LegalDocument = 'terms' | 'privacy' | 'cookies';

/**
 * Terms, privacy policy and cookie policy.
 *
 * <p>All three routes rendered the terms until the privacy and cookie pages
 * were written; the route path now picks the document.
 */
@Component({
  selector: 'app-legal',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './legal.component.html',
  styleUrls: ['./legal.component.css'],
})
export class LegalComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly doc: LegalDocument = pick(inject(ActivatedRoute).snapshot.routeConfig?.path);
}

function pick(path: string | undefined): LegalDocument {
  return path === 'privacy' || path === 'cookies' ? path : 'terms';
}
