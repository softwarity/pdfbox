import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-docker',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>Docker &amp; architectures</h2>

    <p>
      The published image is a <strong>multi-arch manifest list</strong> covering
      <code>linux/amd64</code> and <code>linux/arm64</code>. Docker automatically pulls the variant
      matching your host, so the same tag runs on a regular x86 server, on AWS Graviton, on a Raspberry
      Pi or on Apple-silicon laptops — no flags needed.
    </p>

    <app-code lang="bash">docker run --rm -p 8080:8080 softwarity/pdfbox:latest</app-code>

    <div class="callout">
      <strong>Native builds, no emulation.</strong> Each architecture is built on its <em>own</em>
      native CI runner (amd64 and arm64) and pushed by digest; a final step assembles the manifest
      list. There is no QEMU translation at build or run time, so arm64 performance is first-class.
    </div>

    <h3>Image &amp; tags</h3>
    <p>Image: <code>docker.io/softwarity/pdfbox</code>.</p>
    <table>
      <thead>
        <tr><th>Tag</th><th>What it points to</th></tr>
      </thead>
      <tbody>
        <tr><td><code>latest</code></td><td>The latest build of the default branch.</td></tr>
        <tr><td><code>X.Y.Z</code></td><td>A specific released version (recommended for production).</td></tr>
        <tr><td><code>X.Y</code></td><td>The latest patch of a minor line.</td></tr>
        <tr><td><code>&lt;branch&gt;</code></td><td>The head of a branch (CI builds).</td></tr>
      </tbody>
    </table>

    <h3>Pin a version and an architecture</h3>
    <app-code lang="bash"># pin a released version (recommended)
docker run --rm -p 8080:8080 softwarity/pdfbox:1.0.0

# force a specific architecture (e.g. to test arm64 on a multi-arch host)
docker run --rm -p 8080:8080 --platform linux/arm64 softwarity/pdfbox:latest

# inspect the manifest list to see the available architectures
docker buildx imagetools inspect softwarity/pdfbox:latest</app-code>

    <h3>Health check</h3>
    <p>
      The image ships a <code>HEALTHCHECK</code> that curls <code>/actuator/health</code> (honouring
      <code>PDFBOX_BASE_PATH</code>). Orchestrators can use the same endpoint for liveness/readiness —
      see <a routerLink="/kubernetes">Kubernetes (Helm)</a>.
    </p>
    <app-code lang="bash">curl -fsS http://localhost:8080/actuator/health
# {{ '{' }}"status":"UP"{{ '}' }}</app-code>

    <h3>Drop-in fonts</h3>
    <p>
      Mount extra <code>.ttf</code> files at <code>/app/fonts</code> (a directory the service scans at
      startup) to add scripts or CJK to the bare image:
    </p>
    <app-code lang="bash">docker run --rm -p 8080:8080 \\
  -v "\$PWD/my-fonts:/app/fonts:ro" \\
  softwarity/pdfbox:latest</app-code>
    <p>Only TrueType is embeddable, and variable fonts must be frozen first — see <a routerLink="/fonts">Fonts &amp; scripts</a>.</p>

    <h3>docker compose</h3>
    <app-code lang="yaml">services:
  pdfbox:
    image: softwarity/pdfbox:latest
    ports:
      - "8080:8080"
    environment:
      PDFBOX_DEFAULT_STANDARD: PDF_A_2U
    # volumes:
    #   - ./my-fonts:/app/fonts:ro</app-code>

    <h3>Build the image yourself</h3>
    <p>
      The <code>Dockerfile</code> is a multi-stage Maven → JRE build (with a side stage that freezes the
      Noto CJK variable fonts to a static <code>wght=400</code> instance). To build for your current
      architecture:
    </p>
    <app-code lang="bash">docker build -t pdfbox:dev .</app-code>
    <p>To build a multi-arch image locally with buildx:</p>
    <app-code lang="bash">docker buildx build --platform linux/amd64,linux/arm64 -t pdfbox:dev .</app-code>
  `,
})
export class DockerComponent {}
