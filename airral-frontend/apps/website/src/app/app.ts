import { Component, Inject, OnDestroy, OnInit, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterModule } from '@angular/router';
import { filter } from 'rxjs/operators';
import { DEFAULT_SEO } from './shared/seo-pages';
import { SeoConfig, SeoService } from './shared/seo.service';

/**
 * If the reveal animation has not engaged within this window, give up and show
 * everything. Guards against any environment where IntersectionObserver never
 * delivers a callback — a background tab, an embedded webview, a renderer that
 * is not compositing. Content must never stay hidden.
 */
const REVEAL_FAILSAFE_MS = 2000;

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  protected title = 'website';

  private revealObserver?: IntersectionObserver;
  private failsafe?: ReturnType<typeof setTimeout>;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private seo: SeoService,
    @Inject(PLATFORM_ID) private readonly platformId: object
  ) {}

  ngOnInit() {
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        if (typeof window !== 'undefined') {
          window.scrollTo(0, 0);
        }
        this.setRouteSeo(event as NavigationEnd);
        // Each route brings its own elements, so re-scan after navigation.
        this.scanReveals();
      });

    this.startReveals();
  }

  ngOnDestroy(): void {
    this.revealObserver?.disconnect();
    if (this.failsafe) {
      clearTimeout(this.failsafe);
    }
  }

  /**
   * Reveal-on-scroll, wired once for the whole site rather than per page.
   * Opting in here — rather than hiding in CSS and hoping JS arrives — is what
   * keeps the page readable when it does not.
   */
  private startReveals(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    // Reduced motion: leave everything visible and never animate.
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return;
    }

    if (typeof IntersectionObserver === 'undefined') {
      return;
    }

    this.revealObserver = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-in');
            this.revealObserver?.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: '0px 0px -5% 0px' }
    );

    document.documentElement.classList.add('js-reveal');
    this.scanReveals();

    // If nothing has revealed by now, the observer is not working here.
    this.failsafe = setTimeout(() => {
      const hidden = document.querySelectorAll('.rise:not(.is-in)').length;
      const shown = document.querySelectorAll('.rise.is-in').length;
      if (hidden > 0 && shown === 0) {
        this.abandonReveals();
      }
    }, REVEAL_FAILSAFE_MS);
  }

  private scanReveals(): void {
    if (!this.revealObserver) {
      return;
    }
    document
      .querySelectorAll<HTMLElement>('.rise:not(.is-in)')
      .forEach((el) => this.revealObserver?.observe(el));
  }

  /** Drop the animation entirely and show the page. */
  private abandonReveals(): void {
    this.revealObserver?.disconnect();
    this.revealObserver = undefined;
    document.documentElement.classList.remove('js-reveal');
  }

  private setRouteSeo(event: NavigationEnd): void {
    let activeRoute = this.route.firstChild;
    while (activeRoute?.firstChild) {
      activeRoute = activeRoute.firstChild;
    }

    const routeSeo = activeRoute?.snapshot.data['seo'] as SeoConfig | undefined;
    this.seo.setPage({
      ...DEFAULT_SEO,
      ...(routeSeo || {}),
      path: routeSeo?.path || event.urlAfterRedirects.split('?')[0] || '/',
    });
  }
}
