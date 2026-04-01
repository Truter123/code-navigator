import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

interface NavItem {
  label: string;
  route: string;
  icon: string;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <aside class="sidebar">
      <div class="logo">
        <span class="logo-icon">&#9679;</span>
        <span class="logo-text">Agent Memory</span>
      </div>
      @for (group of navGroups; track group.title) {
        <div class="nav-group">
          <div class="nav-group-title">{{ group.title }}</div>
          @for (item of group.items; track item.route) {
            <a class="nav-item"
               [routerLink]="item.route"
               routerLinkActive="active">
              <span class="nav-icon">{{ item.icon }}</span>
              <span>{{ item.label }}</span>
            </a>
          }
        </div>
      }
    </aside>
  `,
  styles: [`
    .sidebar {
      position: fixed;
      top: 0;
      left: 0;
      width: 240px;
      height: 100vh;
      background-color: var(--bg-secondary);
      border-right: 1px solid var(--border);
      padding: 16px 0;
      overflow-y: auto;
      z-index: 100;
    }
    .logo {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 20px 24px;
      font-size: 16px;
      font-weight: 700;
      color: var(--text-primary);
    }
    .logo-icon {
      color: var(--accent);
      font-size: 20px;
    }
    .nav-group {
      margin-bottom: 8px;
    }
    .nav-group-title {
      padding: 8px 20px 4px;
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-secondary);
    }
    .nav-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 8px 20px;
      font-size: 13px;
      color: var(--text-secondary);
      text-decoration: none;
      transition: all 0.15s;
      cursor: pointer;
    }
    .nav-item:hover {
      color: var(--text-primary);
      background-color: rgba(255,255,255,0.04);
    }
    .nav-item.active {
      color: var(--accent);
      background-color: rgba(249, 115, 22, 0.08);
      border-right: 2px solid var(--accent);
    }
    .nav-icon {
      font-size: 15px;
      width: 20px;
      text-align: center;
    }
  `]
})
export class SidebarComponent {
  navGroups: NavGroup[] = [
    {
      title: 'Monitoring',
      items: [
        { label: 'Overview', route: '/overview', icon: '\u25A3' },
        { label: 'Agents', route: '/agents', icon: '\u2B22' },
        { label: 'Memory Explorer', route: '/memory-explorer', icon: '\u29C9' },
        { label: 'Shared Memory', route: '/shared-memory', icon: '\u2B2C' },
      ]
    },
    {
      title: 'Operations',
      items: [
        { label: 'Performance', route: '/performance', icon: '\u26A1' },
        { label: 'Analytics', route: '/analytics', icon: '\u25B3' },
        { label: 'Audit Trail', route: '/audit-trail', icon: '\u2630' },
      ]
    },
    {
      title: 'Management',
      items: [
        { label: 'Knowledge Graph', route: '/knowledge-graph', icon: '\u2B53' },
        { label: 'Recovery', route: '/recovery', icon: '\u21BB' },
        { label: 'Anomalies', route: '/anomalies', icon: '\u26A0' },
        { label: 'Settings', route: '/settings', icon: '\u2699' },
      ]
    }
  ];
}
