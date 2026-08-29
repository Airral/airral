// apps/website/src/app/pages/blog/blog.component.ts
import {
  Component,
  Inject,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

/** A page on the site that exists today, offered while there are no posts. */
interface Pointer {
  title: string;
  what: string;
  path: string;
}

@Component({
  selector: 'app-blog',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './blog.component.html',
  styleUrls: ['./blog.component.css'],
})
export class BlogComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly pointers: Pointer[] = [
    {
      title: 'How it works',
      what: 'What Airral does with what you tell it, step by step.',
      path: '/how-it-works',
    },
    {
      title: 'Open roles',
      what: 'Everything on Airral that is open right now.',
      path: '/jobs',
    },
    {
      title: 'Help',
      what: 'Answers to the questions people ask us most.',
      path: '/help',
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

}
