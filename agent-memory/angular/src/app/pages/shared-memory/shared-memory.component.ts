import { Component, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-shared-memory',
  standalone: true,
  imports: [DatePipe, FormsModule],
  template: `
    <h1 class="page-title">Shared Memory</h1>
    <div class="explorer-layout">
      <div class="memory-list-panel">
        <div class="filters">
          <input class="filter-input" placeholder="Search shared keys..." [(ngModel)]="searchQuery" (input)="filterMemories()">
        </div>
        <div class="memory-items">
          @for (mem of filteredMemories; track mem.id) {
            <div class="memory-item" [class.selected]="selectedMemory?.id === mem.id" (click)="selectMemory(mem)">
              <div class="memory-key">{{ mem.key }}</div>
              <div class="memory-meta">
                <span class="memory-agent">Origin: {{ mem.agentName }}</span>
                @for (tag of mem.tags || []; track tag) {
                  <span class="tag">{{ tag }}</span>
                }
              </div>
            </div>
          }
          @if (filteredMemories.length === 0) {
            <div class="empty">No shared memories found</div>
          }
        </div>
      </div>
      <div class="version-panel">
        @if (selectedMemory) {
          <h2 class="section-title">Versions: {{ selectedMemory.key }}</h2>
          @for (v of versions; track v.version) {
            <div class="version-item">
              <div class="version-header">
                <span class="version-num">v{{ v.version }}</span>
                <span class="version-time">{{ v.timestamp | date:'medium' }}</span>
              </div>
              <pre class="version-content">{{ v.content }}</pre>
            </div>
          }
        } @else {
          <div class="empty">Select a shared memory to view versions</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .explorer-layout { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .memory-list-panel, .version-panel {
      background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 16px;
      max-height: 75vh; overflow-y: auto;
    }
    .filters { margin-bottom: 12px; }
    .filter-input {
      width: 100%; padding: 8px 12px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--bg-secondary); color: var(--text-primary); font-size: 13px; outline: none;
    }
    .memory-item {
      padding: 10px 12px; border-bottom: 1px solid var(--border); cursor: pointer;
      transition: background 0.15s;
    }
    .memory-item:hover { background: rgba(255,255,255,0.03); }
    .memory-item.selected { background: rgba(249,115,22,0.08); border-left: 2px solid var(--accent); }
    .memory-key { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
    .memory-meta { display: flex; gap: 6px; align-items: center; font-size: 11px; }
    .memory-agent { color: var(--text-secondary); }
    .tag { background: rgba(249,115,22,0.2); color: var(--accent); padding: 1px 6px; border-radius: 4px; font-size: 10px; }
    .section-title { font-size: 16px; font-weight: 600; margin: 0 0 16px; }
    .version-item { margin-bottom: 12px; padding: 10px; background: var(--bg-secondary); border-radius: 6px; }
    .version-header { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 12px; }
    .version-num { color: var(--accent); font-weight: 600; }
    .version-time { color: var(--text-secondary); }
    .version-content { font-size: 12px; color: var(--text-primary); white-space: pre-wrap; word-break: break-word; margin: 0; font-family: 'JetBrains Mono', monospace; }
    .empty { color: var(--text-secondary); font-size: 13px; padding: 24px 0; text-align: center; }
  `]
})
export class SharedMemoryComponent implements OnInit {
  memories: any[] = [];
  filteredMemories: any[] = [];
  searchQuery = '';
  selectedMemory: any = null;
  versions: any[] = [];

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getMemories({ shared: true }).subscribe(m => {
      this.memories = m;
      this.filterMemories();
    });
  }

  filterMemories() {
    this.filteredMemories = this.memories.filter(m =>
      !this.searchQuery || m.key?.toLowerCase().includes(this.searchQuery.toLowerCase())
    );
  }

  selectMemory(mem: any) {
    this.selectedMemory = mem;
    this.api.getMemoryVersions(mem.id).subscribe(v => this.versions = v);
  }
}
