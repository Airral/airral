import {
  Component,
  Inject,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface Answer {
  question: string;
  answer: string;
}

@Component({
  selector: 'app-help',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './help.component.html',
  styleUrls: ['./help.component.css'],
})
export class HelpComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly answers: Answer[] = [
    {
      question: 'Do I need an account to look around?',
      answer:
        'No. You can browse open roles and read the detail on any of them without signing up. An account is what lets Airral keep looking on your behalf.',
    },
    {
      question: 'How does Airral decide what to show me?',
      answer:
        'You tell it what you are after once. It keeps watching what is open and speaks up when something fits, and every suggestion comes with the reason behind it.',
    },
    {
      question: 'What does it do with my résumé?',
      answer:
        'Before you apply, it reworks your résumé for that specific role — the wording the posting used, and the things it asked for twice. You see the result before it goes anywhere.',
    },
    {
      question: 'What does it cost?',
      answer:
        'Starting as a candidate is free. The pricing page has the plans and what each one covers.',
    },
    {
      question: 'I am hiring. Where do I start?',
      answer:
        'The employers page walks through posting a role and following candidates through each stage. You can also write to us and we will set it up with you.',
    },
    {
      question: 'How do I get my data removed?',
      answer:
        'Write to us from the contact page and say so. What we hold and why is set out on the privacy page.',
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

}
