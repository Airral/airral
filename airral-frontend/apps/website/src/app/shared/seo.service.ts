import { DOCUMENT } from '@angular/common';
import { Inject, Injectable } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

export interface SeoConfig {
  title: string;
  description: string;
  path: string;
  type?: 'website' | 'article';
  image?: string;
  robots?: string;
  structuredData?: Record<string, unknown>[];
}

@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly siteUrl = 'https://www.airral.com';
  private readonly siteName = 'AIRRAL';
  private readonly defaultImage = '/assets/brand/airral-og.png';

  constructor(
    private readonly title: Title,
    private readonly meta: Meta,
    @Inject(DOCUMENT) private readonly document: Document
  ) {}

  setPage(config: SeoConfig): void {
    const canonicalUrl = this.toAbsoluteUrl(config.path);
    const imageUrl = this.toAbsoluteUrl(config.image || this.defaultImage);
    const robots = config.robots || 'index, follow, max-image-preview:large';

    this.title.setTitle(config.title);
    this.updateMeta('name', 'description', config.description);
    this.updateMeta('name', 'robots', robots);
    this.updateMeta('name', 'theme-color', '#007C6D');
    this.updateMeta('property', 'og:site_name', this.siteName);
    this.updateMeta('property', 'og:title', config.title);
    this.updateMeta('property', 'og:description', config.description);
    this.updateMeta('property', 'og:type', config.type || 'website');
    this.updateMeta('property', 'og:url', canonicalUrl);
    this.updateMeta('property', 'og:image', imageUrl);
    this.updateMeta('name', 'twitter:card', 'summary_large_image');
    this.updateMeta('name', 'twitter:title', config.title);
    this.updateMeta('name', 'twitter:description', config.description);
    this.updateMeta('name', 'twitter:image', imageUrl);
    this.setCanonical(canonicalUrl);
    this.setStructuredData([
      this.organizationSchema(),
      this.websiteSchema(),
      this.webPageSchema(config, canonicalUrl),
      ...this.breadcrumbSchema(config.path),
      ...(config.structuredData || []),
    ]);
  }

  private updateMeta(attribute: 'name' | 'property', value: string, content: string): void {
    this.meta.updateTag({ [attribute]: value, content }, `${attribute}="${value}"`);
  }

  private setCanonical(url: string): void {
    let link = this.document.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!link) {
      link = this.document.createElement('link');
      link.setAttribute('rel', 'canonical');
      this.document.head.appendChild(link);
    }
    link.setAttribute('href', url);
  }

  private setStructuredData(nodes: Record<string, unknown>[]): void {
    this.document.querySelectorAll('script[data-airral-json-ld="true"]').forEach((node) => node.remove());
    const script = this.document.createElement('script');
    script.type = 'application/ld+json';
    script.setAttribute('data-airral-json-ld', 'true');
    script.textContent = JSON.stringify(nodes);
    this.document.head.appendChild(script);
  }

  private organizationSchema(): Record<string, unknown> {
    return {
      '@context': 'https://schema.org',
      '@type': 'Organization',
      name: 'AIRRAL',
      url: this.siteUrl,
      logo: this.toAbsoluteUrl('/assets/brand/airral-logo.svg'),
      contactPoint: [
        {
          '@type': 'ContactPoint',
          contactType: 'customer support',
          email: 'hello@airral.com',
        },
        {
          '@type': 'ContactPoint',
          contactType: 'sales',
          email: 'sales@airral.com',
        },
      ],
    };
  }

  private websiteSchema(): Record<string, unknown> {
    return {
      '@context': 'https://schema.org',
      '@type': 'WebSite',
      name: 'AIRRAL',
      url: this.siteUrl,
      potentialAction: {
        '@type': 'SearchAction',
        target: `${this.siteUrl}/jobs?q={search_term_string}`,
        'query-input': 'required name=search_term_string',
      },
    };
  }

  private webPageSchema(config: SeoConfig, url: string): Record<string, unknown> {
    return {
      '@context': 'https://schema.org',
      '@type': config.type === 'article' ? 'Article' : 'WebPage',
      name: config.title,
      description: config.description,
      url,
      isPartOf: {
        '@type': 'WebSite',
        name: this.siteName,
        url: this.siteUrl,
      },
    };
  }

  private breadcrumbSchema(path: string): Record<string, unknown>[] {
    const cleanPath = path.split('?')[0].replace(/^\/|\/$/g, '');
    if (!cleanPath) {
      return [];
    }

    const segments = cleanPath.split('/');
    const items = [
      {
        '@type': 'ListItem',
        position: 1,
        name: 'Home',
        item: this.siteUrl,
      },
      ...segments.map((segment, index) => {
        const pathToSegment = segments.slice(0, index + 1).join('/');
        return {
          '@type': 'ListItem',
          position: index + 2,
          name: this.toTitle(segment),
          item: `${this.siteUrl}/${pathToSegment}`,
        };
      }),
    ];

    return [
      {
        '@context': 'https://schema.org',
        '@type': 'BreadcrumbList',
        itemListElement: items,
      },
    ];
  }

  private toAbsoluteUrl(pathOrUrl: string): string {
    if (/^https?:\/\//i.test(pathOrUrl)) {
      return pathOrUrl;
    }
    return `${this.siteUrl}${pathOrUrl.startsWith('/') ? pathOrUrl : `/${pathOrUrl}`}`;
  }

  private toTitle(value: string): string {
    return value
      .replace(/-/g, ' ')
      .replace(/\b\w/g, (letter) => letter.toUpperCase());
  }
}
