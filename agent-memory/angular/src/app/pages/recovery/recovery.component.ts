import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-recovery',
  standalone: true,
  imports: [DatePipe],
  template: `
    <h1 class="page-title">Recovery</h1>
    <div class="agents-grid">
      @for (agent of agents; track agent.name) {
        <div class="agent-card">
          <div class="agent-header">
            <span class="agent-name">{{ agent.name }}</span>
            <span class="badge" [class]="'badge-' + getStatus(agent).toLowerCase()">{{ getStatus(agent) }}</span>
          </div>
          <div class="agent-detail">
            <span class="detail-label">First Seen</span>
            <span class="detail-value">{{ agent.firstSeen | date:'medium' }}</span>
          </div>
          <div class="agent-detail">
            <span class="detail-label">Total Operations</span>
            <span class="detail-value">{{ agent.totalOps || 0 }}</span>
          </div>
        </div>
      }
      @if (agents.length === 0) {
        <div class="empty">No agents registered</div>
      }
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .agents-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
    .agent-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .agent-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .agent-name { font-size: 15px; font-weight: 700; color: var(--accent); }
    .badge { padding: 2px 10px; border-radius: 9999px; font-size: 11px; font-weight: 600; }
    .badge-active { background: rgba(34,197,94,0.15); color: #22c55e; }
    .badge-stale { background: rgba(234,179,8,0.15); color: #eab308; }
    .badge-inactive { background: rgba(161,161,170,0.15); color: #a1a1aa; }
    .agent-detail { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
    .detail-label { color: var(--text-secondary); }
    .detail-value { color: var(--text-primary); }
    .empty { color: var(--text-secondary); font-size: 13px; grid-column: 1 / -1; }
  `]
})
export class RecoveryComponent implements OnInit {
  agents: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getAgents().subscribe(agents => {
      this.agents = agents;
      this.agents.forEach(a => {
        this.api.getAgentMetrics(a.name).subscribe(m => {
          a.totalOps = (m.totalWrites || 0) + (m.totalReads || 0);
        });
      });
    });
  }

  getStatus(agent: any): string {
    if (!agent.lastSeen) return 'Inactive';
    const diff = Date.now() - new Date(agent.lastSeen).getTime();
    if (diff < 300000) return 'Active';
    if (diff < 3600000) return 'Stale';
    return 'Inactive';
  }
}
