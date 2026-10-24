export type Snapshot = {
  revision: number;
  operationId: string;
  stage: string;
  busy: boolean;
  signedIn: boolean;
  email: string;
  planEnabled: boolean;
  models: Array<{slug: string; name: string}>;
  answer: string;
  error: string;
  httpStatus: number;
  requestId: string;
  model?: string;
  responseContentType?: string;
  responseFormat?: string;
};

export const initialSnapshot: Snapshot = {
  revision: -1, operationId: '', stage: 'SignedOut', busy: true, signedIn: false,
  email: '', planEnabled: false, models: [], answer: '', error: '', httpStatus: 0, requestId: '',
};

export function parseSnapshot(raw: string): Snapshot {
  return {...initialSnapshot, ...JSON.parse(raw)};
}

export function safeDiagnostics(snapshot: Snapshot, android: string): string {
  return JSON.stringify({
    app: 'Podcast Generator SIWC Spike', version: '1.0', android,
    stage: snapshot.stage, operationId: snapshot.operationId,
    model: snapshot.model ?? '', error: snapshot.error,
    httpStatus: snapshot.httpStatus, requestId: snapshot.requestId,
    responseContentType: snapshot.responseContentType ?? '',
    responseFormat: snapshot.responseFormat ?? '',
    planEnabled: snapshot.planEnabled,
  }, null, 2);
}
