import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import Prism from 'prismjs';
import { CodeComponent } from '../code/code.component';

/**
 * One showcase document. The HTML, PDF and PNG under assets/showcase are written by the backend test
 * `ShowcaseTest` (`mvn test -Dtest=ShowcaseTest -Dshowcase.write=true`), which also checks that each
 * one conforms to its standard and produces the page sizes described here.
 */
interface Example {
  id: string;
  title: string;
  standard: string;
  pages: string;
  description: string;
}

@Component({
  selector: 'app-examples',
  imports: [CodeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2>Examples</h2>

    <p>
      Each example below is an HTML file rendered by the service as-is: the image shows every page of
      the resulting PDF at the same scale, so an A3 page looks bigger than an A5 one. They are produced
      by the test suite, which also checks that every PDF conforms to its standard (veraPDF) and has
      exactly the page sizes listed — click an image to open the PDF.
    </p>

    <div class="callout warn">
      <strong>Mixing page sizes or orientations.</strong> A named page (<code>page: wide</code> +
      <code>&#64;page wide &#123; size: A4 landscape &#125;</code>) changes the size of the pages it
      starts, but blocks are still laid out against the width of the <em>default</em> page. Give the
      block that lives on a wider (or narrower) page an explicit <code>width</code> equal to that page's
      content width (page width minus its left and right margins) — e.g. <code>width: 267mm</code> for
      A4 landscape with 15&nbsp;mm margins — and <code>width: 100%</code> children then fill it. A
      document that is landscape throughout needs nothing: just set the default
      <code>&#64;page &#123; size: A4 landscape &#125;</code>.
    </div>

    @for (example of examples; track example.id) {
      <section class="example">
        <h3>{{ example.title }}</h3>
        <div class="meta">
          <span class="badge">{{ example.standard }}</span>
          <span class="pages">{{ example.pages }}</span>
        </div>
        <p>{{ example.description }}</p>
        <a class="sheet" [href]="'assets/showcase/' + example.id + '.pdf'" target="_blank" rel="noopener">
          <img [src]="'assets/showcase/' + example.id + '.png'" [alt]="example.title + ' — every page of the PDF'"
               loading="lazy" />
        </a>
        <div class="links">
          <a [href]="'assets/showcase/' + example.id + '.pdf'" target="_blank" rel="noopener">Open the PDF</a>
          <a [href]="'assets/showcase/' + example.id + '.html'" target="_blank" rel="noopener">Raw HTML</a>
        </div>
        <details (toggle)="load(example.id)">
          <summary>HTML source</summary>
          @if (sources()[example.id]; as source) {
            <pre class="language-markup"><code class="language-markup" [innerHTML]="source"></code></pre>
          } @else {
            <p class="loading">Loading…</p>
          }
        </details>
      </section>
    }

    <h3>Try one yourself</h3>
    <p>Download any of the HTML sources above and post it to a running instance:</p>
    <app-code lang="bash">curl -H 'Content-Type: text/html' --data-binary &#64;orientation.html \\
     'http://localhost:8080/api/v1/pdf?standard=PDF_A_1B' -o orientation.pdf</app-code>
  `,
  styles: [
    `
      .example {
        margin: 0 0 40px 0;
      }
      .meta {
        display: flex;
        gap: 12px;
        align-items: center;
        margin: -4px 0 12px 0;
        font-size: 0.85em;
        color: var(--text-muted);
      }
      .badge {
        background: rgba(163, 113, 247, 0.15);
        color: var(--accent-purple);
        padding: 2px 8px;
        border-radius: 10px;
        font-family: 'Courier New', Consolas, monospace;
      }
      .sheet {
        display: block;
        overflow-x: auto;
        border-radius: 6px;
        border: 1px solid var(--border-color);
      }
      .sheet img {
        display: block;
        max-width: 100%;
        height: auto;
      }
      .links {
        display: flex;
        gap: 16px;
        margin: 8px 0;
        font-size: 0.9em;
      }
      details {
        margin-top: 8px;
      }
      summary {
        cursor: pointer;
        color: var(--text-secondary);
        margin-bottom: 8px;
      }
      details pre {
        max-height: 480px;
        overflow: auto;
      }
      .loading {
        color: var(--text-muted);
      }
    `,
  ],
})
export class ExamplesComponent {
  protected readonly examples: Example[] = [
    {
      id: 'orientation',
      title: 'Portrait and landscape in one document',
      standard: 'PDF_A_1B',
      pages: 'A4 portrait · A4 landscape · A4 portrait',
      description:
        'A named page switches one section to A4 landscape for a wide table, then the flow returns to portrait.',
    },
    {
      id: 'page-sizes',
      title: 'Page sizes',
      standard: 'PDF_A_2B',
      pages: 'A4 · A5 · A3 landscape · Letter · Legal landscape · 85 × 55 mm',
      description:
        'Standard ISO and US sizes, orientations and a custom size (a business card), all in one PDF via named pages.',
    },
    {
      id: 'headers-footers',
      title: 'Headers, footers and page numbers',
      standard: 'PDF_A_3A',
      pages: '3 × A4',
      description:
        'Page margin boxes: a running header taken from the document, a fixed footer, "Page n / total", and a cover page without header (@page :first). Tagged (accessible) PDF/A-3A.',
    },
    {
      id: 'long-table',
      title: 'Long table across landscape pages',
      standard: 'PDF_A_2B',
      pages: '4 × A4 landscape',
      description:
        'A 70-row table that breaks across pages, repeats its header row on each page (-fs-table-paginate) and never splits a row.',
    },
    {
      id: 'svg-chart',
      title: 'Inline SVG chart',
      standard: 'PDF_A_1B',
      pages: 'A5 landscape',
      description: 'A bar chart drawn in inline SVG — gradient, polyline, text — kept as vector graphics.',
    },
    {
      id: 'scripts',
      title: 'Many scripts, no font declaration',
      standard: 'PDF_A_2U',
      pages: 'A5',
      description:
        'Latin with diacritics, Vietnamese, Greek, Cyrillic, Hebrew, Tamil, Thai, Georgian, Armenian, Ethiopic, Japanese and symbols: the matching Noto faces are picked and embedded automatically.',
    },
  ];

  protected readonly sources = signal<Record<string, string>>({});

  protected async load(id: string): Promise<void> {
    if (this.sources()[id]) {
      return;
    }
    const response = await fetch(`assets/showcase/${id}.html`);
    const html = await response.text();
    const highlighted = Prism.highlight(html, Prism.languages['markup'], 'markup');
    this.sources.update((all) => ({ ...all, [id]: highlighted }));
  }
}
