@Component({
  selector: 'app-worker-list',
  standalone: true,
})
export class WorkerListComponent {
  readonly workerService = inject(WorkerService);
}
