import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./pages/getting-started.component').then((m) => m.GettingStartedComponent),
  },
  {
    path: 'endpoints',
    loadComponent: () => import('./pages/endpoints.component').then((m) => m.EndpointsComponent),
  },
  {
    path: 'standards',
    loadComponent: () => import('./pages/standards.component').then((m) => m.StandardsComponent),
  },
  {
    path: 'fonts',
    loadComponent: () => import('./pages/fonts.component').then((m) => m.FontsComponent),
  },
  {
    path: 'configuration',
    loadComponent: () => import('./pages/configuration.component').then((m) => m.ConfigurationComponent),
  },
  {
    path: 'docker',
    loadComponent: () => import('./pages/docker.component').then((m) => m.DockerComponent),
  },
  {
    path: 'kubernetes',
    loadComponent: () => import('./pages/kubernetes.component').then((m) => m.KubernetesComponent),
  },
  {
    path: 'how-it-works',
    loadComponent: () => import('./pages/how-it-works.component').then((m) => m.HowItWorksComponent),
  },
  { path: '**', redirectTo: '' },
];
