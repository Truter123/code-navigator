import { Component, OnInit } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-audit-trail',
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  template: `
    <h1 class="page-title">Audit Trail</h1>
    <div class="table-container">
      <div class="filters">
        <select class="filter-select" [(ngModel)]="filterAgent" (ngModelChange)="loadAudit()">
          <option value="">All Agents</option>
          @for (agent of agents; track agent.name) {
            <option [value]="agent.name">{{ agent.name }}</option>
          }
        </select>
        <select class="filter-select" [(ngModel)]="filterOperation" (ngModelChange)="loadAudit()">
          <option value="">All Operations</option>
          <option value="WRITE">WRITE</option>
          <option value="READ">READ</option>
          <option value="DELETE">DELETE</option>
        </select>
      </div>
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Agent</th>
            <th>Operation</th>
            <th>Key</th>
            <th>Latency</th>
          </tr>
        </thead>
        <tbody>
          @for (entry of auditLog; track $index) {
            <tr>
              <td class="time-cell">{{ entry.timestamp | date:'medium' }}</td>
              <td class="agent-cell">{{ entry.agentName }}</td>
              <td>{{ entry.operation }}</td>
              <td>{{ entry.key }}</td>
              <td>{{ entry.latencyMs | number:'1.1-1' }} ms</td>
            </tr>
          }
        </tbody>
      </table>
      @if (auditLog.length === 0) {
        <div class="empty">No audit entries</div>
      }
      <div class="pagination">
        <button class="btn" [disabled]="page === 0" (click)="prevPage()">Previous</button>
        <span class="page-info">Page {{ page + 1 }}</span>
        <button class="btn" [disabled]="auditLog.length < pageSize" (click)="nextPage()">Next</button>
      </div>
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .table-container { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .filters { display: flex; gap: 8px; margin-bottom: 16px; }
    .filter-select {
      padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--bg-secondary); color: var(--text-primary); font-size: 13px; outline: none;
    }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 10px 16px; font-size: 11px; text-transform: uppercase; color: var(--text-secondary); font-weight: 600; }
    td { padding: 10px 16px; font-size: 13px; border-bottom: 1px solid var(--border); }
    .time-cell { color: var(--text-secondary); font-size: 12px; }
    .agent-cell { color: var(--accent); font-weight: 600; }
    .pagination { display: flex; align-items: center; gap: 12px; margin-top: 16px; justify-content: center; }
    .page-info { color: var(--text-secondary); font-size: 13px; }
    .btn {
      padding: 6px 16px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--bg-secondary); color: var(--text-primary); font-size: 13px; cursor: pointer;
    }
    .btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .btn:not(:disabled):hover { border-color: var(--accent); }
    .empty { color: var(--text-secondary); font-size: 13px; padding: 24px 16px; }
  `]
})
export class AuditTrailComponent implements OnInit {
  auditLog: any[] = [];
  agents: any[] = [];
  filterAgent = '';
  filterOperation = '';
  page = 0;
  pageSize = 20;

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getAgents().subscribe(a => this.agents = a);
    this.loadAudit();
  }

  loadAudit() {
    const params: any = { limit: this.pageSize, offset: this.page * this.pageSize };
    if (this.filterAgent) params.agent = this.filterAgent;
    if (this.filterOperation) params.operation = this.filterOperation;
    this.api.getAuditLog(params).subscribe(a => this.auditLog = a);
  }

  prevPage() { if (this.page > 0) { this.page--; this.loadAudit(); } }
  nextPage() { this.page++; this.loadAudit(); }
}
