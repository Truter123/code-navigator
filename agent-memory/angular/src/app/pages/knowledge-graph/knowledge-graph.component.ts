import { Component, AfterViewInit, ElementRef, ViewChild, OnDestroy } from '@angular/core';
import cytoscape from 'cytoscape';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-knowledge-graph',
  standalone: true,
  template: `
    <h1 class="page-title">Knowledge Graph</h1>
    <div class="graph-container" #graphContainer></div>
  `,
  styles: [`
    .page-title { font-size: 24px; font-weight: 700; margin: 0 0 24px; }
    .graph-container {
      width: 100%; height: 70vh;
      background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px;
    }
  `]
})
export class KnowledgeGraphComponent implements AfterViewInit, OnDestroy {
  @ViewChild('graphContainer') containerRef!: ElementRef;
  private cy: cytoscape.Core | null = null;

  constructor(private api: ApiService) {}

  ngAfterViewInit() {
    this.api.getGraph().subscribe(data => {
      const elements: cytoscape.ElementDefinition[] = [];
      if (data.nodes) {
        data.nodes.forEach((n: any) => {
          elements.push({ data: { id: n.id, label: n.key || n.id } });
        });
      }
      if (data.edges) {
        data.edges.forEach((e: any) => {
          elements.push({ data: { source: e.source, target: e.target, label: e.relation || '' } });
        });
      }
      this.cy = cytoscape({
        container: this.containerRef.nativeElement,
        elements,
        style: [
          {
            selector: 'node',
            style: {
              'background-color': '#f97316',
              'label': 'data(label)',
              'color': '#e4e4e7',
              'font-size': '11px',
              'text-valign': 'bottom',
              'text-margin-y': 6,
              'width': 28,
              'height': 28,
            }
          },
          {
            selector: 'edge',
            style: {
              'width': 1.5,
              'line-color': '#2e3344',
              'target-arrow-color': '#2e3344',
              'target-arrow-shape': 'triangle',
              'curve-style': 'bezier',
              'label': 'data(label)',
              'font-size': '9px',
              'color': '#a1a1aa',
              'text-rotation': 'autorotate',
            }
          }
        ],
        layout: { name: 'cose', animate: false } as any,
      });
    });
  }

  ngOnDestroy() {
    this.cy?.destroy();
  }
}
