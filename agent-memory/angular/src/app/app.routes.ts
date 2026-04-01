import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'overview', pathMatch: 'full' },
  { path: 'overview', loadComponent: () => import('./pages/overview/overview.component').then(m => m.OverviewComponent) },
  { path: 'agents', loadComponent: () => import('./pages/agents/agents.component').then(m => m.AgentsComponent) },
  { path: 'memory-explorer', loadComponent: () => import('./pages/memory-explorer/memory-explorer.component').then(m => m.MemoryExplorerComponent) },
  { path: 'shared-memory', loadComponent: () => import('./pages/shared-memory/shared-memory.component').then(m => m.SharedMemoryComponent) },
  { path: 'performance', loadComponent: () => import('./pages/performance/performance.component').then(m => m.PerformanceComponent) },
  { path: 'analytics', loadComponent: () => import('./pages/analytics/analytics.component').then(m => m.AnalyticsComponent) },
  { path: 'audit-trail', loadComponent: () => import('./pages/audit-trail/audit-trail.component').then(m => m.AuditTrailComponent) },
  { path: 'knowledge-graph', loadComponent: () => import('./pages/knowledge-graph/knowledge-graph.component').then(m => m.KnowledgeGraphComponent) },
  { path: 'recovery', loadComponent: () => import('./pages/recovery/recovery.component').then(m => m.RecoveryComponent) },
  { path: 'anomalies', loadComponent: () => import('./pages/anomalies/anomalies.component').then(m => m.AnomaliesComponent) },
  { path: 'settings', loadComponent: () => import('./pages/settings/settings.component').then(m => m.SettingsComponent) },
];
