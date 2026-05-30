# pdfbox Helm chart

A minimal Helm chart to deploy [pdfbox](https://github.com/softwarity/pdfbox) — a self-contained
Spring Boot service that converts **HTML to PDF, including PDF/A** — on Kubernetes.

It deploys the multi-arch (`amd64` / `arm64`) image with a Deployment + Service, health probes on
`/actuator/health`, and an optional Ingress. Nothing you don't need.

## Install

```bash
# from a checkout of the repo
helm install pdfbox ./helm/pdfbox

# pin a released image and run two replicas
helm install pdfbox ./helm/pdfbox --set image.tag=1.0.0 --set replicaCount=2

# with your own values file
helm install pdfbox ./helm/pdfbox -f my-values.yaml
```

Try it:

```bash
kubectl port-forward svc/pdfbox 8080:8080
curl -X POST "http://localhost:8080/api/v1/pdf?standard=PDF_A_1B" \
     -H "Content-Type: text/html" --data '<h1>Hello</h1>' -o out.pdf
```

## Common values

| Value | Default | Purpose |
|---|---|---|
| `replicaCount` | `1` | Number of pods. |
| `image.repository` | `softwarity/pdfbox` | Image to deploy. |
| `image.tag` | `""` (chart `appVersion`) | Image tag; pin a release in production. |
| `service.type` | `ClusterIP` | Service type. |
| `service.port` | `8080` | Service port (maps to container `8080`). |
| `ingress.enabled` | `false` | Expose via an Ingress. |
| `env` | `{}` | Extra env vars (the `PDFBOX_*` knobs). |
| `resources` | requests/limits | CPU/memory tuning. |
| `autoscaling.enabled` | `false` | Horizontal Pod Autoscaler. |
| `extraFonts.enabled` | `false` | Mount a ConfigMap/PVC of `.ttf` files at `/app/fonts`. |

See [`values.yaml`](./values.yaml) for the full list, and the
[online docs](https://softwarity.github.io/pdfbox/) (Kubernetes page) for recipes (base path,
Ingress, extra fonts).

## Notes

- Probes target `/actuator/health` and automatically honour `env.PDFBOX_BASE_PATH` if you set one.
- Extra fonts must be **TrueType** (`.ttf`); freeze variable fonts to a static instance first.
- The chart is intentionally minimal — fork/extend it for org-specific needs (NetworkPolicy, PDB, …).
