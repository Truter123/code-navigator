import { Component, OnInit, OnDestroy } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/ws.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-performance',
  standalone: true,
  imports: [DatePipe, DecimalPipe],
  template: `
    <h1 class="page-title">Performance</h1>
    <div class="cards-grid">
      <div class="card">
        <div class="card-label">Avg Latency</div>
        <div class="card-value">{{ summary.avgLatency | number:'1.1-1' }} ms</div>
      </div>
      <div class="card">
        <div class="card-label">Total Ops</div>
        <div class="card-value">{{ summary.totalOps }}</div>
      </div>
      <div class="card">
        <div class="card-label">Writes</div>
        <div class="card-value">{{ summary.totalWrites }}</div>
      </div>
      <div class="card">
        <div class="card-label">Reads</div>
        <div class="card-value">{{ summary.totalReads }}</div>
      </div>
    </div>
    <div class="table-container">
      <h2 class="section-title">Recent Operations</h2>
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
          @for (op of timeseries; track $index) {
            <tr>
              <td class="time-cell">{{ op.timestamp | date:'medium' }}</td>
              <td class="agent-cell">{{ op.agentName }}</td>
              <td>{{ op.operation }}</td>
              <td>{{ op.key }}</td>
              <td>{{ op.latencyMs | number:'1.1-1' }} ms</td>
            </tr>
          }
        </tbody>
      </table>
      @if (timeseries.length === 0) {
        <div class="empty">No operations recorded</div>
      }
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .cards-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
    .card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .card-label { font-size: 12px; text-transform: uppercase; color: var(--text-secondary); margin-bottom: 8px; }
    .card-value { font-size: 28px; font-weight: 700; color: var(--accent); }
    .table-container { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .section-title { font-size: 16px; font-weight: 600; margin: 0 0 16px; }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 10px 16px; font-size: 11px; text-transform: uppercase; color: var(--text-secondary); font-weight: 600; }
    td { padding: 10px 16px; font-size: 13px; border-bottom: 1px solid var(--border); }
    .time-cell { color: var(--text-secondary); font-size: 12px; }
    .agent-cell { color: var(--accent); font-weight: 600; }
    .empty { color: var(--text-secondary); font-size: 13px; padding: 24px 16px; }
  `]
})
export class PerformanceComponent implements OnInit, OnDestroy {
  summary: any = { avgLatency: 0, totalOps: 0, totalWrites: 0, totalReads: 0 };
  timeseries: any[] = [];
  private subs: Subscription[] = [];

  constructor(private api: ApiService, private ws: WebSocketService) {}

  ngOnInit() {
    this.api.getPerformanceSummary().subscribe(s => this.summary = s);
    this.api.getTimeseries({ limit: 20 }).subscribe(t => this.timeseries = t);
    this.subs.push(
      this.ws.on('audit').subscribe(e => {
        this.timeseries.unshift(e.data);
        if (this.timeseries.length > 20) this.timeseries.pop();
        this.summary.totalOps = (this.summary.totalOps || 0) + 1;
        if (['store','delete','link','share','goals'].includes(e.data.operation)) {
          this.summary.totalWrites = (this.summary.totalWrites || 0) + 1;
        } else {
          this.summary.totalReads = (this.summary.totalReads || 0) + 1;
        }
      })
    );
  }

  ngOnDestroy() { this.subs.forEach(s => s.unsubscribe()); }
}
