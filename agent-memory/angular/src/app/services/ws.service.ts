import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Observable, Subject, filter } from 'rxjs';

export interface WsMessage {
  type: string;
  topic: string;
  data: any;
  ts: string;
}

@Injectable({ providedIn: 'root' })
export class WebSocketService implements OnDestroy {
  private ws: WebSocket | null = null;
  private events = new Subject<WsMessage>();
  private connectedSubject = new BehaviorSubject<boolean>(false);
  private reconnectDelay = 1000;
  private maxReconnectDelay = 30000;
  private destroyed = false;

  events$ = this.events.asObservable();
  connected$ = this.connectedSubject.asObservable();

  constructor() {
    this.connect();
  }

  private connect() {
    if (this.destroyed) return;

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${location.host}/ws/events`;

    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.connectedSubject.next(true);
      this.reconnectDelay = 1000;
    };

    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'batch' && Array.isArray(msg.events)) {
          msg.events.forEach((e: WsMessage) => this.events.next(e));
        } else if (msg.type === 'event') {
          this.events.next(msg);
        }
      } catch {}
    };

    this.ws.onclose = () => {
      this.connectedSubject.next(false);
      this.scheduleReconnect();
    };

    this.ws.onerror = () => {
      this.ws?.close();
    };
  }

  private scheduleReconnect() {
    if (this.destroyed) return;
    setTimeout(() => this.connect(), this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxReconnectDelay);
  }

  subscribe(topics: string[]) {
    this.send({ type: 'subscribe', topics });
  }

  unsubscribe(topics: string[]) {
    this.send({ type: 'unsubscribe', topics });
  }

  on(topic: string): Observable<WsMessage> {
    return this.events$.pipe(filter(e => e.topic === topic));
  }

  private send(msg: any) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.ws?.close();
  }
}
