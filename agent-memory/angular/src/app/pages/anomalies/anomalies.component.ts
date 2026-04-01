import { Component, OnInit } from '@angular/core';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-anomalies',
  standalone: true,
  template: `
    <h1 class="page-title">Anomalies</h1>
    @if (anomalies.length === 0) {
      <div class="all-clear">
        <div class="clear-icon">&#10003;</div>
        <div class="clear-text">All clear - no anomalies detected</div>
      </div>
    }
    <div class="anomalies-grid">
      @for (anomaly of anomalies; track $index) {
        <div class="anomaly-card">
          <div class="anomaly-header">
            <span class="badge badge-type">{{ anomaly.type }}</span>
            <span class="badge" [class]="'badge-' + (anomaly.severity || 'medium').toLowerCase()">{{ anomaly.severity || 'Medium' }}</span>
          </div>
          <div class="anomaly-desc">{{ anomaly.description }}</div>
          @if (anomaly.affectedKeys?.length) {
            <div class="affected-keys">
              <span class="keys-label">Affected Keys:</span>
              @for (key of anomaly.affectedKeys; track key) {
                <span class="key-tag">{{ key }}</span>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .all-clear {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      padding: 64px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px;
    }
    .clear-icon { font-size: 48px; color: #22c55e; margin-bottom: 16px; }
    .clear-text { font-size: 16px; color: var(--text-secondary); }
    .anomalies-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; }
    .anomaly-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 20px; }
    .anomaly-header { display: flex; gap: 8px; margin-bottom: 12px; }
    .badge { padding: 2px 10px; border-radius: 9999px; font-size: 11px; font-weight: 600; }
    .badge-type { background: rgba(249,115,22,0.2); color: var(--accent); }
    .badge-high { background: rgba(239,68,68,0.15); color: #ef4444; }
    .badge-medium { background: rgba(234,179,8,0.15); color: #eab308; }
    .badge-low { background: rgba(34,197,94,0.15); color: #22c55e; }
    .anomaly-desc { font-size: 13px; color: var(--text-primary); margin-bottom: 12px; line-height: 1.5; }
    .affected-keys { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
    .keys-label { font-size: 11px; color: var(--text-secondary); }
    .key-tag { background: rgba(249,115,22,0.2); color: var(--accent); padding: 2px 8px; border-radius: 4px; font-size: 11px; }
  `]
})
export class AnomaliesComponent implements OnInit {
  anomalies: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getAnomalies().subscribe(a => this.anomalies = a);
  }
}
