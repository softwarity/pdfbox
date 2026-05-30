import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-getting-started',
  imports: [CodeComponent, RouterLink],
  styles: [
    `
      .features {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
        gap: 12px;
        margin: 0 0 28px 0;
      }
      .feature-card {
        display: flex;
        flex-direction: column;
        gap: 6px;
        padding: 14px 16px;
        background-color: var(--bg-secondary);
        border: 1px solid var(--border-color);
        border-radius: 8px;
        text-decoration: none;
        transition: all 0.15s;
      }
      .feature-card:hover {
        border-color: var(--accent-purple);
        background-color: rgba(163, 113, 247, 0.1);
        text-decoration: none;
        transform: translateY(-1px);
      }
      .feature-icon {
        font-size: 1.3rem;
        line-height: 1;
      }
      .feature-title {
        font-weight: 600;
        color: var(--text-primary);
        font-size: 0.95rem;
      }
      .feature-desc {
        color: var(--text-secondary);
        font-size: 0.85rem;
        line-height: 1.45;
      }
      .feature-desc code {
        font-size: 0.85em;
      }
    `,
  ],
  template: `
    <h2>Getting started</h2>

    <p>
      <strong>pdfbox</strong> is a single, self-contained <strong>Spring Boot</strong> service that
      converts <strong>HTML to PDF — including PDF/A</strong> (parts 1/2/3, levels A/B/U). The whole
      public contract is one line:
    </p>

    <app-code lang="text">POST  your HTML  ──►  pdfbox  ──►  PDF / PDF/A binary</app-code>

    <div class="callout">
      <strong>Fully offline.</strong> No external services, no runtime configuration. A broad Noto
      font set and the sRGB colour profile are bundled, so multi-script PDF/A generation works the
      same on your laptop, in the jar and in the container. Distributed as a multi-arch Docker image
      for <code>linux/amd64</code> and <code>linux/arm64</code>.
    </div>

    <h3>What you can do with it</h3>
    <section class="features">
      <a routerLink="/endpoints" class="feature-card">
        <span class="feature-icon">🔌</span>
        <span class="feature-title">Two endpoints</span>
        <span class="feature-desc">POST raw HTML or upload an HTML file; get <code>application/pdf</code> back.</span>
      </a>
      <a routerLink="/standards" class="feature-card">
        <span class="feature-icon">🏆</span>
        <span class="feature-title">PDF/A 1·2·3</span>
        <span class="feature-desc">Levels <code>A</code> (tagged), <code>B</code> (visual), <code>U</code> (Unicode) — archival-ready.</span>
      </a>
      <a routerLink="/fonts" class="feature-card">
        <span class="feature-icon">🌐</span>
        <span class="feature-title">Exotic scripts</span>
        <span class="feature-desc">Vietnamese, Hebrew, Arabic, Thai, Devanagari, CJK… fonts embedded.</span>
      </a>
      <a routerLink="/docker" class="feature-card">
        <span class="feature-icon">🧩</span>
        <span class="feature-title">amd64 · arm64</span>
        <span class="feature-desc">Native multi-arch image, no QEMU at runtime; runs on Graviton / Apple silicon.</span>
      </a>
      <a routerLink="/configuration" class="feature-card">
        <span class="feature-icon">⚙️</span>
        <span class="feature-title">Zero config</span>
        <span class="feature-desc">Sensible defaults; override a handful of env vars only if you need to.</span>
      </a>
      <a routerLink="/kubernetes" class="feature-card">
        <span class="feature-icon">☸️</span>
        <span class="feature-title">Helm chart</span>
        <span class="feature-desc">A minimal chart ships in the repo to deploy on Kubernetes in one command.</span>
      </a>
    </section>

    <h3>1. Run it (Docker)</h3>
    <p>The fastest way to a running service — nothing to install but Docker:</p>
    <app-code lang="bash">docker run --rm -p 8080:8080 softwarity/pdfbox:latest</app-code>
    <p>
      Open <a href="http://localhost:8080" target="_blank" rel="noopener">http://localhost:8080</a> for
      a small interactive test page, or call the API directly (next step).
    </p>

    <h3>2. Your first PDF</h3>
    <p>Send HTML in the request body, pick a standard with <code>?standard=</code>, receive the binary:</p>
    <app-code lang="bash">curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_1B" \\
     -H "Content-Type: text/html" \\
     --data '&lt;h1&gt;Hello&lt;/h1&gt;&lt;p&gt;Tiếng Việt · עברית · 日本語&lt;/p&gt;' \\
     -o document.pdf</app-code>
    <p>
      That is the entire contract: <em>send HTML, pick a standard, get
      <code>application/pdf</code></em>. See <a routerLink="/endpoints">API &amp; endpoints</a> for the
      upload form and every parameter.
    </p>

    <h3>3. Run from sources (optional)</h3>
    <p>Requires <strong>JDK 21</strong>. A broad Noto set is bundled in the jar, so multi-script output works offline too:</p>
    <app-code lang="bash">mvn spring-boot:run                          # run from sources at :8080
# or build the fat jar and run it
mvn package &amp;&amp; java -jar target/pdfbox.jar</app-code>

    <h3>Compatibility</h3>
    <ul>
      <li>Runtime: <strong>JDK 21</strong> (the Docker image bundles a Temurin 21 JRE).</li>
      <li>Architectures: <code>linux/amd64</code> and <code>linux/arm64</code>.</li>
      <li>No database, no broker, no external network access required at runtime.</li>
    </ul>

    <h3>Where to next</h3>
    <p>
      Skim <a routerLink="/endpoints">API &amp; endpoints</a> for the full request reference,
      <a routerLink="/standards">PDF/A standards</a> to choose a conformance level,
      <a routerLink="/fonts">Fonts &amp; scripts</a> for multi-language output,
      <a routerLink="/configuration">Configuration</a> for the env vars,
      <a routerLink="/docker">Docker &amp; architectures</a> for image details, or
      <a routerLink="/kubernetes">Kubernetes (Helm)</a> to deploy on a cluster.
    </p>
  `,
})
export class GettingStartedComponent {}
