import { Component } from '@angular/core';
import { CommonModule, NgFor } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-contact',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, HeaderComponent, FooterComponent],
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.css'],
})
export class ContactComponent {
  formData = {
    name: '',
    email: '',
    subject: '',
    message: '',
  };

  submitted = false;

  readonly contactInfo = [
    {
      icon: '📧',
      title: 'Support',
      description: 'hello@airral.com',
      link: 'mailto:hello@airral.com',
    },
    {
      icon: '💼',
      title: 'Sales',
      description: 'sales@airral.com',
      link: 'mailto:sales@airral.com',
    },
    {
      icon: '🌐',
      title: 'Website',
      description: 'www.airral.com',
      link: 'https://www.airral.com',
    },
  ];

  readonly faqs = [
    {
      question: 'What\'s your typical response time?',
      answer: 'We aim to respond to all inquiries within 24 business hours. Enterprise customers receive priority support.',
    },
    {
      question: 'Do you offer phone support?',
      answer: 'Phone support is available for Professional and Enterprise plans. Contact hello@airral.com to schedule a call.',
    },
    {
      question: 'Can I get a demo?',
      answer: 'Yes! Email sales@airral.com and we\'ll set up a personalized demo of the AIRRAL ATS platform.',
    },
  ];

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  onSubmit() {
    if (this.formData.name && this.formData.email && this.formData.message) {
      // In a real app, you'd send this to a backend API
      console.log('Form submitted:', this.formData);
      this.submitted = true;
      this.formData = { name: '', email: '', subject: '', message: '' };
      setTimeout(() => (this.submitted = false), 3000);
    }
  }
}
