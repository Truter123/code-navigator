export interface Worker {
  id: string;
  name: string;
}

export interface CreateWorkerRequest {
  name: string;
  pin: string;
}
