import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api';
  constructor(private http: HttpClient) {}

  getMemories(params?: any): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/memories`, { params }); }
  getMemory(id: string): Observable<any> { return this.http.get<any>(`${this.baseUrl}/memories/${id}`); }
  getMemoryVersions(id: string): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/memories/${id}/versions`); }
  createMemory(body: any): Observable<any> { return this.http.post(`${this.baseUrl}/memories`, body); }
  deleteMemory(id: string): Observable<any> { return this.http.delete(`${this.baseUrl}/memories/${id}`); }
  getAgents(): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/agents`); }
  getAgentMetrics(name: string): Observable<any> { return this.http.get<any>(`${this.baseUrl}/agents/${name}/metrics`); }
  getGraph(params?: any): Observable<any> { return this.http.get<any>(`${this.baseUrl}/graph`, { params }); }
  getSubgraph(memoryId: string, depth?: number): Observable<any> { return this.http.get<any>(`${this.baseUrl}/graph/${memoryId}`, { params: { depth: depth || 2 } }); }
  getGoals(params?: any): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/goals`, { params }); }
  getAnomalies(): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/anomalies`); }
  getAuditLog(params?: any): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/audit`, { params }); }
  getTimeseries(params?: any): Observable<any[]> { return this.http.get<any[]>(`${this.baseUrl}/performance/timeseries`, { params }); }
  getPerformanceSummary(): Observable<any> { return this.http.get<any>(`${this.baseUrl}/performance/summary`); }
  getSettings(): Observable<any> { return this.http.get<any>(`${this.baseUrl}/settings`); }
  updateSettings(body: any): Observable<any> { return this.http.put(`${this.baseUrl}/settings`, body); }
}
