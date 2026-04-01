import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-overview',
  standalone: true,
  imports: [DatePipe],
  template: `
    <h1 class="page-title">Overview</h1>
    <div class="cards-grid">
      <div class="card">
        <div class="card-label">Total Memories</div>
        <div class="card-value">{{ totalMemories }}</div>
      </div>
      <div class="card">
        <div class="card-label">Active Agents</div>
        <div class="card-value">{{ activeAgents }}</div>
      </div>
      <div class="card">
        <div class="card-label">Anomalies</div>
        <div class="card-value">{{ anomalyCount }}</div>
      </div>
      <div class="card">
        <div class="card-label">Shared Memories</div>
        <div class="card-value">{{ sharedMemories }}</div>
      </div>
    </div>
    <div class="section">
      <h2 class="section-title">Recent Activity</h2>
      <div class="activity-list">
        @for (entry of recentActivity; track $index) {
          <div class="activity-item">
            <span class="activity-agent">{{ entry.agentName }}</span>
            <span class="activity-op">{{ entry.operation }}</span>
            <span class="activity-key">{{ entry.key }}</span>
            <span class="activity-time">{{ entry.timestamp | date:'short' }}</span>
          </div>
        }
        @if (recentActivity.length === 0) {
          <div class="empty">No recent activity</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .cards-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 32px; }
    .card {
      background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px;
    }
    .card-label { font-size: 12px; text-transform: uppercase; color: var(--text-secondary); margin-bottom: 8px; }
    .card-value { font-size: 28px; font-weight: 700; color: var(--accent); }
    .section { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .section-title { font-size: 16px; font-weight: 600; margin: 0 0 16px; }
    .activity-list { display: flex; flex-direction: column; gap: 8px; }
    .activity-item {
      display: flex; align-items: center; gap: 12px; padding: 10px 0;
      border-bottom: 1px solid var(--border); font-size: 13px;
    }
    .activity-agent { color: var(--accent); font-weight: 600; min-width: 120px; }
    .activity-op { color: var(--text-secondary); min-width: 80px; }
    .activity-key { flex: 1; color: var(--text-primary); }
    .activity-time { color: var(--text-secondary); font-size: 12px; }
    .empty { color: var(--text-secondary); font-size: 13px; padding: 16px 0; }
  `]
})
export class OverviewComponent implements OnInit {
  totalMemories = 0;
  activeAgents = 0;
  anomalyCount = 0;
  sharedMemories = 0;
  recentActivity: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getMemories().subscribe(m => {
      this.totalMemories = m.length;
      this.sharedMemories = m.filter((x: any) => x.shared).length;
    });
    this.api.getAgents().subscribe(a => this.activeAgents = a.length);
    this.api.getAnomalies().subscribe(a => this.anomalyCount = a.length);
    this.api.getAuditLog({ limit: 10 }).subscribe(a => this.recentActivity = a);
  }
}
