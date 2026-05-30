import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CodeComponent } from '../code/code.component';

@Component({
  selector: 'app-kubernetes',
  imports: [CodeComponent, RouterLink],
  template: `
    <h2>Kubernetes (Helm)</h2>

    <p>
      A <strong>minimal Helm chart</strong> ships in the repository under <code>helm/pdfbox</code>. It
      deploys the multi-arch image with a Deployment + Service, sane probes pointing at
      <code>/actuator/health</code>, and an optional Ingress — nothing you don't need.
    </p>

    <h3>Install</h3>
    <app-code lang="bash"># from a checkout of the repo
helm install pdfbox ./helm/pdfbox

# override the image tag and run two replicas
helm install pdfbox ./helm/pdfbox \\
  --set image.tag=1.0.0 \\
  --set replicaCount=2</app-code>
    <p>Then port-forward to try it:</p>
    <app-code lang="bash">kubectl port-forward svc/pdfbox 8080:8080
curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_1B" \\
     -H "Content-Type: text/html" --data '&lt;h1&gt;Hello&lt;/h1&gt;' -o out.pdf</app-code>

    <h3>Key values</h3>
    <table>
      <thead>
        <tr><th>Value</th><th>Default</th><th>Purpose</th></tr>
      </thead>
      <tbody>
        <tr><td><code>image.repository</code></td><td><code>softwarity/pdfbox</code></td><td>Image to deploy.</td></tr>
        <tr><td><code>image.tag</code></td><td><code>""</code> (chart <code>appVersion</code>)</td><td>Image tag; pin a release in production.</td></tr>
        <tr><td><code>replicaCount</code></td><td><code>1</code></td><td>Number of pods.</td></tr>
        <tr><td><code>service.type</code></td><td><code>ClusterIP</code></td><td>Service type.</td></tr>
        <tr><td><code>service.port</code></td><td><code>8080</code></td><td>Service port (maps to container 8080).</td></tr>
        <tr><td><code>ingress.enabled</code></td><td><code>false</code></td><td>Set <code>true</code> to expose via an Ingress.</td></tr>
        <tr><td><code>env</code></td><td><code>{{ '{}' }}</code></td><td>Extra environment variables (the <code>PDFBOX_*</code> knobs).</td></tr>
        <tr><td><code>resources</code></td><td>requests/limits</td><td>CPU/memory; tune for your render volume.</td></tr>
        <tr><td><code>extraFonts.enabled</code></td><td><code>false</code></td><td>Mount a ConfigMap/PVC of <code>.ttf</code> at <code>/app/fonts</code>.</td></tr>
      </tbody>
    </table>

    <h3>A minimal values file</h3>
    <p>Create <code>my-values.yaml</code> and install with <code>-f my-values.yaml</code>:</p>
    <app-code lang="yaml">replicaCount: 2

image:
  repository: softwarity/pdfbox
  tag: "1.0.0"

# the PDFBOX_* knobs from the Configuration page
env:
  PDFBOX_DEFAULT_STANDARD: PDF_A_2U
  PDFBOX_DEFAULT_LANG: en

resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: "1"
    memory: 1Gi

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: pdfbox.example.com
      paths:
        - path: /
          pathType: Prefix</app-code>

    <div class="callout">
      The chart sets a <strong>readiness</strong> and <strong>liveness</strong> probe on
      <code>/actuator/health</code> with a generous <code>startupProbe</code> — the JVM plus the font
      index takes a few seconds to warm up. If you set <code>PDFBOX_BASE_PATH</code>, the probe path is
      prefixed automatically.
    </div>

    <h3>Behind an Ingress with a base path</h3>
    <p>
      To serve pdfbox under a sub-path (e.g. <code>https://host/pdfbox</code>), set both the env var and
      the Ingress path so they agree — see <a routerLink="/configuration">Configuration</a>:
    </p>
    <app-code lang="yaml">env:
  PDFBOX_BASE_PATH: /pdfbox

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: host.example.com
      paths:
        - path: /pdfbox
          pathType: Prefix</app-code>

    <h3>Extra fonts in the cluster</h3>
    <p>
      Need a script the jar doesn't bundle (or CJK on the bare jar)? Provide your <code>.ttf</code>
      files via a ConfigMap or PVC and enable the mount. The chart wires it to <code>/app/fonts</code>:
    </p>
    <app-code lang="yaml">extraFonts:
  enabled: true
  # mount an existing ConfigMap holding .ttf files (binaryData)
  configMap: my-extra-fonts</app-code>
    <p>Remember: TrueType only, and freeze variable fonts first (<a routerLink="/fonts">Fonts &amp; scripts</a>).</p>

    <h3>Uninstall</h3>
    <app-code lang="bash">helm uninstall pdfbox</app-code>
  `,
})
export class KubernetesComponent {}
