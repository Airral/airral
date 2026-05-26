import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterModule } from '@angular/router';
import { filter } from 'rxjs/operators';
import { DEFAULT_SEO } from './shared/seo-pages';
import { SeoConfig, SeoService } from './shared/seo.service';

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected title = 'website';

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private seo: SeoService
  ) {}

  ngOnInit() {
    // Scroll to top on route change
    this.router.events
      .pipe(filter((event) => event instanceof NavigationEnd))
      .subscribe((event) => {
        if (typeof window !== 'undefined') {
          window.scrollTo(0, 0);
        }
        this.setRouteSeo(event as NavigationEnd);
      });
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
