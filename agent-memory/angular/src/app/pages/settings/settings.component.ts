import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [FormsModule],
  template: `
    <h1 class="page-title">Settings</h1>
    <div class="settings-panel">
      <h2 class="section-title">Detection Thresholds</h2>
      <div class="form-group">
        <label class="form-label">Loop Detection Window (seconds)</label>
        <input class="form-input" type="number" [(ngModel)]="settings.loopWindowSeconds">
      </div>
      <div class="form-group">
        <label class="form-label">Loop Threshold (count)</label>
        <input class="form-input" type="number" [(ngModel)]="settings.loopThreshold">
      </div>
      <div class="form-group">
        <label class="form-label">Drift Threshold</label>
        <input class="form-input" type="number" step="0.01" [(ngModel)]="settings.driftThreshold">
      </div>
      <h2 class="section-title" style="margin-top: 24px;">WebSocket</h2>
      <div class="form-group">
        <label class="form-label">Throttle Interval (ms, 0 = immediate)</label>
        <input class="form-input" type="number" [(ngModel)]="settings.wsThrottleMs">
      </div>
      <div class="form-group">
        <label class="form-label">Default Topics (* = all)</label>
        <input class="form-input" type="text" [(ngModel)]="settings.wsDefaultTopics">
      </div>
      <button class="btn-save" (click)="save()">Save Settings</button>
      @if (saved) {
        <span class="save-confirm">Settings saved successfully</span>
      }
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .settings-panel { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 24px; max-width: 480px; }
    .section-title { font-size: 16px; font-weight: 600; margin: 0 0 20px; }
    .form-group { margin-bottom: 16px; }
    .form-label { display: block; font-size: 13px; color: var(--text-secondary); margin-bottom: 6px; }
    .form-input {
      width: 100%; padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--bg-secondary); color: var(--text-primary); font-size: 14px; outline: none;
    }
    .form-input:focus { border-color: var(--accent); }
    .btn-save {
      padding: 8px 24px; border: none; border-radius: 6px;
      background: var(--accent); color: white; font-size: 13px; font-weight: 600;
      cursor: pointer; margin-top: 8px;
    }
    .btn-save:hover { background: var(--accent-hover); }
    .save-confirm { margin-left: 12px; font-size: 13px; color: #22c55e; }
  `]
})
export class SettingsComponent implements OnInit {
  settings: any = { loopWindowSeconds: 60, loopThreshold: 5, driftThreshold: 0.3, wsThrottleMs: 0, wsDefaultTopics: '*' };
  saved = false;

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getSettings().subscribe(s => {
      if (s) this.settings = s;
    });
  }

  save() {
    this.saved = false;
    this.api.updateSettings(this.settings).subscribe(() => {
      this.saved = true;
      setTimeout(() => this.saved = false, 3000);
    });
  }
}
