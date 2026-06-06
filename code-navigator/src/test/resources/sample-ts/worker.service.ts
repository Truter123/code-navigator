@Injectable({ providedIn: 'root' })
export class WorkerService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/workers`;

  loadAll(): Observable<Worker[]> {
    return this.http.get<Worker[]>(this.apiUrl);
  }
}
