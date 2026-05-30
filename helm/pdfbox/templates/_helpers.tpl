{{/*
Expand the name of the chart.
*/}}
{{- define "pdfbox.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "pdfbox.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "pdfbox.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "pdfbox.labels" -}}
helm.sh/chart: {{ include "pdfbox.chart" . }}
{{ include "pdfbox.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "pdfbox.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pdfbox.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Service account name
*/}}
{{- define "pdfbox.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "pdfbox.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
The image reference (repository:tag), tag defaulting to the chart appVersion.
*/}}
{{- define "pdfbox.image" -}}
{{- $tag := .Values.image.tag | default .Chart.AppVersion -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end }}

{{/*
Health probe path, honouring PDFBOX_BASE_PATH from .Values.env.
*/}}
{{- define "pdfbox.healthPath" -}}
{{- $base := "" -}}
{{- if .Values.env -}}
{{- $base = .Values.env.PDFBOX_BASE_PATH | default "" -}}
{{- end -}}
{{- printf "%s/actuator/health" $base -}}
{{- end }}
