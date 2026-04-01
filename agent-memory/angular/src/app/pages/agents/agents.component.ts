import { Component, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-agents',
  standalone: true,
  imports: [DecimalPipe],
  template: `
    <h1 class="page-title">Agents</h1>
    <div class="table-container">
      <table>
        <thead>
          <tr>
            <th>Agent</th>
            <th>Status</th>
            <th>Write Latency</th>
            <th>Read Latency</th>
            <th>Total Writes</th>
            <th>Total Reads</th>
            <th>Errors</th>
          </tr>
        </thead>
        <tbody>
          @for (agent of agents; track agent.name) {
            <tr>
              <td class="agent-name">{{ agent.name }}</td>
              <td><span class="badge badge-running">Running</span></td>
              <td>{{ agent.avgWriteLatency | number:'1.1-1' }} ms</td>
              <td>{{ agent.avgReadLatency | number:'1.1-1' }} ms</td>
              <td>{{ agent.totalWrites }}</td>
              <td>{{ agent.totalReads }}</td>
              <td>{{ agent.errors || 0 }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (agents.length === 0) {
        <div class="empty">No agents found</div>
      }
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .table-container { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 4px; overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 12px 16px; font-size: 11px; text-transform: uppercase; color: var(--text-secondary); font-weight: 600; letter-spacing: 0.05em; }
    td { padding: 12px 16px; font-size: 13px; border-bottom: 1px solid var(--border); }
    .agent-name { color: var(--accent); font-weight: 600; }
    .badge { padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 600; }
    .badge-running { background: rgba(34,197,94,0.15); color: #22c55e; }
    .empty { color: var(--text-secondary); font-size: 13px; padding: 24px 16px; }
  `]
})
export class AgentsComponent implements OnInit {
  agents: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getAgents().subscribe(agents => {
      this.agents = agents;
      this.agents.forEach(a => {
        this.api.getAgentMetrics(a.name).subscribe(m => Object.assign(a, m));
      });
    });
  }
}
