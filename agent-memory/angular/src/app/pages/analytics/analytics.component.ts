import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-analytics',
  standalone: true,
  template: `
    <h1 class="page-title">Analytics</h1>
    <div class="analytics-grid">
      <div class="panel">
        <h2 class="section-title">Most Active Agents</h2>
        @for (agent of agentStats; track agent.name) {
          <div class="bar-row">
            <span class="bar-label">{{ agent.name }}</span>
            <div class="bar-track">
              <div class="bar-fill" [style.width.%]="agent.pct"></div>
            </div>
            <span class="bar-value">{{ agent.totalOps }}</span>
          </div>
        }
        @if (agentStats.length === 0) {
          <div class="empty">No data</div>
        }
      </div>
      <div class="panel">
        <h2 class="section-title">Memory Stats</h2>
        <div class="stat-row">
          <span class="stat-label">Total Memories</span>
          <span class="stat-value">{{ totalMemories }}</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Shared Memories</span>
          <span class="stat-value">{{ sharedMemories }}</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Active Agents</span>
          <span class="stat-value">{{ totalAgents }}</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Anomalies Detected</span>
          <span class="stat-value">{{ totalAnomalies }}</span>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .analytics-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .panel { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .section-title { font-size: 16px; font-weight: 600; margin: 0 0 16px; }
    .bar-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
    .bar-label { font-size: 13px; min-width: 100px; color: var(--text-primary); }
    .bar-track { flex: 1; height: 20px; background: var(--bg-secondary); border-radius: 4px; overflow: hidden; }
    .bar-fill { height: 100%; background: var(--accent); border-radius: 4px; transition: width 0.3s; }
    .bar-value { font-size: 13px; color: var(--text-secondary); min-width: 40px; text-align: right; }
    .stat-row { display: flex; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid var(--border); font-size: 14px; }
    .stat-label { color: var(--text-secondary); }
    .stat-value { color: var(--accent); font-weight: 700; }
    .empty { color: var(--text-secondary); font-size: 13px; }
  `]
})
export class AnalyticsComponent implements OnInit {
  agentStats: any[] = [];
  totalMemories = 0;
  sharedMemories = 0;
  totalAgents = 0;
  totalAnomalies = 0;

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getAgents().subscribe(agents => {
      this.totalAgents = agents.length;
      let maxOps = 0;
      const stats: any[] = [];
      let pending = agents.length;
      if (pending === 0) return;
      agents.forEach(a => {
        this.api.getAgentMetrics(a.name).subscribe(m => {
          const totalOps = (m.totalWrites || 0) + (m.totalReads || 0);
          stats.push({ name: a.name, totalOps });
          if (totalOps > maxOps) maxOps = totalOps;
          pending--;
          if (pending === 0) {
            this.agentStats = stats
              .sort((a, b) => b.totalOps - a.totalOps)
              .map(s => ({ ...s, pct: maxOps > 0 ? (s.totalOps / maxOps) * 100 : 0 }));
          }
        });
      });
    });
    this.api.getMemories().subscribe(m => {
      this.totalMemories = m.length;
      this.sharedMemories = m.filter((x: any) => x.shared).length;
    });
    this.api.getAnomalies().subscribe(a => this.totalAnomalies = a.length);
  }
}
