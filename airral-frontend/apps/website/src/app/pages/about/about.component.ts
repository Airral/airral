// apps/website/src/app/pages/about/about.component.ts
import {
  Component,
  Inject,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface Principle {
  /** Inline SVG path data on a 24x24 grid. */
  icon: string;
  title: string;
  description: string;
}

@Component({
  selector: 'app-about',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './about.component.html',
  styleUrls: ['./about.component.css'],
})
export class AboutComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  /** How we decide what to build. Each one describes something the product does. */
  readonly principles: Principle[] = [
    {
      // speech mark
      icon: 'M20 4H4a1.6 1.6 0 0 0-1.6 1.6v9.2A1.6 1.6 0 0 0 4 16.4h3.4L12 21l4.6-4.6H20a1.6 1.6 0 0 0 1.6-1.6V5.6A1.6 1.6 0 0 0 20 4z M8 10.2h8',
      title: 'Say why',
      description:
        'Every match comes with the reason behind it, in plain words. No score you cannot question, no ranking you cannot see the workings of.',
    },
    {
      // eye
      icon: 'M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z M12 9.4a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2z',
      title: 'Do the reading',
      description:
        'Airral watches for openings so you do not have to sit on job boards. It speaks up when something fits, and stays quiet when nothing does.',
    },
    {
      // stacked layers
      icon: 'M12 3l9 4.6-9 4.6-9-4.6L12 3z M3 12.4l9 4.6 9-4.6 M3 16.9l9 4.6 9-4.6',
      title: 'One place per hire',
      description:
        'Applications, interviews, feedback and offers live together. Nobody has to ask where a candidate got to or who said what.',
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

}
