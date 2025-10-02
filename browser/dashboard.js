const params = new URLSearchParams(window.location.search);
const rawStreamIdParam = (params.get('streamId') || '').trim();
const hasExplicitStreamId = params.has('streamId') && rawStreamIdParam.length > 0;
const streamId = rawStreamIdParam || 'mavic-stream';
const gcsEnableParam = (params.get('enableGcs') || '').trim().toLowerCase();
const gcsControlEnabled = gcsEnableParam !== 'false';

const isSecurePage = window.location.protocol === 'https:';

function getDefaultHost() {
  const candidate = window.location.hostname;
  if (candidate && candidate.trim().length > 0) {
    return candidate.trim();
  }
  return '127.0.0.1';
}

function parseUrl(rawUrl, fallbackProtocol) {
  if (!rawUrl) {
    return null;
  }
  try {
    return new URL(rawUrl, window.location.href);
  } catch (error) {
    if (rawUrl.includes('://')) {
      return null;
    }
    try {
      return new URL(`${fallbackProtocol}://${rawUrl}`);
    } catch (innerError) {
      console.error('Failed to parse URL', rawUrl, innerError);
      return null;
    }
  }
}

function normaliseWebSocketUrl(url, defaultProtocol, defaultTerminalSegment) {
  if (!url) {
    return null;
  }

  if (url.protocol === 'http:' || url.protocol === 'https:') {
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  } else if (url.protocol !== 'ws:' && url.protocol !== 'wss:') {
    url.protocol = `${defaultProtocol}:`;
  }

  const segments = url.pathname.split('/').filter(Boolean);
  const socketIoIndex = segments.findIndex((segment) => segment === 'socket.io');
  if (socketIoIndex >= 0) {
    segments.splice(socketIoIndex);
  }
  if (segments.length === 0 || segments[segments.length - 1] !== defaultTerminalSegment) {
    segments.push(defaultTerminalSegment);
  }
  url.pathname = `/${segments.join('/')}`;
  url.hash = '';
  url.search = '';
  return url;
}

function resolveSignalingEndpoint(searchParams, streamIdValue) {
  const fallbackProtocol = isSecurePage ? 'wss' : 'ws';
  const explicitUrlParam = (searchParams.get('signalingUrl') || '').trim();
  const portOverride = (searchParams.get('signalingPort') || '').trim();

  let signalingUrlObject = null;
  if (explicitUrlParam) {
    signalingUrlObject = parseUrl(explicitUrlParam, `${fallbackProtocol}://`);
  }

  if (!signalingUrlObject) {
    const hostParam = (searchParams.get('signalingHost') || '').trim();
    const hostValue = hostParam || getDefaultHost();
    const protocolParam = (searchParams.get('signalingProtocol') || '').trim();
    const protocolValue = protocolParam || fallbackProtocol;
    const portValue = portOverride || '8080';
    const hostPortPart = portValue ? `${hostValue}:${portValue}` : hostValue;
    signalingUrlObject = parseUrl(`${protocolValue}://${hostPortPart}`, `${fallbackProtocol}://${hostPortPart}`);
    if (!signalingUrlObject) {
      signalingUrlObject = new URL(`${fallbackProtocol}://${hostPortPart}`);
    }
  }

  if (portOverride) {
    signalingUrlObject.port = portOverride;
  }

  signalingUrlObject = normaliseWebSocketUrl(signalingUrlObject, fallbackProtocol, 'ws');
  signalingUrlObject.searchParams.set('role', 'subscriber');
  signalingUrlObject.searchParams.set('streamId', streamIdValue);

  return signalingUrlObject;
}

function resolveDetectionUrl(searchParams, signalingUrlObject) {
  const fallbackProtocol = signalingUrlObject?.protocol === 'wss:' ? 'wss' : isSecurePage ? 'wss' : 'ws';
  const explicitDetectionUrl = (searchParams.get('detectionUrl') || '').trim();
  if (explicitDetectionUrl) {
    const url = parseUrl(explicitDetectionUrl, `${fallbackProtocol}://`);
    if (!url) {
      return explicitDetectionUrl;
    }
    if (url.protocol === 'http:' || url.protocol === 'https:') {
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    }
    return url.toString();
  }

  const detectionHostParam = (searchParams.get('detectionHost') || '').trim();
  const detectionPortParam = (searchParams.get('detectionPort') || '').trim();
  const detectionProtocolParam = (searchParams.get('detectionProtocol') || '').trim();

  const host = detectionHostParam || signalingUrlObject?.hostname || getDefaultHost();
  const port = detectionPortParam || '8765';
  const protocol = detectionProtocolParam || fallbackProtocol;

  const hostPortPart = port ? `${host}:${port}` : host;
  const url = parseUrl(`${protocol}://${hostPortPart}`, `${protocol}://${host}`);
  if (!url) {
    return `${protocol}://${hostPortPart}/detections`;
  }
  if (url.protocol === 'http:' || url.protocol === 'https:') {
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  }
  if (!url.pathname || url.pathname === '/') {
    url.pathname = '/detections';
  }
  return url.toString();
}

function resolveRecordingsEndpoints(searchParams, signalingUrlObject) {
  const endpoints = [];
  const seen = new Set();

  function appendEndpoint(url) {
    if (!url) {
      return;
    }
    const serialized = url.toString();
    if (seen.has(serialized)) {
      return;
    }
    seen.add(serialized);
    endpoints.push(url);
  }

  const explicitUrl = (searchParams.get('recordingsUrl') || '').trim();
  if (explicitUrl) {
    const parsed = parseUrl(explicitUrl, isSecurePage ? 'https://' : 'http://');
    if (parsed) {
      if (!parsed.pathname || parsed.pathname === '/') {
        parsed.pathname = '/recordings';
      }
      parsed.search = '';
      parsed.hash = '';
      appendEndpoint(parsed);
    } else {
      console.error('Failed to parse explicit recordingsUrl parameter', explicitUrl);
    }
  }

  if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
    try {
      const sameOrigin = new URL(window.location.href);
      sameOrigin.pathname = '/recordings';
      sameOrigin.search = '';
      sameOrigin.hash = '';
      appendEndpoint(sameOrigin);
    } catch (error) {
      console.warn('Failed to derive same-origin recordings endpoint', error);
    }
  }

  const hostParam = (searchParams.get('recordingsHost') || '').trim();
  const portParam = (searchParams.get('recordingsPort') || '').trim();
  const protocolParam = (searchParams.get('recordingsProtocol') || '').trim();

  let host = hostParam;
  let port = portParam;
  let protocol = protocolParam;

  if (signalingUrlObject) {
    if (!host) {
      host = signalingUrlObject.hostname;
    }
    if (!port && signalingUrlObject.port) {
      port = signalingUrlObject.port;
    }
    if (!protocol) {
      if (signalingUrlObject.protocol === 'wss:') {
        protocol = 'https';
      } else if (signalingUrlObject.protocol === 'ws:') {
        protocol = 'http';
      } else if (signalingUrlObject.protocol.endsWith(':')) {
        protocol = signalingUrlObject.protocol.slice(0, -1);
      } else {
        protocol = signalingUrlObject.protocol || '';
      }
    }
  }

  if (!protocol) {
    protocol = isSecurePage ? 'https' : 'http';
  }
  if (!host) {
    host = getDefaultHost();
  }

  const hostPort = port ? `${host}:${port}` : host;
  const derived = parseUrl(`${protocol}://${hostPort}`, `${protocol}://${hostPort}`);
  if (derived) {
    if (!derived.pathname || derived.pathname === '/') {
      derived.pathname = '/recordings';
    }
    derived.search = '';
    derived.hash = '';
    appendEndpoint(derived);
  } else {
    console.error('Failed to construct recordings endpoint URL');
  }

  return endpoints;
}

function resolveAnalysisEndpoint(searchParams, recordingsEndpoints) {
  const explicit = (searchParams.get('analysisUrl') || '').trim();
  if (explicit) {
    const parsed = parseUrl(explicit, isSecurePage ? 'https://' : 'http://');
    if (parsed) {
      if (!parsed.pathname || parsed.pathname === '/') {
        parsed.pathname = '/analysis';
      }
      parsed.search = '';
      parsed.hash = '';
      return parsed;
    }
  }

  if (Array.isArray(recordingsEndpoints) && recordingsEndpoints.length > 0) {
    try {
      const base = new URL(recordingsEndpoints[0].toString());
      if (!base.pathname || base.pathname === '/') {
        base.pathname = '/analysis';
      } else if (base.pathname.endsWith('/recordings')) {
        base.pathname = `${base.pathname.slice(0, -'/recordings'.length)}/analysis`;
      } else {
        base.pathname = '/analysis';
      }
      base.search = '';
      base.hash = '';
      return base;
    } catch (error) {
      console.warn('Failed to derive analysis endpoint from recordings endpoint', error);
    }
  }

  if (window.location.protocol === 'http:' || window.location.protocol === 'https:') {
    try {
      const sameOrigin = new URL(window.location.href);
      sameOrigin.pathname = '/analysis';
      sameOrigin.search = '';
      sameOrigin.hash = '';
      return sameOrigin;
    } catch (error) {
      console.warn('Failed to derive same-origin analysis endpoint', error);
    }
  }

  return null;
}

const signalingUrlObject = resolveSignalingEndpoint(params, streamId);
const signalingUrl = signalingUrlObject.toString();
const detectionUrl = resolveDetectionUrl(params, signalingUrlObject);
const recordingsEndpoints = resolveRecordingsEndpoints(params, signalingUrlObject);
const analysisEndpoint = resolveAnalysisEndpoint(params, recordingsEndpoints);
const analysisPromptParam = (params.get('analysisPrompt') || '').trim();
const analysisPromptValue = analysisPromptParam;
const analysisTemperatureParam = (params.get('analysisTemperature') || '').trim();
const analysisMaxTokensParam = (params.get('analysisMaxTokens') || '').trim();
const embeddingTranscriptionParam = (params.get('embeddingTranscription') || '')
  .trim()
  .toLowerCase();
const embeddingTranscriptionEnabled = ['1', 'true', 'yes', 'on'].includes(
  embeddingTranscriptionParam
);
const embeddingOptionParams = params.getAll('embeddingOption');
const embeddingOptionsParamValue = (params.get('embeddingOptions') || '')
  .split(',')
  .map((option) => option.trim())
  .filter((option) => option.length > 0);
const defaultEmbeddingOptions = [];
for (const option of [...embeddingOptionParams, ...embeddingOptionsParamValue]) {
  const value = typeof option === 'string' ? option.trim() : '';
  if (value && !defaultEmbeddingOptions.includes(value)) {
    defaultEmbeddingOptions.push(value);
  }
}

const embeddingRequestState = {
  includeTranscription: embeddingTranscriptionEnabled,
  options: defaultEmbeddingOptions,
};

const rawVideo = document.getElementById('rawVideo');
const overlayVideo = document.getElementById('overlayVideo');
const overlayCanvas = document.getElementById('overlayCanvas');
const overlayCtx = overlayCanvas.getContext('2d');
const rawVideoStatus = document.getElementById('rawVideoStatus');
const overlayVideoStatus = document.getElementById('overlayVideoStatus');
const rawVideoMessage = document.getElementById('rawVideoMessage');
const overlayVideoMessage = document.getElementById('overlayVideoMessage');
const rawVideoDelay = document.getElementById('rawVideoDelay');
const overlayVideoDelay = document.getElementById('overlayVideoDelay');
const connectionStatus = document.getElementById('connectionStatus');
const detectionStatus = document.getElementById('detectionStatus');
const detectionTimestamp = document.getElementById('detectionTimestamp');
const streamIdLabel = document.getElementById('streamId');
const flightControlsPanel = document.getElementById('flightControlsPanel');
const toggleFlightControlsButton = document.getElementById('toggleFlightControls');
const closeFlightControlsButton = document.getElementById('closeFlightControls');
const routePanel = document.getElementById('routePlanner');
const toggleRoutePanel = document.getElementById('toggleRoutePanel');
const closeRoutePanel = document.getElementById('closeRoutePanel');
const routeMapElement = document.getElementById('routeMap');
const waypointCountLabel = document.getElementById('waypointCount');
const routeDistanceLabel = document.getElementById('routeDistance');
const startRouteButton = document.getElementById('startRoute');
const undoWaypointButton = document.getElementById('undoWaypoint');
const clearRouteButton = document.getElementById('clearRoute');
const defaultAltitudeInput = document.getElementById('defaultAltitude');
const gcsStatusElements = Array.from(document.querySelectorAll('[data-role="gcs-status"]'));
const takeoffButton = document.getElementById('commandTakeoff');
const landButton = document.getElementById('commandLand');
const returnHomeButton = document.getElementById('commandReturnHome');
const cancelReturnButton = document.getElementById('commandCancelReturn');
const virtualStickForm = document.getElementById('virtualStickForm');
const gimbalForm = document.getElementById('gimbalForm');
const droneLocationContainer = document.getElementById('droneLocation');
const droneLocationCoords = document.getElementById('droneLocationCoords');
const droneLocationMeta = document.getElementById('droneLocationMeta');
const mainElement = document.querySelector('main');
const tabButtons = Array.from(document.querySelectorAll('[data-tab-target]'));
const tabPanels = new Map(
  Array.from(document.querySelectorAll('.tab-panel')).map((panel) => [panel.id, panel])
);
const recordingsListElement = document.getElementById('recordingsList');
const recordingsSummaryElement = document.getElementById('recordingsSummary');
const refreshRecordingsButton = document.getElementById('refreshRecordings');
const analysisRecordingTitle = document.getElementById('analysisRecordingTitle');
const analysisRecordingMeta = document.getElementById('analysisRecordingMeta');
const analysisStatusBadge = document.getElementById('analysisStatusBadge');
const analysisDetailsContainer = document.getElementById('analysisDetails');

if (streamIdLabel) {
  streamIdLabel.textContent = streamId;
}

const recordingsState = {
  items: [],
  isLoading: false,
  lastUpdated: null,
};

let selectedRecordingId = null;

const defaultAnalysisMessage =
  'Select a recording to preview its metadata, request Twelve Labs analysis, or fetch embeddings for video playback and vector export.';

let analysisIntegrationAvailable = Boolean(analysisEndpoint);

const analysisViewState = {
  recording: null,
  status: 'idle',
  message: defaultAnalysisMessage,
  result: null,
  cached: false,
  errorCode: null,
};

const lastRenderedAnalysisContent = {
  recordingId: null,
  message: defaultAnalysisMessage,
  resultRef: null,
  cached: false,
  errorCode: null,
};

const ANALYSIS_STATUS_REFRESH_INTERVAL_MS = 5000;
const ACTIVE_ANALYSIS_STATUSES = new Set(['loading', 'pending', 'embedding']);
const analysisCache = new Map();
const analysisStatusRequests = new Map();
const embeddingSuccessCache = new Map();
const EMBEDDING_INITIAL_POLL_DELAY_MS = 30000;
const EMBEDDING_POLL_INTERVAL_MS = 5000;
const embeddingMonitorState = new Map();
const WORKFLOW_SYNC_CHANNEL_NAME = 'dji-dashboard-workflow';
const WORKFLOW_SYNC_TTL_MS = 60 * 60 * 1000;
const workflowSyncState = new Map();
const workflowSyncClientId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
let workflowSyncChannel = null;
const WORKFLOW_PENDING_STATES = new Set([
  'pending',
  'starting',
  'processing',
  'running',
  'queued',
  'queue',
  'start',
]);
const WORKFLOW_SUCCESS_STATES = new Set(['ready', 'completed', 'done', 'ok', 'success', 'cached', 'end']);
const WORKFLOW_ERROR_STATES = new Set(['error', 'failed', 'failure']);

function getWorkflowStateValue(statusInfo) {
  if (!statusInfo || typeof statusInfo !== 'object') {
    return '';
  }
  if (typeof statusInfo.state === 'string' && statusInfo.state.trim().length > 0) {
    return statusInfo.state.trim().toLowerCase();
  }
  if (typeof statusInfo.status === 'string' && statusInfo.status.trim().length > 0) {
    return statusInfo.status.trim().toLowerCase();
  }
  return '';
}

function getWorkflowStageValue(statusInfo) {
  if (!statusInfo || typeof statusInfo !== 'object') {
    return '';
  }
  if (typeof statusInfo.stage === 'string' && statusInfo.stage.trim().length > 0) {
    const stageCandidate = statusInfo.stage.trim().toLowerCase();
    if (stageCandidate === 'start' || stageCandidate === 'end' || stageCandidate === 'error') {
      return stageCandidate;
    }
  }
  const state = getWorkflowStateValue(statusInfo);
  if (!state) {
    return '';
  }
  if (WORKFLOW_PENDING_STATES.has(state)) {
    return 'start';
  }
  if (WORKFLOW_SUCCESS_STATES.has(state)) {
    return 'end';
  }
  if (WORKFLOW_ERROR_STATES.has(state)) {
    return 'error';
  }
  return '';
}

function isWorkflowSyncStorageSupported() {
  return false;
}

function getWorkflowStatusTimestamp(status) {
  if (!status || typeof status !== 'object') {
    return 0;
  }
  if (typeof status.__timestamp === 'number' && Number.isFinite(status.__timestamp)) {
    return status.__timestamp;
  }
  const updatedAt = typeof status.updatedAt === 'string' ? status.updatedAt : '';
  if (updatedAt) {
    const parsed = Date.parse(updatedAt);
    if (!Number.isNaN(parsed)) {
      return parsed;
    }
  }
  return 0;
}

function normaliseWorkflowStatus(status) {
  if (!status || typeof status !== 'object') {
    return null;
  }
  const state =
    typeof status.state === 'string' && status.state.trim().length > 0
      ? status.state.trim().toLowerCase()
      : '';
  if (!state) {
    return null;
  }
  const message =
    typeof status.message === 'string' && status.message.trim().length > 0
      ? status.message.trim()
      : '';
  const options = Array.isArray(status.options)
    ? status.options
        .map((option) => (typeof option === 'string' ? option.trim() : ''))
        .filter((option, index, array) => option && array.indexOf(option) === index)
    : [];
  const missingOptions = Array.isArray(status.missingOptions)
    ? status.missingOptions
        .map((option) => (typeof option === 'string' ? option.trim() : ''))
        .filter((option, index, array) => option && array.indexOf(option) === index)
    : [];
  const includeTranscription = Object.prototype.hasOwnProperty.call(status, 'includeTranscription')
    ? Boolean(status.includeTranscription)
    : undefined;
  const updatedAt =
    typeof status.updatedAt === 'string' && status.updatedAt.trim().length > 0
      ? status.updatedAt
      : new Date().toISOString();
  const timestamp =
    typeof status.__timestamp === 'number' && Number.isFinite(status.__timestamp)
      ? status.__timestamp
      : Date.now();
  const normalised = {
    state,
    updatedAt,
    __timestamp: timestamp,
  };
  if (message) {
    normalised.message = message;
  }
  if (options.length > 0) {
    normalised.options = options;
  }
  if (missingOptions.length > 0) {
    normalised.missingOptions = missingOptions;
  }
  if (includeTranscription !== undefined) {
    normalised.includeTranscription = includeTranscription;
  }
  return normalised;
}

function cloneWorkflowStatus(status) {
  if (!status || typeof status !== 'object') {
    return null;
  }
  const clone = {
    state: status.state,
    updatedAt: status.updatedAt,
  };
  if (typeof status.message === 'string' && status.message.length > 0) {
    clone.message = status.message;
  }
  if (Array.isArray(status.options) && status.options.length > 0) {
    clone.options = [...status.options];
  }
  if (Array.isArray(status.missingOptions) && status.missingOptions.length > 0) {
    clone.missingOptions = [...status.missingOptions];
  }
  if (Object.prototype.hasOwnProperty.call(status, 'includeTranscription')) {
    clone.includeTranscription = Boolean(status.includeTranscription);
  }
  if (typeof status.__timestamp === 'number' && Number.isFinite(status.__timestamp)) {
    clone.__timestamp = status.__timestamp;
  }
  return clone;
}

function pruneWorkflowSyncEntries(map) {
  const now = Date.now();
  const expiryThreshold = now - WORKFLOW_SYNC_TTL_MS;
  let changed = false;
  for (const [key, value] of map.entries()) {
    let entryChanged = false;
    if (value.embedding && getWorkflowStatusTimestamp(value.embedding) < expiryThreshold) {
      delete value.embedding;
      entryChanged = true;
    }
    if (value.analysis && getWorkflowStatusTimestamp(value.analysis) < expiryThreshold) {
      delete value.analysis;
      entryChanged = true;
    }
    if (!value.embedding && !value.analysis) {
      map.delete(key);
      changed = true;
      continue;
    }
    if (entryChanged) {
      map.set(key, value);
      changed = true;
    }
  }
  return changed;
}

function serialiseWorkflowSyncState() {
  const entries = [];
  for (const [key, value] of workflowSyncState.entries()) {
    const entry = { key };
    if (value.embedding) {
      entry.embedding = cloneWorkflowStatus(value.embedding);
    }
    if (value.analysis) {
      entry.analysis = cloneWorkflowStatus(value.analysis);
    }
    entries.push(entry);
  }
  return {
    clientId: workflowSyncClientId,
    version: Date.now(),
    entries,
  };
}

function applyWorkflowSyncState(nextMap) {
  workflowSyncState.clear();
  for (const [key, value] of nextMap.entries()) {
    workflowSyncState.set(key, value);
  }
  pruneWorkflowSyncEntries(workflowSyncState);
  renderRecordingsList();
}

function applyWorkflowSyncPayload(payload) {
  if (!payload || typeof payload !== 'object') {
    return;
  }
  if (payload.clientId && payload.clientId === workflowSyncClientId) {
    return;
  }
  const entries = Array.isArray(payload.entries) ? payload.entries : [];
  const nextMap = new Map();
  for (const candidate of entries) {
    if (!candidate || typeof candidate !== 'object') {
      continue;
    }
    const key = typeof candidate.key === 'string' && candidate.key.trim().length > 0 ? candidate.key : '';
    if (!key) {
      continue;
    }
    const entry = {};
    const embeddingStatus = normaliseWorkflowStatus(candidate.embedding);
    if (embeddingStatus) {
      entry.embedding = embeddingStatus;
    }
    const analysisStatus = normaliseWorkflowStatus(candidate.analysis);
    if (analysisStatus) {
      entry.analysis = analysisStatus;
    }
    if (entry.embedding || entry.analysis) {
      nextMap.set(key, entry);
    }
  }
  pruneWorkflowSyncEntries(nextMap);
  applyWorkflowSyncState(nextMap);
}

function persistWorkflowSyncState() {
  pruneWorkflowSyncEntries(workflowSyncState);
  const payload = serialiseWorkflowSyncState();
  if (workflowSyncChannel) {
    try {
      workflowSyncChannel.postMessage(payload);
    } catch (error) {
      console.warn('Failed to broadcast workflow sync update', error);
    }
  }
}

function updateWorkflowSyncStateByKey(key, updates) {
  if (!key || !updates || typeof updates !== 'object') {
    return;
  }
  const current = workflowSyncState.get(key) || {};
  const next = { ...current };
  let changed = false;

  if (Object.prototype.hasOwnProperty.call(updates, 'embedding')) {
    const embeddingStatus = normaliseWorkflowStatus(updates.embedding);
    if (embeddingStatus) {
      next.embedding = embeddingStatus;
      changed = true;
    } else if (next.embedding) {
      delete next.embedding;
      changed = true;
    }
  }

  if (Object.prototype.hasOwnProperty.call(updates, 'analysis')) {
    const analysisStatus = normaliseWorkflowStatus(updates.analysis);
    if (analysisStatus) {
      next.analysis = analysisStatus;
      changed = true;
    } else if (next.analysis) {
      delete next.analysis;
      changed = true;
    }
  }

  if (!next.embedding && !next.analysis) {
    if (workflowSyncState.has(key)) {
      workflowSyncState.delete(key);
      changed = true;
    }
  } else {
    workflowSyncState.set(key, next);
  }

  if (!changed) {
    return;
  }

  persistWorkflowSyncState();
  renderRecordingsList();

  const activeRecording = analysisViewState.recording;
  if (activeRecording) {
    const activeKey = getAnalysisCacheKey(activeRecording);
    if (activeKey && activeKey === key) {
      updateAnalysisPanelForRecording(activeRecording, { preserveExistingResult: true });
    }
  }
}

function updateWorkflowSyncStateForRecording(recording, updates) {
  const key = getAnalysisCacheKey(recording);
  if (!key) {
    return;
  }
  updateWorkflowSyncStateByKey(key, updates);
}

function getSharedWorkflowStateEntry(recording) {
  const key = getAnalysisCacheKey(recording);
  if (!key) {
    return null;
  }
  return workflowSyncState.get(key) || null;
}

function getSharedEmbeddingStatus(recording) {
  const entry = getSharedWorkflowStateEntry(recording);
  return entry && entry.embedding ? entry.embedding : null;
}

function getSharedAnalysisStatus(recording) {
  const entry = getSharedWorkflowStateEntry(recording);
  return entry && entry.analysis ? entry.analysis : null;
}

function initialiseWorkflowSync() {
  try {
    if (typeof BroadcastChannel === 'function') {
      workflowSyncChannel = new BroadcastChannel(WORKFLOW_SYNC_CHANNEL_NAME);
      workflowSyncChannel.addEventListener('message', (event) => {
        applyWorkflowSyncPayload(event.data);
      });
    }
  } catch (error) {
    workflowSyncChannel = null;
    console.warn('BroadcastChannel unavailable for workflow sync', error);
  }
}

function isWorkflowPendingStatus(statusInfo) {
  if (!statusInfo || typeof statusInfo !== 'object') {
    return false;
  }
  const state = getWorkflowStateValue(statusInfo);
  if (state && WORKFLOW_PENDING_STATES.has(state)) {
    return true;
  }
  return getWorkflowStageValue(statusInfo) === 'start';
}

function isWorkflowReadyStatus(statusInfo) {
  if (!statusInfo || typeof statusInfo !== 'object') {
    return false;
  }
  const state = getWorkflowStateValue(statusInfo);
  if (state && WORKFLOW_SUCCESS_STATES.has(state)) {
    return true;
  }
  return getWorkflowStageValue(statusInfo) === 'end';
}

function isWorkflowErrorStatus(statusInfo) {
  if (!statusInfo || typeof statusInfo !== 'object') {
    return false;
  }
  const state = getWorkflowStateValue(statusInfo);
  if (state && WORKFLOW_ERROR_STATES.has(state)) {
    return true;
  }
  return getWorkflowStageValue(statusInfo) === 'error';
}

function isEmbeddingPendingStatus(statusInfo) {
  return isWorkflowPendingStatus(statusInfo);
}

function isEmbeddingReadyStatus(statusInfo) {
  return isWorkflowReadyStatus(statusInfo);
}

function isEmbeddingErrorStatus(statusInfo) {
  return isWorkflowErrorStatus(statusInfo);
}

const ANALYSIS_REQUEST_TYPE_LABELS = {
  status: 'analysis status',
  start: 'analysis',
  embed: 'embedding',
};

function getAnalysisRequestLabel(requestType) {
  const key = typeof requestType === 'string' ? requestType : '';
  return ANALYSIS_REQUEST_TYPE_LABELS[key] || 'analysis';
}

function buildNetworkFailureMessage(requestType, originalMessage) {
  const label = getAnalysisRequestLabel(requestType);
  const baseMessage = `Unable to reach the Twelve Labs ${label} endpoint. Check the server connection and try again.`;
  const original = typeof originalMessage === 'string' ? originalMessage.trim() : '';
  if (original && original.length > 0 && !/^failed to fetch$/i.test(original)) {
    return `${baseMessage} (${original}).`;
  }
  return baseMessage;
}

function extractEmbeddingStatus(record) {
  if (!record || typeof record !== 'object') {
    return null;
  }
  const status = record.embeddingStatus;
  if (!status || typeof status !== 'object') {
    return null;
  }
  const stateRaw =
    typeof status.state === 'string' && status.state.trim().length > 0
      ? status.state.trim().toLowerCase()
      : '';
  if (!stateRaw) {
    return null;
  }
  const message =
    typeof status.message === 'string' && status.message.trim().length > 0
      ? status.message.trim()
      : '';
  const options = Array.isArray(status.options)
    ? status.options
        .map((option) => (typeof option === 'string' ? option.trim() : ''))
        .filter((option, index, array) => option && array.indexOf(option) === index)
    : [];
  const missingOptions = Array.isArray(status.missingOptions)
    ? status.missingOptions
        .map((option) => (typeof option === 'string' ? option.trim() : ''))
        .filter((option, index, array) => option && array.indexOf(option) === index)
    : [];
  const updatedAt = status.updatedAt || status.updated_at || null;
  const stage = getWorkflowStageValue({
    state: stateRaw,
    stage: status.stage,
  });
  return {
    state: stateRaw,
    message,
    options,
    missingOptions,
    includeTranscription: Boolean(status.includeTranscription),
    updatedAt,
    stage,
  };
}

function getEmbeddingStatusPriority(statusInfo) {
  if (isEmbeddingPendingStatus(statusInfo)) {
    return 3;
  }
  if (isEmbeddingErrorStatus(statusInfo)) {
    return 2;
  }
  if (isEmbeddingReadyStatus(statusInfo)) {
    return 1;
  }
  return 0;
}

function choosePreferredEmbeddingStatus(current, candidate) {
  if (!current) {
    return candidate;
  }
  if (!candidate) {
    return current;
  }
  const currentPriority = getEmbeddingStatusPriority(current);
  const candidatePriority = getEmbeddingStatusPriority(candidate);
  if (candidatePriority > currentPriority) {
    return candidate;
  }
  if (candidatePriority < currentPriority) {
    return current;
  }
  const currentTimestamp = getWorkflowStatusTimestamp(current);
  const candidateTimestamp = getWorkflowStatusTimestamp(candidate);
  if (candidateTimestamp > currentTimestamp) {
    return candidate;
  }
  return current;
}

function getEffectiveEmbeddingStatus(recording, ...sources) {
  const candidates = [];
  for (const source of sources) {
    const status = extractEmbeddingStatus(source);
    if (status) {
      candidates.push(status);
    }
  }
  const sharedStatus = getSharedEmbeddingStatus(recording);
  if (sharedStatus) {
    candidates.push(sharedStatus);
  }
  if (candidates.length === 0) {
    return null;
  }
  let preferred = null;
  for (const status of candidates) {
    preferred = choosePreferredEmbeddingStatus(preferred, status);
  }
  return preferred;
}

function buildPendingEmbeddingMessage(recording, record, statusInfo) {
  if (statusInfo && statusInfo.message) {
    return statusInfo.message;
  }
  let displayName = '';
  if (recording && typeof recording.displayName === 'string') {
    displayName = recording.displayName.trim();
  }
  if (!displayName && record && typeof record.fileName === 'string') {
    displayName = record.fileName.trim();
  }
  if (!displayName && record && typeof record.name === 'string') {
    displayName = record.name.trim();
  }
  if (!displayName) {
    displayName = 'this recording';
  } else {
    displayName = `“${displayName}”`;
  }
  const optionText =
    statusInfo && Array.isArray(statusInfo.options) && statusInfo.options.length > 0
      ? ` (${statusInfo.options.join(', ')})`
      : '';
  return `Twelve Labs embeddings${optionText} for ${displayName} are still processing…`;
}

function resolveAnalysisViewState(
  recording,
  record,
  defaultStatus,
  baseMessage,
  cached,
  context = {}
) {
  let stageValue =
    context && typeof context.embeddingStage === 'string'
      ? context.embeddingStage.trim().toLowerCase()
      : '';
  let statusInfo = context && context.embeddingStatus ? context.embeddingStatus : null;
  if (!statusInfo) {
    statusInfo = extractEmbeddingStatus(record);
  }

  if (!stageValue && statusInfo) {
    const derivedStage = getWorkflowStageValue(statusInfo);
    if (derivedStage) {
      stageValue = derivedStage;
    }
  }

  if (stageValue === 'error' || isEmbeddingErrorStatus(statusInfo)) {
    const errorMessage =
      (statusInfo && statusInfo.message) ||
      baseMessage ||
      'Failed to retrieve Twelve Labs embeddings for this recording.';
    return {
      status: 'error',
      message: errorMessage,
      cached,
    };
  }

  if (stageValue === 'start') {
    return {
      status: 'embedding',
      message: buildPendingEmbeddingMessage(recording, record, statusInfo),
      cached,
    };
  }

  if (stageValue !== 'end' && isEmbeddingPendingStatus(statusInfo)) {
    return {
      status: 'embedding',
      message: buildPendingEmbeddingMessage(recording, record, statusInfo),
      cached,
    };
  }

  const hasAnalysis = recordHasAnalysisContent(record);
  if (!hasAnalysis) {
    return {
      status: 'ready',
      message: baseMessage || defaultAnalysisMessage,
      cached,
    };
  }

  let normalisedStatus = defaultStatus || (cached ? 'cached' : 'ready');
  if (normalisedStatus === 'pending') {
    normalisedStatus = 'embedding';
  } else if (normalisedStatus === 'ok') {
    normalisedStatus = 'cached';
  }

  if (stageValue === 'end' && normalisedStatus === 'embedding') {
    normalisedStatus = cached ? 'cached' : 'ready';
  }

  return {
    status: normalisedStatus,
    message: baseMessage || defaultAnalysisMessage,
    cached,
  };
}

function updateAnalysisViewWithRecord(
  recording,
  record,
  cached,
  defaultStatus,
  baseMessage,
  context = {}
) {
  const resolved = resolveAnalysisViewState(
    recording,
    record,
    defaultStatus,
    baseMessage,
    cached,
    context
  );
  setAnalysisView(recording, resolved.status, resolved.message, record, cached);
}

function stopEmbeddingMonitor(key) {
  const entry = embeddingMonitorState.get(key);
  if (!entry) {
    return;
  }
  if (typeof entry.timerId === 'number') {
    window.clearTimeout(entry.timerId);
    entry.timerId = null;
  }
  if (typeof entry.intervalId === 'number') {
    window.clearInterval(entry.intervalId);
    entry.intervalId = null;
  }
  embeddingMonitorState.delete(key);
}

async function pollEmbeddingStatus(entry) {
  if (!entry || entry.active) {
    return;
  }
  entry.active = true;
  try {
    const result = await fetchAnalysis(entry.recording, { start: false });
    const current = embeddingMonitorState.get(entry.key);
    if (!current || current !== entry) {
      return;
    }
    if (!result.ok) {
      const statusKey = (result.status || '').toLowerCase();
      if (['not_found', 'missing', 'empty'].includes(statusKey)) {
        stopEmbeddingMonitor(entry.key);
      }
      return;
    }
    if (result.record) {
      storeAnalysisRecord(entry.recording, result.record, result.cached);
      if (analysisViewState.recording?.id === entry.recording.id) {
        const defaultStatus = result.cached ? 'cached' : result.status || 'ok';
        const combinedMessage = combineAnalysisAndEmbeddingMessages(
          result.record,
          result.message || '',
          result.cached
        );
        updateAnalysisViewWithRecord(
          entry.recording,
          result.record,
          result.cached,
          defaultStatus,
          combinedMessage,
          {
            embeddingStage: result.embeddingStage,
            embeddingStatus: result.embeddingStatus,
          }
        );
      }
      renderRecordingsList();
    }
    const statusInfo = result.embeddingStatus || extractEmbeddingStatus(result.record);
    if (!statusInfo || statusInfo.state !== 'pending') {
      stopEmbeddingMonitor(entry.key);
    }
  } finally {
    entry.active = false;
  }
}

function startEmbeddingMonitor(key, recording) {
  if (!key) {
    return;
  }
  const existing = embeddingMonitorState.get(key);
  if (existing) {
    existing.recording = recording;
    return;
  }
  const entry = {
    key,
    timerId: null,
    intervalId: null,
    active: false,
    recording,
  };
  entry.timerId = window.setTimeout(() => {
    entry.timerId = null;
    void pollEmbeddingStatus(entry);
    entry.intervalId = window.setInterval(() => {
      void pollEmbeddingStatus(entry);
    }, EMBEDDING_POLL_INTERVAL_MS);
  }, EMBEDDING_INITIAL_POLL_DELAY_MS);
  embeddingMonitorState.set(key, entry);
}

function updateEmbeddingMonitor(key, recording, record) {
  if (!key) {
    return;
  }
  const statusInfo = getEffectiveEmbeddingStatus(recording, record, recording);
  if (isEmbeddingPendingStatus(statusInfo)) {
    startEmbeddingMonitor(key, recording);
  } else {
    stopEmbeddingMonitor(key);
  }
}

function getEmbeddingRequestOptions() {
  const includeTranscription = Boolean(embeddingRequestState.includeTranscription);
  const sourceOptions = Array.isArray(embeddingRequestState.options)
    ? embeddingRequestState.options
    : [];
  const options = [];
  for (const option of sourceOptions) {
    const value = typeof option === 'string' ? option.trim() : '';
    if (value && !options.includes(value)) {
      options.push(value);
    }
  }
  return { includeTranscription, embeddingOptions: options };
}

function extractEmbeddingIdentifier(candidate) {
  if (!candidate || typeof candidate !== 'object') {
    return '';
  }
  const keys = ['id', 'videoId', 'video_id', 'vectorId', 'vector_id'];
  for (const key of keys) {
    const value = candidate[key];
    if (typeof value === 'string' && value.trim()) {
      return value.trim();
    }
  }
  return '';
}

function resolveEmbeddingIdentifier(record) {
  if (!record || typeof record !== 'object') {
    return '';
  }

  const embeddings = record.embeddings && typeof record.embeddings === 'object'
    ? record.embeddings
    : null;
  if (embeddings) {
    const direct = extractEmbeddingIdentifier(embeddings);
    if (direct) {
      return direct;
    }

    const response = embeddings.response && typeof embeddings.response === 'object'
      ? embeddings.response
      : null;
    if (response) {
      const responseId = extractEmbeddingIdentifier(response);
      if (responseId) {
        return responseId;
      }

      const videoEmbedding = response.video_embedding && typeof response.video_embedding === 'object'
        ? response.video_embedding
        : null;
      if (videoEmbedding) {
        const videoEmbeddingId = extractEmbeddingIdentifier(videoEmbedding);
        if (videoEmbeddingId) {
          return videoEmbeddingId;
        }
      }
    }
  }

  const metadataEmbedding =
    record.video &&
    typeof record.video === 'object' &&
    record.video.metadata &&
    typeof record.video.metadata === 'object' &&
    record.video.metadata.embedding &&
    typeof record.video.metadata.embedding === 'object'
      ? record.video.metadata.embedding
      : null;
  if (metadataEmbedding) {
    const metadataId = extractEmbeddingIdentifier(metadataEmbedding);
    if (metadataId) {
      return metadataId;
    }
  }

  return '';
}

function rememberEmbeddingState(recording, record) {
  const key = getAnalysisCacheKey(recording);
  if (!key) {
    return;
  }

  const rawStatus =
    record &&
    typeof record === 'object' &&
    record.embeddingStatus &&
    typeof record.embeddingStatus === 'object'
      ? record.embeddingStatus
      : null;
  const statusInfo = rawStatus ? extractEmbeddingStatus(record) : null;
  let sharedEmbeddingStatusUpdate = null;
  if (recording && typeof recording === 'object') {
    if (statusInfo) {
      const nextStatus = {
        state: statusInfo.state,
        updatedAt: statusInfo.updatedAt || rawStatus?.updatedAt || new Date().toISOString(),
      };
      if (statusInfo.message) {
        nextStatus.message = statusInfo.message;
      }
      if (Array.isArray(statusInfo.options) && statusInfo.options.length > 0) {
        nextStatus.options = [...statusInfo.options];
      }
      if (Array.isArray(statusInfo.missingOptions) && statusInfo.missingOptions.length > 0) {
        nextStatus.missingOptions = [...statusInfo.missingOptions];
      }
      if (rawStatus && Object.prototype.hasOwnProperty.call(rawStatus, 'includeTranscription')) {
        nextStatus.includeTranscription = Boolean(statusInfo.includeTranscription);
      }
      nextStatus.__timestamp = Date.now();
      recording.embeddingStatus = nextStatus;
      sharedEmbeddingStatusUpdate = { ...nextStatus };
    } else if (record == null) {
      delete recording.embeddingStatus;
    }
  }

  const sources = [];
  if (record && typeof record === 'object') {
    sources.push(record);
  }
  if (recording && typeof recording === 'object' && recording !== record) {
    sources.push(recording);
  }

  let identifier = '';
  for (const source of sources) {
    identifier = resolveEmbeddingIdentifier(source);
    if (identifier) {
      break;
    }
  }

  if (identifier) {
    embeddingSuccessCache.set(key, identifier);
  } else {
    let readyStatus = null;
    for (const source of sources) {
      const statusInfo = extractEmbeddingStatus(source);
      if (isEmbeddingReadyStatus(statusInfo)) {
        readyStatus = statusInfo;
        break;
      }
    }
    if (readyStatus) {
      const state = getWorkflowStateValue(readyStatus) || 'ready';
      const marker = readyStatus.updatedAt || `${state}:${Date.now()}`;
      embeddingSuccessCache.set(key, marker);
    } else {
      embeddingSuccessCache.delete(key);
    }
  }

  updateEmbeddingMonitor(key, recording, record || recording);
  if (sharedEmbeddingStatusUpdate) {
    updateWorkflowSyncStateByKey(key, { embedding: sharedEmbeddingStatusUpdate });
  } else if (record == null) {
    updateWorkflowSyncStateByKey(key, { embedding: null });
  }
}

function hasEmbeddingSuccess(recording) {
  const key = getAnalysisCacheKey(recording);
  if (!key) {
    return false;
  }
  if (embeddingSuccessCache.has(key)) {
    return true;
  }

  const sources = [];
  const cached = analysisCache.get(key);
  if (cached?.record) {
    sources.push(cached.record);
  }
  if (recording && typeof recording === 'object') {
    sources.push(recording);
  }

  for (const source of sources) {
    const identifier = resolveEmbeddingIdentifier(source);
    if (identifier) {
      embeddingSuccessCache.set(key, identifier);
      return true;
    }
  }

  for (const source of sources) {
    const statusInfo = extractEmbeddingStatus(source);
    if (isEmbeddingReadyStatus(statusInfo)) {
      const state = getWorkflowStateValue(statusInfo) || 'ready';
      const marker = statusInfo.updatedAt || `${state}:${Date.now()}`;
      embeddingSuccessCache.set(key, marker);
      return true;
    }
  }

  return false;
}
let activeAnalysisPromise = null;

const NETWORK_ERROR_PATTERNS = [
  /failed to fetch/i,
  /networkerror/i,
  /network error/i,
  /load failed/i,
];

function isNetworkFailureMessage(message) {
  if (typeof message !== 'string') {
    return false;
  }
  const trimmed = message.trim();
  if (!trimmed) {
    return false;
  }
  return NETWORK_ERROR_PATTERNS.some((pattern) => pattern.test(trimmed));
}

const ANALYSIS_INTEGRATION_DISABLED_CODES = new Set([
  'integration_unavailable',
  'recordings_unavailable',
  'missing_api_key',
  'client_initialisation_failed',
  'initialisation_failed',
  'analysis_disabled',
]);

function resolveIntegrationErrorMessage(result, fallbackMessage) {
  const requestType =
    result && typeof result.requestType === 'string' ? result.requestType : null;
  const fallbackBase =
    fallbackMessage ||
    (requestType === 'embed'
      ? 'Failed to request Twelve Labs embeddings.'
      : requestType === 'start'
      ? 'Failed to request Twelve Labs analysis.'
      : 'Failed to retrieve the Twelve Labs analysis status.');
  const fallback =
    fallbackBase || 'Failed to obtain a response from the Twelve Labs service.';
  if (!result || typeof result !== 'object') {
    return fallback;
  }

  const messageCandidates = [];
  if (typeof result.message === 'string' && result.message.trim()) {
    messageCandidates.push(result.message.trim());
  }
  if (typeof result.error === 'string' && result.error.trim()) {
    messageCandidates.push(result.error.trim());
  }
  if (Array.isArray(result.warnings)) {
    result.warnings
      .filter((warning) => typeof warning === 'string' && warning.trim())
      .forEach((warning) => {
        messageCandidates.push(warning.trim());
      });
  }

  const meaningfulCandidate = messageCandidates.find(
    (message) => !isNetworkFailureMessage(message)
  );
  if (meaningfulCandidate) {
    return meaningfulCandidate;
  }

  if (result.details && typeof result.details === 'object') {
    const detailMessages = [];
    if (typeof result.details.message === 'string' && result.details.message.trim()) {
      detailMessages.push(result.details.message.trim());
    }
    if (typeof result.details.error === 'string' && result.details.error.trim()) {
      detailMessages.push(result.details.error.trim());
    }
    const meaningfulDetail = detailMessages.find(
      (message) => !isNetworkFailureMessage(message)
    );
    if (meaningfulDetail) {
      return meaningfulDetail;
    }
  }

  if (result.status === 'network_error') {
    const originalMessage =
      (result.details && typeof result.details === 'object'
        ? result.details.message || result.details.error
        : null) ||
      result.error ||
      result.message ||
      '';
    return buildNetworkFailureMessage(requestType, originalMessage);
  }

  return fallback;
}

function isAnalysisIntegrationError(result) {
  if (!result || typeof result !== 'object') {
    return false;
  }
  const code = typeof result.code === 'string' ? result.code : null;
  if (code && ANALYSIS_INTEGRATION_DISABLED_CODES.has(code)) {
    return true;
  }
  const status = typeof result.status === 'string' ? result.status : null;
  if (status && ANALYSIS_INTEGRATION_DISABLED_CODES.has(status)) {
    return true;
  }
  const message =
    (typeof result.error === 'string' && result.error) ||
    (typeof result.message === 'string' && result.message) ||
    '';
  return message.toLowerCase().includes('not configured');
}

function disableAnalysisIntegration(recording, message, code) {
  analysisIntegrationAvailable = false;
  const fallbackMessage =
    message ||
    'Configure the Twelve Labs integration on the server to enable analysis and embeddings.';
  setAnalysisView(recording, 'disabled', fallbackMessage, null, false, code || null);
  renderRecordingsList();
}

const ANALYSIS_STATUS_CONFIG = {
  idle: { label: 'Idle', tone: 'muted' },
  ready: { label: 'Ready', tone: 'ready' },
  loading: { label: 'Analyzing…', tone: 'pending' },
  embedding: { label: 'Embedding…', tone: 'pending' },
  pending: { label: 'Embedding…', tone: 'pending' },
  cached: { label: 'Completed', tone: 'ready' },
  missing: { label: 'No analysis', tone: 'muted' },
  error: { label: 'Error', tone: 'warning' },
  disabled: { label: 'Unavailable', tone: 'warning' },
};

if (!analysisIntegrationAvailable) {
  analysisViewState.status = 'disabled';
  analysisViewState.message =
    'Configure the Twelve Labs API integration on the server to enable video analysis.';
}

function getAnalysisIdentifiers(recording) {
  if (!recording || typeof recording !== 'object') {
    return null;
  }
  const streamIdValue =
    typeof recording.streamId === 'string' && recording.streamId.trim().length > 0
      ? recording.streamId.trim()
      : typeof recording.StreamID === 'string' && recording.StreamID.trim().length > 0
      ? recording.StreamID.trim()
      : '';
  let fileNameValue =
    typeof recording.fileName === 'string' && recording.fileName.trim().length > 0
      ? recording.fileName.trim()
      : typeof recording.FileName === 'string' && recording.FileName.trim().length > 0
      ? recording.FileName.trim()
      : '';
  if (!fileNameValue) {
    const urlValue =
      (typeof recording.url === 'string' && recording.url.trim()) ||
      (typeof recording.URL === 'string' && recording.URL.trim());
    if (urlValue) {
      try {
        const parsed = new URL(urlValue, window.location.href);
        const segments = parsed.pathname.split('/').filter((segment) => segment.length > 0);
        if (segments.length > 0) {
          fileNameValue = segments[segments.length - 1];
        }
      } catch (error) {
        // Ignore parse errors and fall back to the recording ID.
      }
    }
  }
  if (!fileNameValue && typeof recording.id === 'string' && recording.id.includes('/')) {
    const parts = recording.id.split('/').filter((segment) => segment.length > 0);
    if (parts.length > 0) {
      fileNameValue = parts[parts.length - 1];
    }
  }
  if (!fileNameValue && typeof recording.displayName === 'string') {
    fileNameValue = recording.displayName.trim();
  }
  if (!fileNameValue) {
    return null;
  }
  return { streamId: streamIdValue, fileName: fileNameValue };
}

function getAnalysisCacheKey(recording) {
  const identifiers = getAnalysisIdentifiers(recording);
  if (!identifiers) {
    return null;
  }
  return `${identifiers.streamId || 'default'}::${identifiers.fileName}`;
}

function getCachedAnalysis(recording) {
  const key = getAnalysisCacheKey(recording);
  if (!key) {
    return null;
  }
  return analysisCache.get(key) || null;
}

function recordHasAnalysisContent(record) {
  if (!record || typeof record !== 'object') {
    return false;
  }

  if (record.analysisAvailable === true) {
    return true;
  }

  const stageCandidate =
    typeof record.analysisStage === 'string' && record.analysisStage.trim().length > 0
      ? record.analysisStage.trim().toLowerCase()
      : '';
  if (stageCandidate === 'end') {
    return true;
  }

  const analysisStatus =
    record.analysisStatus && typeof record.analysisStatus === 'object'
      ? record.analysisStatus
      : null;
  if (analysisStatus && isWorkflowReadyStatus(analysisStatus)) {
    return true;
  }

  const gist = record?.gist;
  if (gist && typeof gist === 'object' && Object.keys(gist).length > 0) {
    return true;
  }

  const analysis = record?.analysis;
  if (analysis && typeof analysis === 'object') {
    const analysisText =
      typeof analysis.text === 'string' && analysis.text.trim().length > 0
        ? analysis.text.trim()
        : '';
    if (analysisText) {
      return true;
    }
    const analysisChunks = Array.isArray(analysis.chunks)
      ? analysis.chunks.filter((chunk) => typeof chunk === 'string' && chunk.trim().length > 0)
      : [];
    if (analysisChunks.length > 0) {
      return true;
    }
    const analysisKeys = Object.keys(analysis);
    if (analysisKeys.length > 0) {
      if (
        analysisKeys.some((key) => key !== 'prompt') ||
        (typeof analysis.prompt === 'string' && analysis.prompt.trim().length > 0)
      ) {
        return true;
      }
    }
  }

  const summary = record?.summary;
  if (summary && typeof summary === 'object' && Object.keys(summary).length > 0) {
    return true;
  }

  const insights = record?.insights;
  if (Array.isArray(insights) && insights.length > 0) {
    return true;
  }

  return false;
}

function storeAnalysisRecord(recording, record, cached) {
  const key = getAnalysisCacheKey(recording);
  if (!key || !record) {
    rememberEmbeddingState(recording, null);
    return;
  }
  analysisCache.set(key, {
    record,
    cached: Boolean(cached),
    hasAnalysis: recordHasAnalysisContent(record),
    checkedAt: Date.now(),
  });
  rememberEmbeddingState(recording, record);
}

function buildAnalysisCompleteMessage(record, cached) {
  if (!record || typeof record !== 'object') {
    return 'Twelve Labs analysis completed.';
  }
  if (!recordHasAnalysisContent(record)) {
    return cached
      ? 'No stored Twelve Labs analysis is available yet. Press “Analyze” to generate insights.'
      : 'Twelve Labs analysis has not been generated yet. Press “Analyze” to request insights.';
  }
  const updatedAt =
    typeof record.updatedAt === 'string' && record.updatedAt ? new Date(record.updatedAt) : null;
  const formatted =
    updatedAt && !Number.isNaN(updatedAt.valueOf())
      ? updatedAt.toLocaleString()
      : typeof record.updatedAt === 'string'
      ? record.updatedAt
      : '';
  return formatted
    ? `Twelve Labs analysis completed on ${formatted}.`
    : 'Twelve Labs analysis completed.';
}

function buildEmbeddingsMessage(record, cached) {
  if (!record || typeof record !== 'object') {
    return cached
      ? 'Showing stored Twelve Labs embeddings.'
      : 'Twelve Labs embeddings retrieved.';
  }
  const statusInfo = extractEmbeddingStatus(record);
  if (isEmbeddingPendingStatus(statusInfo)) {
    return buildPendingEmbeddingMessage(null, record, statusInfo);
  }
  if (isEmbeddingErrorStatus(statusInfo)) {
    return (
      statusInfo.message || 'Failed to retrieve Twelve Labs embeddings for this recording.'
    );
  }
  const embeddings = record.embeddings;
  if (!embeddings || typeof embeddings !== 'object') {
    return cached
      ? 'No stored Twelve Labs embeddings available yet.'
      : 'Twelve Labs embeddings retrieved.';
  }
  const options = Array.isArray(embeddings.options) && embeddings.options.length > 0
    ? embeddings.options.join(', ')
    : 'default settings';
  const retrievedAt =
    typeof embeddings.retrievedAt === 'string' && embeddings.retrievedAt
      ? new Date(embeddings.retrievedAt)
      : null;
  const formatted =
    retrievedAt && !Number.isNaN(retrievedAt.valueOf())
      ? retrievedAt.toLocaleString()
      : typeof embeddings.retrievedAt === 'string'
      ? embeddings.retrievedAt
      : '';
  if (cached) {
    return formatted
      ? `Showing stored Twelve Labs embeddings (${options}) from ${formatted}.`
      : `Showing stored Twelve Labs embeddings (${options}).`;
  }
  return formatted
    ? `Twelve Labs embeddings (${options}) retrieved on ${formatted}.`
    : `Twelve Labs embeddings (${options}) retrieved.`;
}

function combineAnalysisAndEmbeddingMessages(record, baseMessage, cached) {
  if (!record || typeof record !== 'object') {
    return baseMessage;
  }
  const hasEmbeddings = record.embeddings && typeof record.embeddings === 'object';
  if (!hasEmbeddings) {
    return baseMessage;
  }
  const embeddingMessage = buildEmbeddingsMessage(record, cached);
  if (!embeddingMessage) {
    return baseMessage;
  }
  if (!baseMessage) {
    return embeddingMessage;
  }
  if (baseMessage.includes(embeddingMessage)) {
    return baseMessage;
  }
  return `${baseMessage} ${embeddingMessage}`;
}

async function fetchAnalysis(recording, options = {}) {
  const {
    start = false,
    embed = false,
    includeTranscription = false,
    embeddingOptions = [],
  } = options;
  const requestType = start ? 'start' : embed ? 'embed' : 'status';
  if (!analysisEndpoint) {
    return {
      ok: false,
      status: 'disabled',
      error: 'Analysis endpoint is unavailable.',
      requestType,
    };
  }

  const identifiers = getAnalysisIdentifiers(recording);
  if (!identifiers) {
    return {
      ok: false,
      status: 'error',
      error: 'Recording is missing stream or filename metadata.',
    };
  }

  const url = new URL(analysisEndpoint.toString());
  if (identifiers.streamId) {
    url.searchParams.set('streamId', identifiers.streamId);
  }
  url.searchParams.set('fileName', identifiers.fileName);
  if (start) {
    url.searchParams.set('action', 'start');
    if (analysisPromptValue) {
      url.searchParams.set('prompt', analysisPromptValue);
    }
    if (analysisTemperatureParam) {
      url.searchParams.set('temperature', analysisTemperatureParam);
    }
    if (analysisMaxTokensParam) {
      url.searchParams.set('maxTokens', analysisMaxTokensParam);
    }
  } else if (embed) {
    url.searchParams.set('action', 'embed');
    if (includeTranscription) {
      url.searchParams.set('transcription', 'true');
    }
    if (Array.isArray(embeddingOptions) && embeddingOptions.length > 0) {
      embeddingOptions.forEach((option) => {
        if (typeof option === 'string' && option.trim()) {
          url.searchParams.append('embeddingOption', option.trim());
        }
      });
    }
  } else {
    url.searchParams.set('action', 'status');
  }

  let response;
  try {
    response = await fetch(url.toString(), { cache: 'no-store' });
  } catch (error) {
    const originalMessage = error instanceof Error ? error.message : String(error);
    const errorMessage = buildNetworkFailureMessage(requestType, originalMessage);
    return {
      ok: false,
      status: 'network_error',
      error: errorMessage,
      message: errorMessage,
      code: 'network_error',
      requestType,
      details: { message: originalMessage },
    };
  }

  let payload = null;
  const contentType = response.headers.get('Content-Type') || '';
  if (contentType.includes('application/json')) {
    try {
      payload = await response.json();
    } catch (error) {
      payload = null;
    }
  } else {
    try {
      payload = { raw: await response.text() };
    } catch (error) {
      payload = null;
    }
  }

  if (!response.ok) {
    const status = (payload && payload.status) || 'error';
    const errorMessage =
      (payload && (payload.message || payload.error)) || `HTTP ${response.status}`;
    return {
      ok: false,
      status,
      error: errorMessage,
      message: errorMessage,
      code: payload && (payload.error || payload.code),
      details: payload,
      requestType,
    };
  }

  const record = payload && payload.record ? payload.record : null;
  const cached = Boolean(payload && (payload.cached || payload.status === 'cached'));
  const status = (payload && payload.status) || (cached ? 'cached' : 'ok');
  const message = (payload && payload.message) || '';
  const embeddingStatus = record ? extractEmbeddingStatus(record) : null;
  let embeddingStage =
    payload && typeof payload.embeddingStage === 'string'
      ? payload.embeddingStage.trim().toLowerCase()
      : '';
  if (!embeddingStage && embeddingStatus) {
    embeddingStage = getWorkflowStageValue(embeddingStatus);
  }
  return {
    ok: true,
    status,
    record,
    cached,
    message,
    embeddingStage,
    embeddingStatus,
    requestType,
  };
}

function formatRecordingDuration(durationSeconds) {
  if (!Number.isFinite(durationSeconds) || durationSeconds <= 0) {
    return '';
  }
  const totalSeconds = Math.round(durationSeconds);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const segments = [];
  if (hours > 0) {
    segments.push(`${hours}h`);
  }
  if (minutes > 0 || hours > 0) {
    const minuteValue = hours > 0 ? minutes.toString().padStart(2, '0') : minutes;
    segments.push(`${minuteValue}m`);
  }
  if (hours === 0) {
    if (minutes > 0 || seconds > 0) {
      const secondValue = minutes > 0 ? seconds.toString().padStart(2, '0') : seconds;
      segments.push(`${secondValue}s`);
    }
  } else if (seconds > 0) {
    segments.push(`${seconds.toString().padStart(2, '0')}s`);
  }
  return segments.join(' ');
}

function formatTimestampDisplay(value) {
  if (!value) {
    return '';
  }
  if (value instanceof Date && !Number.isNaN(value.valueOf())) {
    return value.toLocaleString();
  }
  const parsed = new Date(value);
  if (!Number.isNaN(parsed.valueOf())) {
    return parsed.toLocaleString();
  }
  return typeof value === 'string' ? value : '';
}

function formatFileSize(sizeBytes) {
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) {
    return '';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = sizeBytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const precision = unitIndex === 0 || value >= 100 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(precision)} ${units[unitIndex]}`;
}

function normaliseRecording(recording) {
  if (!recording || typeof recording !== 'object') {
    return null;
  }
  const nameRaw = typeof recording.name === 'string' ? recording.name.trim() : '';
  const idRaw = typeof recording.id === 'string' ? recording.id.trim() : '';
  const displayName = nameRaw || idRaw || 'Recording';
  const slug = displayName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  const id = idRaw || slug || `recording-${Date.now()}`;

  const recordedAtSource =
    recording.recordedAt ?? recording.capturedAt ?? recording.timestamp ?? recording.createdAt;
  let recordedAt = null;
  let recordedAtText = '';
  if (recordedAtSource !== undefined && recordedAtSource !== null) {
    const parsed = new Date(recordedAtSource);
    if (!Number.isNaN(parsed.valueOf())) {
      recordedAt = parsed;
      recordedAtText = parsed.toLocaleString();
    }
  }
  if (!recordedAtText) {
    if (typeof recording.recordedAtText === 'string' && recording.recordedAtText.trim()) {
      recordedAtText = recording.recordedAtText.trim();
    } else if (
      typeof recording.recordedAtDisplay === 'string' &&
      recording.recordedAtDisplay.trim()
    ) {
      recordedAtText = recording.recordedAtDisplay.trim();
    }
  }

  const durationSeconds = Number.isFinite(recording.durationSeconds)
    ? recording.durationSeconds
    : Number.isFinite(recording.duration)
    ? recording.duration
    : Number.isFinite(recording.lengthSeconds)
    ? recording.lengthSeconds
    : null;
  const formattedDuration = formatRecordingDuration(durationSeconds);
  const durationText =
    typeof recording.durationText === 'string' && recording.durationText.trim().length > 0
      ? recording.durationText.trim()
      : formattedDuration;

  const sizeBytes = Number.isFinite(recording.sizeBytes)
    ? recording.sizeBytes
    : Number.isFinite(recording.size)
    ? recording.size
    : Number.isFinite(recording.fileSize)
    ? recording.fileSize
    : null;
  const formattedSize = formatFileSize(sizeBytes);
  const sizeText =
    typeof recording.sizeText === 'string' && recording.sizeText.trim().length > 0
      ? recording.sizeText.trim()
      : typeof recording.size === 'string' && recording.size.trim().length > 0
      ? recording.size.trim()
      : formattedSize;

  const location = typeof recording.location === 'string' ? recording.location.trim() : '';
  const notes = typeof recording.notes === 'string' ? recording.notes.trim() : '';
  const tags = Array.isArray(recording.tags)
    ? recording.tags
        .map((tag) => (typeof tag === 'string' ? tag.trim() : ''))
        .filter((tag) => tag.length > 0)
    : [];

  const metaParts = [];
  if (recordedAtText) {
    metaParts.push(recordedAtText);
  }
  if (durationText) {
    metaParts.push(durationText);
  }
  if (sizeText) {
    metaParts.push(sizeText);
  }
  if (location) {
    metaParts.push(location);
  }

  return {
    ...recording,
    id,
    displayName,
    recordedAt,
    recordedAtText,
    durationSeconds,
    durationText,
    sizeBytes,
    sizeText,
    location,
    notes,
    tags,
    metaSummary: metaParts.join(' • '),
  };
}

function updateRecordingsSummary() {
  if (!recordingsSummaryElement) {
    return;
  }
  if (recordingsState.isLoading) {
    recordingsSummaryElement.textContent = 'Loading recordings…';
    return;
  }
  if (!Array.isArray(recordingsState.items) || recordingsState.items.length === 0) {
    recordingsSummaryElement.textContent = 'No recordings synced yet.';
    return;
  }
  const count = recordingsState.items.length;
  const countLabel = count === 1 ? '1 recording' : `${count} recordings`;
  const timestamp =
    recordingsState.lastUpdated instanceof Date
      ? recordingsState.lastUpdated.toLocaleTimeString()
      : 'recently';
  recordingsSummaryElement.textContent = `Showing ${countLabel}. Synced ${timestamp}.`;
}

function renderRecordingsList() {
  if (!recordingsListElement) {
    return;
  }
  recordingsListElement.innerHTML = '';
  if (recordingsState.isLoading) {
    const placeholder = document.createElement('li');
    placeholder.className = 'recordings-placeholder';
    placeholder.textContent = 'Loading recordings…';
    recordingsListElement.appendChild(placeholder);
    updateRecordingsSummary();
    syncActiveAnalysisView();
    return;
  }
  if (!Array.isArray(recordingsState.items) || recordingsState.items.length === 0) {
    const emptyMessage = document.createElement('li');
    emptyMessage.className = 'recordings-placeholder';
    emptyMessage.textContent = 'No recordings available yet. Capture missions to populate this list.';
    recordingsListElement.appendChild(emptyMessage);
    updateRecordingsSummary();
    syncActiveAnalysisView();
    return;
  }

  recordingsState.items.forEach((recording) => {
    const item = document.createElement('li');
    item.className = 'recording-item';
    if (recording.id === selectedRecordingId) {
      item.classList.add('active');
    }

    const selectButton = document.createElement('button');
    selectButton.type = 'button';
    selectButton.className = 'recording-select';
    selectButton.addEventListener('click', () => {
      setSelectedRecording(recording);
    });

    const nameElement = document.createElement('span');
    nameElement.className = 'recording-name';
    nameElement.textContent = recording.displayName;
    selectButton.appendChild(nameElement);

    const metaElement = document.createElement('span');
    metaElement.className = 'recording-meta';
    metaElement.textContent = recording.metaSummary || 'Metadata pending';
    selectButton.appendChild(metaElement);

    if (Array.isArray(recording.tags) && recording.tags.length > 0) {
      const tagsContainer = document.createElement('span');
      tagsContainer.className = 'recording-tags';
      recording.tags.forEach((tag) => {
        const tagElement = document.createElement('span');
        tagElement.className = 'recording-tag';
        tagElement.textContent = tag;
        tagsContainer.appendChild(tagElement);
      });
      selectButton.appendChild(tagsContainer);
    }

    item.appendChild(selectButton);

    const actionsContainer = document.createElement('div');
    actionsContainer.className = 'recording-actions';

    const analyzeButton = document.createElement('button');
    analyzeButton.type = 'button';
    analyzeButton.className = 'recording-action-button analyze-button';
    analyzeButton.textContent = 'Analyze';
    analyzeButton.addEventListener('click', (event) => {
      event.stopPropagation();
      setSelectedRecording(recording, {
        triggerLoad: false,
        preserveExistingResult: true,
        suppressInitialStatus: true,
      });
      void requestRecordingAnalysis(recording);
    });

    const embedButton = document.createElement('button');
    embedButton.type = 'button';
    embedButton.className = 'recording-action-button embed-button';
    embedButton.textContent = 'Embeddings';
    embedButton.addEventListener('click', async (event) => {
      event.stopPropagation();
      setSelectedRecording(recording, { suppressInitialStatus: true });
      const latestCached = getCachedAnalysis(recording);
      const latestStatus = getEffectiveEmbeddingStatus(
        recording,
        latestCached?.record,
        recording
      );
      if (isEmbeddingPendingStatus(latestStatus)) {
        embedButton.disabled = true;
        embedButton.textContent = 'Embedding…';
        embedButton.title =
          latestStatus?.message ||
          'Twelve Labs embeddings are still processing for this recording.';
        return;
      }
      embedButton.disabled = true;
      try {
        await requestRecordingEmbeddings(recording);
      } finally {
        if (!document.body.contains(embedButton)) {
          return;
        }
        const refreshedCached = getCachedAnalysis(recording);
        const refreshedStatus = getEffectiveEmbeddingStatus(
          recording,
          refreshedCached?.record,
          recording
        );
        if (isEmbeddingPendingStatus(refreshedStatus)) {
          embedButton.disabled = true;
          embedButton.textContent = 'Embedding…';
          embedButton.title =
            refreshedStatus?.message ||
            'Twelve Labs embeddings are still processing for this recording.';
          return;
        }
        embedButton.disabled = false;
      }
    });

    const cached = getCachedAnalysis(recording);
    if (!analysisIntegrationAvailable) {
      analyzeButton.disabled = true;
      analyzeButton.title = 'Configure the Twelve Labs integration to enable analysis.';
      embedButton.disabled = true;
      embedButton.title = 'Configure the Twelve Labs integration to enable embeddings.';
    } else {
      const hasCachedRecord = Boolean(cached?.record);
      const hasCachedAnalysis = Boolean(cached?.hasAnalysis);
      const hasInlineAnalysis = recordHasAnalysisContent(recording);
      const hasAnalysisAvailable = hasCachedAnalysis || hasInlineAnalysis;
      const statusInfo = getEffectiveEmbeddingStatus(recording, cached?.record, recording);
      const sharedEmbeddingStatus = getSharedEmbeddingStatus(recording);
      const pendingStatusInfo = isEmbeddingPendingStatus(statusInfo)
        ? statusInfo
        : isWorkflowPendingStatus(sharedEmbeddingStatus)
        ? sharedEmbeddingStatus
        : null;
      const embeddingPending = Boolean(pendingStatusInfo);
      const embeddingReady =
        hasEmbeddingSuccess(recording) ||
        isEmbeddingReadyStatus(statusInfo) ||
        isEmbeddingReadyStatus(sharedEmbeddingStatus);
      const sharedAnalysisStatus = getSharedAnalysisStatus(recording);
      const analysisPendingShared = isWorkflowPendingStatus(sharedAnalysisStatus);

      analyzeButton.textContent = hasAnalysisAvailable ? 'View analysis' : 'Analyze';
      embedButton.textContent = embeddingReady ? 'View embeddings' : 'Embeddings';

      if (embeddingPending) {
        embedButton.disabled = true;
        embedButton.textContent = 'Embedding…';
        const pendingMessage =
          pendingStatusInfo?.message ||
          statusInfo?.message ||
          sharedEmbeddingStatus?.message ||
          'Twelve Labs embeddings are still processing for this recording.';
        embedButton.title = pendingMessage;
        analyzeButton.disabled = true;
        analyzeButton.title = pendingMessage;
      } else {
        embedButton.disabled = false;
        embedButton.textContent = embeddingReady ? 'View embeddings' : 'Embeddings';
        embedButton.title = embeddingReady
          ? 'Retrieve Twelve Labs embeddings for this recording.'
          : 'Upload to Twelve Labs and retrieve embeddings for this recording.';
        if (analysisPendingShared) {
          analyzeButton.disabled = true;
          analyzeButton.textContent = 'Analyzing…';
          analyzeButton.title =
            sharedAnalysisStatus?.message ||
            'Twelve Labs analysis is processing for this recording.';
        } else {
          analyzeButton.disabled = !embeddingReady;
          analyzeButton.title = embeddingReady
            ? hasAnalysisAvailable || hasCachedRecord
              ? 'View Twelve Labs analysis for this recording.'
              : 'Request Twelve Labs analysis for this recording.'
            : 'Run embeddings before requesting Twelve Labs analysis.';
        }
      }
    }

    actionsContainer.appendChild(embedButton);
    actionsContainer.appendChild(analyzeButton);
    item.appendChild(actionsContainer);

    recordingsListElement.appendChild(item);
  });

  updateRecordingsSummary();
  syncActiveAnalysisView();
}

function appendAnalysisMeta(container, label, value) {
  if (!container || !label || value === undefined || value === null || value === '') {
    return;
  }
  const dt = document.createElement('dt');
  dt.textContent = label;
  const dd = document.createElement('dd');
  dd.textContent = value;
  container.append(dt, dd);
}

function extractHlsUrl(record) {
  if (!record || typeof record !== 'object') {
    return '';
  }
  const videoInfo = record.video && typeof record.video === 'object' ? record.video : null;
  if (videoInfo) {
    const direct =
      typeof videoInfo.hlsUrl === 'string' && videoInfo.hlsUrl.trim() ? videoInfo.hlsUrl.trim() : '';
    if (direct) {
      return direct;
    }
  }
  const metadata = videoInfo && typeof videoInfo.metadata === 'object' ? videoInfo.metadata : null;
  const fallbackMetadata = record.metadata && typeof record.metadata === 'object' ? record.metadata : null;
  const source = metadata || fallbackMetadata;
  if (source && typeof source === 'object') {
    const hls = source.hls && typeof source.hls === 'object' ? source.hls : null;
    if (hls) {
      for (const key of ['video_url', 'videoUrl', 'url']) {
        const value = hls[key];
        if (typeof value === 'string' && value.trim()) {
          return value.trim();
        }
      }
    }
  }
  return '';
}

function initialiseHlsPlayback(videoElement, sourceUrl) {
  if (!videoElement || !sourceUrl) {
    return false;
  }
  if (window.Hls && window.Hls.isSupported && window.Hls.isSupported()) {
    try {
      const hls = new window.Hls();
      hls.loadSource(sourceUrl);
      hls.attachMedia(videoElement);
      videoElement.dataset.hlsAttached = 'true';
      videoElement._hlsInstance = hls; // eslint-disable-line no-underscore-dangle
      return true;
    } catch (error) {
      console.error('Failed to initialise HLS playback', error);
      return false;
    }
  }
  if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
    videoElement.src = sourceUrl;
    return true;
  }
  return false;
}

function createVideoPlaybackBlock(record) {
  const videoInfo = record?.video && typeof record.video === 'object' ? record.video : null;
  if (!videoInfo || Object.keys(videoInfo).length === 0) {
    return null;
  }

  const hlsUrl = extractHlsUrl(record);
  const section = document.createElement('section');
  section.className = 'analysis-video-block';

  const heading = document.createElement('h5');
  heading.textContent = 'Video playback';
  section.appendChild(heading);

  if (hlsUrl) {
    const videoWrapper = document.createElement('div');
    videoWrapper.className = 'analysis-video-player';

    const videoElement = document.createElement('video');
    videoElement.controls = true;
    videoElement.preload = 'metadata';
    videoElement.playsInline = true;
    videoElement.setAttribute('muted', '');
    videoWrapper.appendChild(videoElement);
    section.appendChild(videoWrapper);

    const initialised = initialiseHlsPlayback(videoElement, hlsUrl);
    if (initialised) {
      // When the browser can play HLS directly, avoid rendering the fallback note/link.
    } else {
      const linkParagraph = document.createElement('p');
      linkParagraph.className = 'analysis-video-note';
      const link = document.createElement('a');
      link.href = hlsUrl;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = 'Open HLS stream in a new tab';
      linkParagraph.textContent = '';
      linkParagraph.append(
        'This browser cannot play the Twelve Labs HLS stream directly. '
      );
      linkParagraph.appendChild(link);
      linkParagraph.append('. Use an HLS-compatible player to view the recording.');
      section.appendChild(linkParagraph);
    }
  } else {
    const placeholder = document.createElement('p');
    placeholder.className = 'analysis-video-note';
    placeholder.textContent = 'Twelve Labs has not exposed a streaming URL for this recording yet.';
    section.appendChild(placeholder);
  }

  const syncedAt = videoInfo.syncedAt ? formatTimestampDisplay(videoInfo.syncedAt) : '';
  if (syncedAt) {
    const syncParagraph = document.createElement('p');
    syncParagraph.className = 'analysis-video-note';
    syncParagraph.textContent = `Last synced: ${syncedAt}`;
    section.appendChild(syncParagraph);
  }

  return section;
}

function getEmbeddingVector(segment) {
  if (!segment || typeof segment !== 'object') {
    return [];
  }
  if (Array.isArray(segment.float)) {
    return segment.float;
  }
  if (Array.isArray(segment.float_)) {
    return segment.float_;
  }
  return [];
}

const EMBEDDING_OPTION_LABELS = new Map([
  ['visual', 'Visual'],
  ['text', 'Text'],
  ['audio', 'Audio'],
  ['visual-text', 'Visual + text'],
]);

function formatEmbeddingOptionLabel(option) {
  if (typeof option !== 'string') {
    return '';
  }
  const trimmed = option.trim();
  if (!trimmed) {
    return '';
  }
  const lower = trimmed.toLowerCase();
  if (EMBEDDING_OPTION_LABELS.has(lower)) {
    return EMBEDDING_OPTION_LABELS.get(lower);
  }
  const segments = trimmed.split(/[-_]/).map((segment) => segment.trim()).filter(Boolean);
  if (segments.length > 1) {
    const mapped = segments.map((segment) => {
      const segmentKey = segment.toLowerCase();
      if (EMBEDDING_OPTION_LABELS.has(segmentKey)) {
        return EMBEDDING_OPTION_LABELS.get(segmentKey);
      }
      return segment.charAt(0).toUpperCase() + segment.slice(1);
    });
    return mapped.join(' + ');
  }
  return trimmed.charAt(0).toUpperCase() + trimmed.slice(1);
}

function formatEmbeddingOptionsList(options) {
  if (!Array.isArray(options) || options.length === 0) {
    return '';
  }
  const formatted = options
    .map((option) => formatEmbeddingOptionLabel(option))
    .filter((label) => typeof label === 'string' && label);
  if (formatted.length === 0) {
    return '';
  }
  return formatted.join(', ');
}

function createEmbeddingsBlock(record, cached) {
  const embeddingsRoot =
    (record?.embeddings && typeof record.embeddings === 'object' && record.embeddings.response) ||
    (record?.video && record.video?.metadata && record.video.metadata.embedding);
  if (!embeddingsRoot || typeof embeddingsRoot !== 'object') {
    return null;
  }

  const section = document.createElement('section');
  section.className = 'analysis-embedding-block';

  const heading = document.createElement('h5');
  heading.textContent = 'Embeddings';
  section.appendChild(heading);

  const messageParagraph = document.createElement('p');
  messageParagraph.className = 'analysis-embedding-note';
  messageParagraph.textContent = buildEmbeddingsMessage(record, Boolean(cached));
  section.appendChild(messageParagraph);

  const metaGrid = document.createElement('dl');
  metaGrid.className = 'analysis-meta-grid';

  const optionsValue = formatEmbeddingOptionsList(record?.embeddings?.options);
  const optionsLabel = optionsValue || 'Default Twelve Labs settings';
  appendAnalysisMeta(metaGrid, 'Options', optionsLabel);

  const modelName =
    typeof embeddingsRoot.model_name === 'string' && embeddingsRoot.model_name
      ? embeddingsRoot.model_name
      : '';
  if (modelName) {
    appendAnalysisMeta(metaGrid, 'Model', modelName);
  }

  const videoEmbedding =
    embeddingsRoot.video_embedding && typeof embeddingsRoot.video_embedding === 'object'
      ? embeddingsRoot.video_embedding
      : null;
  const segments = Array.isArray(videoEmbedding?.segments) ? videoEmbedding.segments : [];

  if (segments.length > 0) {
    appendAnalysisMeta(metaGrid, 'Segments', String(segments.length));
    const vectorLength = getEmbeddingVector(segments[0]).length;
    if (vectorLength > 0) {
      appendAnalysisMeta(metaGrid, 'Vector length', String(vectorLength));
    }
  }

  const retrieved = record?.embeddings?.retrievedAt
    ? formatTimestampDisplay(record.embeddings.retrievedAt)
    : '';
  if (retrieved) {
    appendAnalysisMeta(metaGrid, 'Retrieved', retrieved);
  }

  const transcription = record?.embeddings?.transcription;
  if (Array.isArray(transcription) && transcription.length > 0) {
    appendAnalysisMeta(metaGrid, 'Transcription entries', String(transcription.length));
  }

  if (metaGrid.childNodes.length > 0) {
    section.appendChild(metaGrid);
  }

  return section;
}

function renderAnalysisResult(record, cached) {
  const section = document.createElement('section');
  section.className = 'analysis-result-block';

  const heading = document.createElement('h4');
  heading.textContent = 'Twelve Labs response';
  section.appendChild(heading);

  const metaGrid = document.createElement('dl');
  metaGrid.className = 'analysis-meta-grid';

  const updatedAt =
    typeof record?.updatedAt === 'string' && record.updatedAt
      ? new Date(record.updatedAt)
      : null;
  const updatedLabel =
    updatedAt && !Number.isNaN(updatedAt.valueOf())
      ? updatedAt.toLocaleString()
      : typeof record?.updatedAt === 'string'
      ? record.updatedAt
      : '';

  if (record?.videoId) {
    appendAnalysisMeta(metaGrid, 'Video ID', record.videoId);
  }
  const promptValue =
    (typeof record?.analysis?.prompt === 'string' && record.analysis.prompt) || record?.prompt;
  if (promptValue) {
    appendAnalysisMeta(metaGrid, 'Prompt', promptValue);
  }
  if (record?.temperature !== undefined && record.temperature !== null) {
    appendAnalysisMeta(metaGrid, 'Temperature', String(record.temperature));
  }
  if (record?.maxTokens !== undefined && record.maxTokens !== null) {
    appendAnalysisMeta(metaGrid, 'Max tokens', String(record.maxTokens));
  }
  if (updatedLabel) {
    appendAnalysisMeta(metaGrid, 'Generated', updatedLabel);
  }

  section.appendChild(metaGrid);

  const videoBlock = createVideoPlaybackBlock(record);
  if (videoBlock) {
    section.appendChild(videoBlock);
  }

  const embeddingsBlock = createEmbeddingsBlock(record, cached);
  if (embeddingsBlock) {
    section.appendChild(embeddingsBlock);
  }

  const formatLabel = (key) => {
    if (!key) {
      return 'Details';
    }
    return key
      .toString()
      .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
      .replace(/[_\-]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/\b\w/g, (character) => character.toUpperCase());
  };

  const normaliseStringArray = (value) => {
    if (!Array.isArray(value)) {
      return [];
    }
    return value
      .map((entry) => (typeof entry === 'string' ? entry.trim() : ''))
      .filter((entry) => entry.length > 0);
  };

  const pickFirstString = (source, keys) => {
    if (!source || typeof source !== 'object') {
      return { key: null, value: '' };
    }
    for (const key of keys) {
      const candidate = source[key];
      if (typeof candidate === 'string' && candidate.trim().length > 0) {
        return { key, value: candidate.trim() };
      }
    }
    return { key: null, value: '' };
  };

  const appendPlainParagraph = (container, text) => {
    if (!text) {
      return;
    }
    const paragraph = document.createElement('p');
    paragraph.textContent = text;
    container.appendChild(paragraph);
  };

  const appendLabeledParagraph = (container, label, text) => {
    if (!text) {
      return;
    }
    const paragraph = document.createElement('p');
    const labelSpan = document.createElement('span');
    labelSpan.className = 'analysis-output-label';
    labelSpan.textContent = `${label}:`;
    paragraph.appendChild(labelSpan);
    paragraph.appendChild(document.createTextNode(` ${text}`));
    container.appendChild(paragraph);
  };

  const appendLabeledList = (container, label, items) => {
    if (!Array.isArray(items) || items.length === 0) {
      return;
    }
    const labelParagraph = document.createElement('p');
    const labelSpan = document.createElement('span');
    labelSpan.className = 'analysis-output-label';
    labelSpan.textContent = `${label}:`;
    labelParagraph.appendChild(labelSpan);
    container.appendChild(labelParagraph);

    const listElement = document.createElement('ul');
    items.forEach((item) => {
      const listItem = document.createElement('li');
      listItem.textContent = item;
      listElement.appendChild(listItem);
    });
    container.appendChild(listElement);
  };

  const appendJsonPre = (container, value) => {
    const pre = document.createElement('pre');
    try {
      pre.textContent = JSON.stringify(value, null, 2);
    } catch (error) {
      pre.textContent = String(value);
    }
    container.appendChild(pre);
  };

  const gistResponse = record?.gist?.response ?? record?.gist ?? null;
  if (gistResponse && typeof gistResponse === 'object') {
    const gistTitle =
      typeof gistResponse.title === 'string'
        ? gistResponse.title
        : typeof gistResponse.Title === 'string'
        ? gistResponse.Title
        : '';
    const gistTopics = Array.isArray(gistResponse.topics)
      ? gistResponse.topics
      : Array.isArray(gistResponse.Topics)
      ? gistResponse.Topics
      : [];
    const gistHashtags = Array.isArray(gistResponse.hashtags)
      ? gistResponse.hashtags
      : Array.isArray(gistResponse.Hashtags)
      ? gistResponse.Hashtags
      : [];

    if (gistTitle || gistTopics.length > 0 || gistHashtags.length > 0) {
      const gistBlock = document.createElement('div');
      gistBlock.className = 'analysis-output-body';

      const gistHeading = document.createElement('h5');
      gistHeading.textContent = 'Gist overview';
      gistBlock.appendChild(gistHeading);

      if (gistTitle) {
        const titleParagraph = document.createElement('p');
        titleParagraph.textContent = `Title: ${gistTitle}`;
        gistBlock.appendChild(titleParagraph);
      }

      if (gistTopics.length > 0) {
        const topicsParagraph = document.createElement('p');
        topicsParagraph.textContent = `Topics: ${gistTopics.join(', ')}`;
        gistBlock.appendChild(topicsParagraph);
      }

      if (gistHashtags.length > 0) {
        const hashtagsParagraph = document.createElement('p');
        hashtagsParagraph.textContent = `Hashtags: ${gistHashtags.join(', ')}`;
        gistBlock.appendChild(hashtagsParagraph);
      }

      section.appendChild(gistBlock);
    }
  }

  const summary = record?.summary;
  if (summary && typeof summary === 'object' && Object.keys(summary).length > 0) {
    const summaryBlock = document.createElement('div');
    summaryBlock.className = 'analysis-output-body';

    const summaryHeading = document.createElement('h5');
    summaryHeading.textContent = 'Summary';
    summaryBlock.appendChild(summaryHeading);

    let summaryContentAdded = false;
    const usedSummaryKeys = new Set();

    const { key: summaryTitleKey, value: summaryTitle } = pickFirstString(summary, [
      'title',
      'Title',
      'heading',
      'Heading',
    ]);
    if (summaryTitle) {
      appendLabeledParagraph(summaryBlock, formatLabel(summaryTitleKey || 'Title'), summaryTitle);
      if (summaryTitleKey) {
        usedSummaryKeys.add(summaryTitleKey);
      }
      summaryContentAdded = true;
    }

    const { key: summaryTextKey, value: summaryText } = pickFirstString(summary, [
      'text',
      'Text',
      'summary',
      'Summary',
      'description',
      'Description',
      'overview',
      'Overview',
    ]);
    if (summaryText) {
      appendPlainParagraph(summaryBlock, summaryText);
      if (summaryTextKey) {
        usedSummaryKeys.add(summaryTextKey);
      }
      summaryContentAdded = true;
    }

    const additionalStrings = Object.entries(summary).filter(
      ([key, value]) =>
        !usedSummaryKeys.has(key) && typeof value === 'string' && value.trim().length > 0
    );
    additionalStrings.forEach(([key, value]) => {
      appendLabeledParagraph(summaryBlock, formatLabel(key), value.trim());
      usedSummaryKeys.add(key);
      summaryContentAdded = true;
    });

    const arrayEntries = Object.entries(summary).filter(
      ([key, value]) => !usedSummaryKeys.has(key) && Array.isArray(value)
    );
    arrayEntries.forEach(([key, value]) => {
      const items = normaliseStringArray(value);
      if (items.length === 0) {
        return;
      }
      appendLabeledList(summaryBlock, formatLabel(key), items);
      usedSummaryKeys.add(key);
      summaryContentAdded = true;
    });

    if (!summaryContentAdded) {
      appendJsonPre(summaryBlock, summary);
    }

    section.appendChild(summaryBlock);
  }

  const insightItems = Array.isArray(record?.insights) ? record.insights : [];
  if (insightItems.length > 0) {
    const insightsBlock = document.createElement('div');
    insightsBlock.className = 'analysis-output-body';

    const insightsHeading = document.createElement('h5');
    insightsHeading.textContent = 'Insights';
    insightsBlock.appendChild(insightsHeading);

    const listElement = document.createElement('ul');
    let insightsAdded = false;

    insightItems.forEach((insight) => {
      const listItem = document.createElement('li');
      const container = document.createElement('div');
      container.className = 'analysis-insight-item';
      let itemContentAdded = false;

      if (typeof insight === 'string') {
        const trimmed = insight.trim();
        if (trimmed) {
          appendPlainParagraph(container, trimmed);
          itemContentAdded = true;
        }
      } else if (insight && typeof insight === 'object') {
        const usedInsightKeys = new Set();
        const { key: titleKey, value: insightTitle } = pickFirstString(insight, [
          'title',
          'Title',
          'name',
          'Name',
          'label',
          'Label',
        ]);
        if (insightTitle) {
          const titleElement = document.createElement('span');
          titleElement.className = 'analysis-insight-item-title';
          titleElement.textContent = insightTitle;
          container.appendChild(titleElement);
          itemContentAdded = true;
          if (titleKey) {
            usedInsightKeys.add(titleKey);
          }
        }

        const { key: detailKey, value: detailText } = pickFirstString(insight, [
          'text',
          'Text',
          'detail',
          'Detail',
          'description',
          'Description',
          'summary',
          'Summary',
          'insight',
          'Insight',
        ]);
        if (detailText) {
          appendPlainParagraph(container, detailText);
          itemContentAdded = true;
          if (detailKey) {
            usedInsightKeys.add(detailKey);
          }
        }

        const arrayDetails = Object.entries(insight).filter(
          ([key, value]) =>
            !usedInsightKeys.has(key) &&
            Array.isArray(value) &&
            normaliseStringArray(value).length > 0
        );
        arrayDetails.forEach(([key, value]) => {
          const items = normaliseStringArray(value);
          if (items.length === 0) {
            return;
          }
          appendLabeledList(container, formatLabel(key), items);
          itemContentAdded = true;
          usedInsightKeys.add(key);
        });

        const simpleValues = Object.entries(insight).filter(([key, value]) => {
          if (usedInsightKeys.has(key)) {
            return false;
          }
          if (Array.isArray(value)) {
            return false;
          }
          return value !== null && typeof value !== 'object';
        });
        simpleValues.forEach(([key, value]) => {
          appendLabeledParagraph(container, formatLabel(key), String(value));
          itemContentAdded = true;
          usedInsightKeys.add(key);
        });
      }

      if (!itemContentAdded) {
        appendJsonPre(container, insight);
        itemContentAdded = true;
      }

      if (itemContentAdded) {
        listItem.appendChild(container);
        listElement.appendChild(listItem);
        insightsAdded = true;
      }
    });

    if (insightsAdded) {
      insightsBlock.appendChild(listElement);
    } else {
      appendJsonPre(insightsBlock, insightItems);
    }

    section.appendChild(insightsBlock);
  }

  const analysisText =
    typeof record?.analysis?.text === 'string' ? record.analysis.text.trim() : '';
  const analysisChunks = Array.isArray(record?.analysis?.chunks)
    ? record.analysis.chunks.filter((chunk) => typeof chunk === 'string' && chunk.trim().length > 0)
    : [];

  const textParagraphs = analysisText
    ? analysisText
        .split(/\n{2,}/)
        .map((paragraph) => paragraph.trim())
        .filter((paragraph) => paragraph.length > 0)
    : [];

  const paragraphs = textParagraphs.length > 0 ? textParagraphs : analysisChunks;

  const outputBlock = document.createElement('div');
  outputBlock.className = 'analysis-output-body';
  let analysisContentAdded = false;

  if (paragraphs.length > 0) {
    const analysisHeading = document.createElement('h5');
    analysisHeading.textContent = 'Analysis';
    outputBlock.appendChild(analysisHeading);

    paragraphs.forEach((paragraph) => {
      const p = document.createElement('p');
      p.textContent = paragraph;
      outputBlock.appendChild(p);
    });
    analysisContentAdded = true;
  } else if (record?.analysis) {
    appendJsonPre(outputBlock, record.analysis);
    analysisContentAdded = true;
  }

  if (analysisContentAdded) {
    section.appendChild(outputBlock);
  }

  return section;
}

function renderAnalysisView() {
  if (analysisRecordingTitle) {
    analysisRecordingTitle.textContent = analysisViewState.recording
      ? analysisViewState.recording.displayName
      : 'No recording selected';
  }
  if (analysisRecordingMeta) {
    analysisRecordingMeta.textContent = analysisViewState.recording
      ? analysisViewState.recording.metaSummary || 'Metadata pending integration.'
      : 'Choose a recording on the left to see its details.';
  }
  updateAnalysisStatusBadgeDisplay();
  if (analysisDetailsContainer) {
    analysisDetailsContainer.replaceChildren();
    if (analysisViewState.message) {
      const messageParagraph = document.createElement('p');
      messageParagraph.textContent = analysisViewState.message;
      analysisDetailsContainer.appendChild(messageParagraph);
    }
    if (analysisViewState.recording) {
      const grid = document.createElement('dl');
      grid.className = 'analysis-meta-grid';
      appendAnalysisMeta(grid, 'Filename', analysisViewState.recording.displayName);
      if (analysisViewState.recording.recordedAtText) {
        appendAnalysisMeta(grid, 'Captured', analysisViewState.recording.recordedAtText);
      }
      if (analysisViewState.recording.durationText) {
        appendAnalysisMeta(grid, 'Duration', analysisViewState.recording.durationText);
      }
      if (analysisViewState.recording.sizeText) {
        appendAnalysisMeta(grid, 'Size', analysisViewState.recording.sizeText);
      }
      if (analysisViewState.recording.location) {
        appendAnalysisMeta(grid, 'Location', analysisViewState.recording.location);
      }
      if (analysisViewState.recording.notes) {
        appendAnalysisMeta(grid, 'Notes', analysisViewState.recording.notes);
      }
      if (Array.isArray(analysisViewState.recording.tags) && analysisViewState.recording.tags.length > 0) {
        appendAnalysisMeta(grid, 'Tags', analysisViewState.recording.tags.join(', '));
      }
      analysisDetailsContainer.appendChild(grid);
    }
    if (analysisViewState.result) {
      const resultBlock = renderAnalysisResult(
        analysisViewState.result,
        Boolean(analysisViewState.cached)
      );
      analysisDetailsContainer.appendChild(resultBlock);
    }
  }

  lastRenderedAnalysisContent.recordingId = analysisViewState.recording?.id || null;
  lastRenderedAnalysisContent.message = analysisViewState.message;
  lastRenderedAnalysisContent.resultRef = analysisViewState.result;
  lastRenderedAnalysisContent.cached = Boolean(analysisViewState.cached);
  lastRenderedAnalysisContent.errorCode = analysisViewState.errorCode;
}

function updateAnalysisStatusBadgeDisplay() {
  if (!analysisStatusBadge) {
    return;
  }
  const config = ANALYSIS_STATUS_CONFIG[analysisViewState.status] || ANALYSIS_STATUS_CONFIG.idle;
  analysisStatusBadge.textContent = config.label;
  analysisStatusBadge.dataset.tone = config.tone;
}

function setAnalysisView(recording, status, message, result = null, cached = false, errorCode = null) {
  const resolvedMessage = message || defaultAnalysisMessage;
  const resolvedCached = Boolean(cached);
  const nextRecordingId = recording?.id || null;
  const statusOnlyChange =
    lastRenderedAnalysisContent.recordingId === nextRecordingId &&
    lastRenderedAnalysisContent.message === resolvedMessage &&
    lastRenderedAnalysisContent.resultRef === result &&
    lastRenderedAnalysisContent.cached === resolvedCached &&
    lastRenderedAnalysisContent.errorCode === errorCode;

  analysisViewState.recording = recording;
  analysisViewState.status = status;
  analysisViewState.message = resolvedMessage;
  analysisViewState.result = result;
  analysisViewState.cached = resolvedCached;
  analysisViewState.errorCode = errorCode;

  if (statusOnlyChange) {
    updateAnalysisStatusBadgeDisplay();
    return;
  }

  renderAnalysisView();
}

async function loadCachedAnalysis(recording, options = {}) {
  if (!analysisEndpoint || !analysisIntegrationAvailable) {
    return null;
  }
  const { force = false } = options;
  const key = getAnalysisCacheKey(recording);
  if (!key) {
    return null;
  }

  const existingEntry = analysisCache.get(key);
  const now = Date.now();
  if (!force && existingEntry) {
    const hasAnalysisRecord = Boolean(existingEntry.hasAnalysis) && Boolean(existingEntry.record);
    const lastChecked =
      typeof existingEntry.checkedAt === 'number' && Number.isFinite(existingEntry.checkedAt)
        ? existingEntry.checkedAt
        : 0;
    if (hasAnalysisRecord) {
      return existingEntry;
    }
    if (now - lastChecked < ANALYSIS_STATUS_REFRESH_INTERVAL_MS) {
      return existingEntry;
    }
  }

  if (analysisStatusRequests.has(key)) {
    return analysisStatusRequests.get(key);
  }

  const loadPromise = (async () => {
    try {
      const result = await fetchAnalysis(recording, { start: false });
      const timestamp = Date.now();
      if (!result.ok) {
        if (isAnalysisIntegrationError(result)) {
          disableAnalysisIntegration(
            recording,
            result.error || 'Twelve Labs analysis is not configured on the server.',
            result.code || result.status
          );
          return null;
        }
        const errorMessage = resolveIntegrationErrorMessage(
          result,
          'Failed to check the Twelve Labs analysis status.'
        );
        if (analysisViewState.recording?.id === recording.id) {
          setAnalysisView(recording, 'error', errorMessage, null, false, result.code || result.status);
        }
        const previousEntry = analysisCache.get(key);
        if (previousEntry) {
          analysisCache.set(key, { ...previousEntry, checkedAt: timestamp });
        } else {
          analysisCache.set(key, {
            record: null,
            cached: false,
            hasAnalysis: false,
            checkedAt: timestamp,
          });
        }
        return null;
      }

      if (!result.record) {
        const statusKey = (result.status || '').toLowerCase();
        if (['not_found', 'missing', 'empty'].includes(statusKey)) {
          const cacheKey = getAnalysisCacheKey(recording);
          if (cacheKey) {
            const previousEntry = analysisCache.get(cacheKey);
            const hasEmbeddingStatus =
              Boolean(extractEmbeddingStatus(recording)) || Boolean(getSharedEmbeddingStatus(recording));
            const nextEntry = {
              record: null,
              cached: false,
              hasAnalysis: false,
              checkedAt: timestamp,
            };
            analysisCache.set(cacheKey, previousEntry ? { ...previousEntry, ...nextEntry } : nextEntry);
            if (!hasEmbeddingStatus) {
              rememberEmbeddingState(recording, null);
            }
          }
          if (analysisViewState.recording?.id === recording.id) {
            const displayName =
              (recording && typeof recording.displayName === 'string' && recording.displayName) ||
              'this recording';
            const fallbackMessage =
              result.message ||
              `No stored Twelve Labs analysis found for “${displayName}” yet. Press “Analyze” to generate insights.`;
            setAnalysisView(recording, 'missing', fallbackMessage, null, false, result.code || result.status);
          }
        }
        return null;
      }

      const cachedResult = Boolean(result.cached);
      storeAnalysisRecord(recording, result.record, cachedResult);
      const updatedEntry = analysisCache.get(key);
      if (updatedEntry) {
        analysisCache.set(key, { ...updatedEntry, checkedAt: timestamp });
      }
      if (analysisViewState.recording?.id === recording.id) {
        const baseMessage =
          result.message || buildAnalysisCompleteMessage(result.record, cachedResult);
        const combinedMessage = combineAnalysisAndEmbeddingMessages(
          result.record,
          baseMessage,
          cachedResult
        );
        const defaultStatus = cachedResult ? 'cached' : result.status || 'ok';
        updateAnalysisViewWithRecord(
          recording,
          result.record,
          cachedResult,
          defaultStatus,
          combinedMessage,
          {
            embeddingStage: result.embeddingStage,
            embeddingStatus: result.embeddingStatus,
          }
        );
      }
      renderRecordingsList();
      return result;
    } catch (error) {
      console.error('Failed to load stored Twelve Labs analysis', error);
      const previousEntry = analysisCache.get(key);
      const timestamp = Date.now();
      if (previousEntry) {
        analysisCache.set(key, { ...previousEntry, checkedAt: timestamp });
      } else {
        analysisCache.set(key, {
          record: null,
          cached: false,
          hasAnalysis: false,
          checkedAt: timestamp,
        });
      }
      return null;
    } finally {
      analysisStatusRequests.delete(key);
    }
  })();

  analysisStatusRequests.set(key, loadPromise);
  return loadPromise;
}

async function preloadRecordingStatuses(recordings) {
  if (!analysisIntegrationAvailable || !analysisEndpoint) {
    return;
  }
  const items = Array.isArray(recordings)
    ? recordings.filter((item) => item && typeof item === 'object')
    : [];
  if (items.length === 0) {
    return;
  }
  await Promise.all(items.map((recording) => loadCachedAnalysis(recording)));
}

function updateAnalysisPanelForRecording(recording, options = {}) {
  const {
    triggerLoad = false,
    preserveExistingResult = false,
    suppressInitialStatus = false,
  } = options;
  if (!recording) {
    setAnalysisView(null, 'idle', defaultAnalysisMessage);
    return;
  }

  const displayName =
    typeof recording.displayName === 'string' && recording.displayName.trim().length > 0
      ? recording.displayName.trim()
      : 'this recording';

  const shouldPreserveResult =
    preserveExistingResult && analysisViewState.recording?.id === recording.id;
  const preservedResult = shouldPreserveResult ? analysisViewState.result : null;
  const preservedCached = shouldPreserveResult ? analysisViewState.cached : false;
  const preservedErrorCode = shouldPreserveResult ? analysisViewState.errorCode : null;

  const applyView = (status, message) => {
    const resolvedMessage = message || defaultAnalysisMessage;
    setAnalysisView(recording, status, resolvedMessage, preservedResult, preservedCached, preservedErrorCode);
  };

  if (!analysisIntegrationAvailable) {
    applyView(
      'disabled',
      'Configure the Twelve Labs integration on the server to enable analysis for stored recordings.'
    );
    return;
  }

  const cached = getCachedAnalysis(recording);
  if (cached?.record) {
    const cachedRecord = cached.record;
    const cachedResult = Boolean(cached.cached);
    const baseMessage = buildAnalysisCompleteMessage(cachedRecord, cachedResult);
    const combinedMessage = combineAnalysisAndEmbeddingMessages(
      cachedRecord,
      baseMessage,
      cachedResult
    );
    const statusInfo = getEffectiveEmbeddingStatus(recording, cachedRecord, recording);
    const defaultStatus = isEmbeddingPendingStatus(statusInfo)
      ? 'pending'
      : cachedResult
      ? 'cached'
      : 'ok';
    updateAnalysisViewWithRecord(
      recording,
      cachedRecord,
      cachedResult,
      defaultStatus,
      combinedMessage,
      {
        embeddingStatus: statusInfo,
      }
    );
    return;
  }

  const statusInfo = getEffectiveEmbeddingStatus(recording, cached?.record, recording);
  const sharedEmbeddingStatus = getSharedEmbeddingStatus(recording);
  const pendingStatusInfo = isEmbeddingPendingStatus(statusInfo)
    ? statusInfo
    : isWorkflowPendingStatus(sharedEmbeddingStatus)
    ? sharedEmbeddingStatus
    : null;
  const sharedAnalysisStatus = getSharedAnalysisStatus(recording);
  const embeddingReady =
    hasEmbeddingSuccess(recording) ||
    isEmbeddingReadyStatus(statusInfo) ||
    isEmbeddingReadyStatus(sharedEmbeddingStatus);

  const isCurrentRecording = analysisViewState.recording?.id === recording.id;
  const existingStatus = isCurrentRecording ? analysisViewState.status : null;
  const existingMessage = isCurrentRecording ? analysisViewState.message : null;

  const shouldMaintainActiveStatus =
    preserveExistingResult &&
    isCurrentRecording &&
    ACTIVE_ANALYSIS_STATUSES.has(existingStatus) &&
    !pendingStatusInfo &&
    !isWorkflowPendingStatus(sharedAnalysisStatus) &&
    !isEmbeddingErrorStatus(statusInfo) &&
    !embeddingReady &&
    !recordHasAnalysisContent(recording);

  if (shouldMaintainActiveStatus) {
    applyView(existingStatus, existingMessage || defaultAnalysisMessage);
    if (triggerLoad) {
      void loadCachedAnalysis(recording);
    }
    return;
  }

  const shouldSuppressDefaultReady =
    suppressInitialStatus &&
    !cached?.record &&
    !pendingStatusInfo &&
    !isWorkflowPendingStatus(sharedAnalysisStatus) &&
    !isEmbeddingErrorStatus(statusInfo) &&
    !embeddingReady &&
    !recordHasAnalysisContent(recording);

  if (shouldSuppressDefaultReady) {
    const fallbackStatus =
      isCurrentRecording && existingStatus ? existingStatus : 'idle';
    const fallbackMessage =
      isCurrentRecording && existingMessage
        ? existingMessage
        : defaultAnalysisMessage;
    setAnalysisView(
      recording,
      fallbackStatus,
      fallbackMessage,
      preservedResult,
      preservedCached,
      preservedErrorCode
    );
    if (triggerLoad) {
      void loadCachedAnalysis(recording);
    }
    return;
  }

  if (pendingStatusInfo) {
    const pendingMessage =
      pendingStatusInfo?.message ||
      statusInfo?.message ||
      sharedEmbeddingStatus?.message ||
      `Twelve Labs embeddings are still processing for “${displayName}”.`;
    applyView('pending', pendingMessage);
  } else if (isWorkflowPendingStatus(sharedAnalysisStatus)) {
    const analysisMessage =
      sharedAnalysisStatus?.message ||
      `Requesting Twelve Labs analysis for “${displayName}”…`;
    applyView('loading', analysisMessage);
  } else if (isEmbeddingErrorStatus(statusInfo)) {
    const errorMessage =
      statusInfo?.message ||
      `Twelve Labs embedding request for “${displayName}” failed.`;
    applyView('error', errorMessage);
  } else if (embeddingReady) {
    const readyMessage =
      statusInfo?.message ||
      sharedEmbeddingStatus?.message ||
      `Twelve Labs embeddings are available for “${displayName}”.`;
    applyView('ready', readyMessage);
  } else if (recordHasAnalysisContent(recording)) {
    applyView(
      'loading',
      `Loading stored Twelve Labs analysis for “${displayName}”…`
    );
  } else {
    applyView(
      'ready',
      `Press “Analyze” to request AI insights for “${displayName}” via Twelve Labs, or choose “Embeddings” to upload and retrieve vectors in one step.`
    );
  }

  if (triggerLoad) {
    void loadCachedAnalysis(recording);
  }
}

function syncActiveAnalysisView() {
  const currentRecording = analysisViewState.recording;
  if (!currentRecording) {
    return;
  }
  if (recordingsState.isLoading) {
    return;
  }
  const items = Array.isArray(recordingsState.items) ? recordingsState.items : [];
  if (items.length === 0) {
    setAnalysisView(null, 'idle', defaultAnalysisMessage);
    return;
  }
  const active = currentRecording.id
    ? items.find((item) => item.id === currentRecording.id)
    : null;
  if (!active) {
    setAnalysisView(null, 'idle', defaultAnalysisMessage);
    return;
  }
  updateAnalysisPanelForRecording(active, { preserveExistingResult: true });
}

function shouldPollAnalysisStatus(recording) {
  if (!recording) {
    return false;
  }

  if (ACTIVE_ANALYSIS_STATUSES.has(analysisViewState.status)) {
    return true;
  }

  const sharedAnalysisStatus = getSharedAnalysisStatus(recording);
  if (isWorkflowPendingStatus(sharedAnalysisStatus)) {
    return true;
  }

  const cached = getCachedAnalysis(recording);
  const effectiveStatus = getEffectiveEmbeddingStatus(
    recording,
    analysisViewState.result,
    cached?.record,
    recording
  );
  return isEmbeddingPendingStatus(effectiveStatus);
}

function refreshActiveAnalysisStatus(options = {}) {
  const { force = false } = options;
  if (!analysisIntegrationAvailable || !analysisEndpoint) {
    return;
  }
  if (recordingsState.isLoading) {
    return;
  }
  if (document?.body?.dataset?.activeTab && document.body.dataset.activeTab !== 'recordingsTab') {
    return;
  }
  const recording = analysisViewState.recording;
  if (!recording) {
    return;
  }
  if (activeAnalysisPromise) {
    return;
  }
  updateAnalysisPanelForRecording(recording, { preserveExistingResult: true });
  if (!force && !shouldPollAnalysisStatus(recording)) {
    return;
  }
  void loadCachedAnalysis(recording);
}

function setSelectedRecording(recording, options = {}) {
  const { triggerLoad = true, ...restOptions } = options;
  activeAnalysisPromise = null;
  if (!recording) {
    selectedRecordingId = null;
    setAnalysisView(null, 'idle', defaultAnalysisMessage);
    renderRecordingsList();
    return;
  }
  selectedRecordingId = recording.id;

  updateAnalysisPanelForRecording(recording, {
    triggerLoad,
    ...restOptions,
  });
  renderRecordingsList();
}

async function requestRecordingAnalysis(recording) {
  if (!recording) {
    return;
  }
  if (!analysisIntegrationAvailable) {
    setAnalysisView(
      recording,
      'disabled',
      'Twelve Labs analysis is not configured on the server.'
    );
    return;
  }

  const cached = getCachedAnalysis(recording);
  if (cached?.record && recordHasAnalysisContent(cached.record)) {
    const message = buildAnalysisCompleteMessage(cached.record, true);
    setAnalysisView(recording, 'cached', message, cached.record, true);
    return;
  }

  const pendingMessage = analysisPromptValue
    ? `Requesting Twelve Labs analysis for “${recording.displayName}” with a custom prompt…`
    : `Requesting Twelve Labs analysis for “${recording.displayName}”…`;
  const isCurrentSelection = analysisViewState.recording?.id === recording.id;
  const preservedResult = isCurrentSelection
    ? analysisViewState.result
    : cached?.record || null;
  const preservedCached = isCurrentSelection ? analysisViewState.cached : Boolean(cached?.cached);
  const preservedErrorCode = isCurrentSelection ? analysisViewState.errorCode : null;
  setAnalysisView(
    recording,
    'loading',
    pendingMessage,
    preservedResult,
    preservedCached,
    preservedErrorCode
  );
  updateWorkflowSyncStateForRecording(recording, {
    analysis: {
      state: 'pending',
      message: pendingMessage,
      updatedAt: new Date().toISOString(),
      __timestamp: Date.now(),
    },
  });

  try {
    activeAnalysisPromise = fetchAnalysis(recording, { start: true });
    const result = await activeAnalysisPromise;
    if (analysisViewState.recording?.id !== recording.id) {
      return;
    }
    if (!result.ok || !result.record) {
      if (isAnalysisIntegrationError(result)) {
        disableAnalysisIntegration(
          recording,
          result.error || 'Twelve Labs analysis is not configured on the server.',
          result.code || result.status
        );
        return;
      }
      const errorMessage = resolveIntegrationErrorMessage(
        result,
        'Failed to obtain a Twelve Labs analysis response.'
      );
      setAnalysisView(recording, 'error', errorMessage, null, false, result.code || result.status);
      return;
    }
    storeAnalysisRecord(recording, result.record, result.cached);
    const baseMessage =
      result.message || buildAnalysisCompleteMessage(result.record, result.cached);
    const combinedMessage = combineAnalysisAndEmbeddingMessages(
      result.record,
      baseMessage,
      result.cached
    );
    const defaultStatus = result.cached ? 'cached' : result.status || 'ok';
    updateAnalysisViewWithRecord(
      recording,
      result.record,
      result.cached,
      defaultStatus,
      combinedMessage,
      {
        embeddingStage: result.embeddingStage,
        embeddingStatus: result.embeddingStatus,
      }
    );
    renderRecordingsList();
  } catch (error) {
    if (analysisViewState.recording?.id !== recording.id) {
      return;
    }
    const fallback = error instanceof Error ? error.message : String(error);
    setAnalysisView(
      recording,
      'error',
      `Failed to request Twelve Labs analysis: ${fallback}`
    );
  } finally {
    if (analysisViewState.recording?.id === recording.id) {
      activeAnalysisPromise = null;
    }
    updateWorkflowSyncStateForRecording(recording, { analysis: null });
  }
}

async function recoverEmbeddingStateFromNetworkError(recording) {
  if (!recording) {
    return false;
  }
  try {
    const statusResult = await fetchAnalysis(recording, { start: false });
    if (analysisViewState.recording?.id !== recording.id) {
      return true;
    }
    if (statusResult.ok && statusResult.record) {
      const cachedStatus = Boolean(statusResult.cached);
      storeAnalysisRecord(recording, statusResult.record, cachedStatus);
      const baseMessage =
        statusResult.message ||
        buildAnalysisCompleteMessage(statusResult.record, cachedStatus);
      const combinedMessage = combineAnalysisAndEmbeddingMessages(
        statusResult.record,
        baseMessage,
        cachedStatus
      );
      const defaultStatus = cachedStatus
        ? 'cached'
        : statusResult.status || 'ok';
      updateAnalysisViewWithRecord(
        recording,
        statusResult.record,
        cachedStatus,
        defaultStatus,
        combinedMessage,
        {
          embeddingStage: statusResult.embeddingStage,
          embeddingStatus: statusResult.embeddingStatus,
        }
      );
      renderRecordingsList();
      return true;
    }
  } catch (error) {
    console.warn('Failed to refresh embedding status after network error', error);
  }
  return false;
}

async function requestRecordingEmbeddings(recording) {
  if (!recording) {
    return;
  }
  if (!analysisIntegrationAvailable) {
    setAnalysisView(
      recording,
      'disabled',
      'Twelve Labs embeddings are not configured on the server.'
    );
    return;
  }

  const cached = getCachedAnalysis(recording);
  const workingRecord = cached?.record || null;
  const { includeTranscription, embeddingOptions } = getEmbeddingRequestOptions();

  const pendingMessage = workingRecord
    ? `Requesting Twelve Labs embeddings for “${recording.displayName}”…`
    : `Uploading “${recording.displayName}” to Twelve Labs and retrieving embeddings…`;
  setAnalysisView(
    recording,
    'embedding',
    pendingMessage,
    workingRecord,
    Boolean(workingRecord)
  );

  const pendingStatusPayload = {
    embeddingStatus: {
      state: 'pending',
      message: pendingMessage,
      updatedAt: new Date().toISOString(),
    },
  };
  if (Array.isArray(embeddingOptions) && embeddingOptions.length > 0) {
    const pendingOptions = embeddingOptions
      .map((option) => (typeof option === 'string' ? option.trim() : ''))
      .filter((option, index, array) => option && array.indexOf(option) === index);
    if (pendingOptions.length > 0) {
      pendingStatusPayload.embeddingStatus.options = pendingOptions;
    }
  }
  pendingStatusPayload.embeddingStatus.includeTranscription = Boolean(includeTranscription);
  rememberEmbeddingState(recording, pendingStatusPayload);
  renderRecordingsList();

  try {
    activeAnalysisPromise = fetchAnalysis(recording, {
      embed: true,
      includeTranscription,
      embeddingOptions,
    });
    const result = await activeAnalysisPromise;
    if (analysisViewState.recording?.id !== recording.id) {
      return;
    }
    if (!result.ok || !result.record) {
      if (
        result.requestType === 'embed' &&
        (result.status === 'network_error' || result.code === 'network_error')
      ) {
        const recovered = await recoverEmbeddingStateFromNetworkError(recording);
        if (recovered) {
          return;
        }
      }
      if (isAnalysisIntegrationError(result)) {
        rememberEmbeddingState(recording, null);
        disableAnalysisIntegration(
          recording,
          result.error || 'Twelve Labs analysis is not configured on the server.',
          result.code || result.status
        );
        return;
      }
      const errorMessage = resolveIntegrationErrorMessage(
        result,
        'Failed to obtain Twelve Labs embeddings for this recording.'
      );
      setAnalysisView(
        recording,
        'error',
        errorMessage,
        workingRecord,
        Boolean(workingRecord),
        result.code || result.status
      );
      const errorStatus = {
        state: 'error',
        message: errorMessage,
        updatedAt: new Date().toISOString(),
        includeTranscription: Boolean(includeTranscription),
      };
      if (Array.isArray(embeddingOptions) && embeddingOptions.length > 0) {
        const optionList = embeddingOptions
          .map((option) => (typeof option === 'string' ? option.trim() : ''))
          .filter((option, index, array) => option && array.indexOf(option) === index);
        if (optionList.length > 0) {
          errorStatus.options = optionList;
        }
      }
      rememberEmbeddingState(recording, { embeddingStatus: errorStatus });
      renderRecordingsList();
      return;
    }

    const cachedResult = Boolean(result.cached);
    storeAnalysisRecord(recording, result.record, cachedResult);
    const baseMessage =
      result.message || buildAnalysisCompleteMessage(result.record, cachedResult);
    const combinedMessage = combineAnalysisAndEmbeddingMessages(
      result.record,
      baseMessage,
      cachedResult
    );
    const defaultStatus = cachedResult ? 'cached' : result.status || 'ok';
    updateAnalysisViewWithRecord(
      recording,
      result.record,
      cachedResult,
      defaultStatus,
      combinedMessage,
      {
        embeddingStage: result.embeddingStage,
        embeddingStatus: result.embeddingStatus,
      }
    );
    renderRecordingsList();
  } catch (error) {
    if (analysisViewState.recording?.id !== recording.id) {
      return;
    }
    const recovered = await recoverEmbeddingStateFromNetworkError(recording);
    if (recovered) {
      return;
    }
    const fallback = error instanceof Error ? error.message : String(error);
    setAnalysisView(
      recording,
      'error',
      `Failed to request Twelve Labs embeddings: ${fallback}`,
      workingRecord,
      Boolean(workingRecord)
    );
    const errorStatus = {
      state: 'error',
      message: `Failed to request Twelve Labs embeddings: ${fallback}`,
      updatedAt: new Date().toISOString(),
      includeTranscription: Boolean(includeTranscription),
    };
    if (Array.isArray(embeddingOptions) && embeddingOptions.length > 0) {
      const optionList = embeddingOptions
        .map((option) => (typeof option === 'string' ? option.trim() : ''))
        .filter((option, index, array) => option && array.indexOf(option) === index);
      if (optionList.length > 0) {
        errorStatus.options = optionList;
      }
    }
    rememberEmbeddingState(recording, { embeddingStatus: errorStatus });
    renderRecordingsList();
  } finally {
    if (analysisViewState.recording?.id === recording.id) {
      activeAnalysisPromise = null;
    }
  }
}

function updateRefreshButtonState(isLoading) {
  if (!refreshRecordingsButton) {
    return;
  }
  refreshRecordingsButton.disabled = Boolean(isLoading);
  refreshRecordingsButton.textContent = isLoading ? 'Refreshing…' : 'Refresh';
}

function adaptServerRecording(recording) {
  if (!recording || typeof recording !== 'object') {
    return null;
  }

  const adapted = { ...recording };

  if (!adapted.name && typeof recording.fileName === 'string') {
    adapted.name = recording.fileName;
  }

  if (!adapted.url && typeof recording.URL === 'string') {
    adapted.url = recording.URL;
  }

  if (!adapted.recordedAt && recording.modified) {
    adapted.recordedAt = recording.modified;
  }

  if (adapted.recordedAt === undefined && recording.Modified) {
    adapted.recordedAt = recording.Modified;
  }

  if (!adapted.sizeBytes && Number.isFinite(recording.size)) {
    adapted.sizeBytes = recording.size;
  }

  if (!adapted.sizeBytes && Number.isFinite(recording.Size)) {
    adapted.sizeBytes = recording.Size;
  }

  if (!adapted.id) {
    const streamPart =
      typeof recording.streamId === 'string' && recording.streamId.trim().length > 0
        ? recording.streamId.trim()
        : typeof recording.StreamID === 'string' && recording.StreamID.trim().length > 0
        ? recording.StreamID.trim()
        : '';
    const namePart =
      typeof adapted.name === 'string' && adapted.name.trim().length > 0
        ? adapted.name.trim()
        : typeof recording.fileName === 'string' && recording.fileName.trim().length > 0
        ? recording.fileName.trim()
        : typeof recording.FileName === 'string' && recording.FileName.trim().length > 0
        ? recording.FileName.trim()
        : '';
    if (streamPart && namePart) {
      adapted.id = `${streamPart}/${namePart}`;
    } else if (namePart) {
      adapted.id = namePart;
    } else if (streamPart) {
      adapted.id = streamPart;
    }
  }

  return adapted;
}

async function loadRecordingsFromServer() {
  if (!Array.isArray(recordingsEndpoints) || recordingsEndpoints.length === 0) {
    console.warn('Recordings endpoint is not configured.');
    return [];
  }

  const attempts = [];

  for (const endpoint of recordingsEndpoints) {
    try {
      const endpointUrl = new URL(endpoint.toString());
      if (hasExplicitStreamId) {
        endpointUrl.searchParams.set('streamId', rawStreamIdParam);
      } else {
        endpointUrl.searchParams.delete('streamId');
      }
      const response = await fetch(endpointUrl.toString(), { cache: 'no-store' });
      if (!response.ok) {
        throw new Error(`Unexpected status ${response.status}`);
      }
      const payload = await response.json();
      if (Array.isArray(payload)) {
        return payload.map(adaptServerRecording).filter((item) => item !== null);
      }
      if (payload && Array.isArray(payload.recordings)) {
        return payload.recordings
          .map(adaptServerRecording)
          .filter((item) => item !== null);
      }
      return [];
    } catch (error) {
      attempts.push({ endpoint, error });
    }
  }

  attempts.forEach(({ endpoint, error }) => {
    console.error('Failed to load recordings from endpoint', endpoint.toString(), error);
  });
  const fallbackError =
    attempts.length > 0 ? attempts[attempts.length - 1].error : new Error('No recordings endpoints');
  throw fallbackError;
}

async function refreshRecordingsList() {
  if (!recordingsListElement || recordingsState.isLoading) {
    return;
  }
  recordingsState.isLoading = true;
  updateRefreshButtonState(true);
  renderRecordingsList();
  try {
    const rawItems = await loadRecordingsFromServer();
    const normalized = Array.isArray(rawItems)
      ? rawItems.map(normaliseRecording).filter((item) => item !== null)
      : [];
    recordingsState.items = normalized;
    normalized.forEach((item) => {
      rememberEmbeddingState(item, item);
    });
    try {
      await preloadRecordingStatuses(normalized);
    } catch (error) {
      console.warn('Failed to preload analysis statuses for recordings', error);
    }
    recordingsState.lastUpdated = new Date();
    if (selectedRecordingId) {
      const selected = normalized.find((item) => item.id === selectedRecordingId);
      if (selected) {
        const cached = getCachedAnalysis(selected);
        if (!analysisIntegrationAvailable) {
          setAnalysisView(
            selected,
            'disabled',
            'Configure the Twelve Labs integration on the server to enable analysis for stored recordings.'
          );
        } else if (cached?.record) {
          const message = buildAnalysisCompleteMessage(cached.record, true);
          setAnalysisView(selected, 'cached', message, cached.record, true);
        } else {
          setAnalysisView(
            selected,
            'ready',
            `Press “Analyze” to request AI insights for “${selected.displayName}” via Twelve Labs.`
          );
          void loadCachedAnalysis(selected);
        }
      } else {
        selectedRecordingId = null;
        setAnalysisView(null, 'idle', defaultAnalysisMessage);
      }
    }
  } catch (error) {
    console.error('Failed to refresh recordings', error);
    recordingsState.items = [];
    recordingsState.lastUpdated = null;
  } finally {
    recordingsState.isLoading = false;
    updateRefreshButtonState(false);
    renderRecordingsList();
  }
}

function updateTabButtonState(button, isActive) {
  button.classList.toggle('active', isActive);
  button.setAttribute('aria-selected', String(isActive));
  button.tabIndex = isActive ? 0 : -1;
}

function setActiveTab(tabId) {
  const targetId = tabPanels.has(tabId) ? tabId : 'liveTab';
  tabButtons.forEach((button) => {
    const isActive = button.dataset.tabTarget === targetId;
    updateTabButtonState(button, isActive);
  });
  tabPanels.forEach((panel, id) => {
    const isActive = id === targetId;
    panel.classList.toggle('active', isActive);
    panel.hidden = !isActive;
    panel.setAttribute('aria-hidden', String(!isActive));
  });
  if (mainElement) {
    mainElement.dataset.activeTab = targetId;
  }
  document.body.dataset.activeTab = targetId;
  const showLiveControls = targetId === 'liveTab';
  if (toggleFlightControlsButton) {
    toggleFlightControlsButton.hidden = !showLiveControls;
    toggleFlightControlsButton.setAttribute('aria-hidden', String(!showLiveControls));
  }
  if (toggleRoutePanel) {
    toggleRoutePanel.hidden = !showLiveControls;
    toggleRoutePanel.setAttribute('aria-hidden', String(!showLiveControls));
  }
  if (!showLiveControls) {
    setFlightControlsVisibility(false);
    toggleRoutePanelVisibility(false);
  }
  if (
    targetId === 'recordingsTab' &&
    recordingsListElement &&
    !recordingsState.isLoading &&
    recordingsState.items.length === 0
  ) {
    refreshRecordingsList();
  }
}

function attachTabNavigation() {
  if (!tabButtons.length) {
    return;
  }
  tabButtons.forEach((button, index) => {
    button.addEventListener('click', () => {
      const targetId = button.dataset.tabTarget;
      if (targetId) {
        setActiveTab(targetId);
      }
    });
    button.addEventListener('keydown', (event) => {
      if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
        event.preventDefault();
        const direction = event.key === 'ArrowRight' ? 1 : -1;
        const nextIndex = (index + direction + tabButtons.length) % tabButtons.length;
        tabButtons[nextIndex].focus();
      }
    });
  });
  const defaultTab =
    tabButtons.find((button) => button.classList.contains('active'))?.dataset.tabTarget || 'liveTab';
  setActiveTab(defaultTab);
}

let latestDetections = null;
let lastNotificationSummary = '';
let detectionPermissionRequested = false;
let mapInstance = null;
let routePolyline = null;
let waypointMarkers = [];
let waypoints = [];
let rawVideoAvailable = false;
let overlayVideoAvailable = false;
const trackedVideoTracks = new WeakSet();
let latestTelemetryState = null;
let droneMarker = null;
let droneAccuracyCircle = null;
let droneTrailPolyline = null;
let droneTrail = [];
let telemetryStaleTimer = null;
let droneAutoCentered = false;
const DRONE_TRAIL_MAX_POINTS = 300;
const TELEMETRY_STALE_TIMEOUT_MS = 15000;
let gcsChannelReady = false;
const STREAM_RECONNECT_BASE_DELAY_MS = 2000;
const STREAM_RECONNECT_MAX_DELAY_MS = 20000;
let streamReconnectTimerId = null;
let streamReconnectAttempts = 0;
let pc = null;
let signalingSocket = null;
let analysisStatusRefreshTimerId = null;

markTelemetryWaiting();

function clearStreamReconnectTimer() {
  if (streamReconnectTimerId !== null) {
    window.clearTimeout(streamReconnectTimerId);
    streamReconnectTimerId = null;
  }
}

function resetStreamReconnectBackoff() {
  streamReconnectAttempts = 0;
  clearStreamReconnectTimer();
}

function scheduleStreamReconnect(reason) {
  if (streamReconnectTimerId !== null) {
    return;
  }

  streamReconnectAttempts += 1;
  const delay = Math.min(
    STREAM_RECONNECT_BASE_DELAY_MS * 2 ** Math.max(streamReconnectAttempts - 1, 0),
    STREAM_RECONNECT_MAX_DELAY_MS,
  );
  console.warn(`Scheduling stream reconnect in ${delay}ms due to: ${reason}`);
  connectionStatus.textContent = 'reconnecting';
  markTelemetryStale();
  streamReconnectTimerId = window.setTimeout(() => {
    streamReconnectTimerId = null;
    establishStreamConnection();
  }, delay);
}

function cleanupPeerConnection() {
  if (!pc) {
    return;
  }
  pc.removeEventListener('track', handlePeerTrack);
  pc.removeEventListener('connectionstatechange', handlePeerConnectionStateChange);
  pc.removeEventListener('icecandidate', handlePeerIceCandidate);
  try {
    pc.close();
  } catch (error) {
    console.warn('Failed to close peer connection cleanly', error);
  }
  pc = null;
}

function cleanupSignalingSocket() {
  if (!signalingSocket) {
    return;
  }
  signalingSocket.removeEventListener('open', handleSignalingOpen);
  signalingSocket.removeEventListener('close', handleSignalingClose);
  signalingSocket.removeEventListener('error', handleSignalingError);
  signalingSocket.removeEventListener('message', handleSignalingMessage);
  if (gcsControlEnabled && gcsChannelReady) {
    gcsChannelReady = false;
    updateGcsStatus('GCS: disconnected');
  }
  try {
    if (signalingSocket.readyState === WebSocket.OPEN || signalingSocket.readyState === WebSocket.CONNECTING) {
      signalingSocket.close();
    }
  } catch (error) {
    console.warn('Failed to close signaling socket cleanly', error);
  }
  signalingSocket = null;
}

function sendSignalingMessage(payload) {
  if (signalingSocket && signalingSocket.readyState === WebSocket.OPEN) {
    signalingSocket.send(JSON.stringify(payload));
  } else {
    console.warn('Signaling socket not open, dropping message', payload);
  }
}

function handlePeerTrack(event) {
  const [stream] = event.streams;
  if (rawVideo.srcObject !== stream) {
    rawVideo.srcObject = stream;
  }
  if (overlayVideo.srcObject !== stream) {
    overlayVideo.srcObject = stream;
  }
  updateVideoAvailabilityFromStream(stream);
  stream.getVideoTracks().forEach((track) => {
    if (trackedVideoTracks.has(track)) {
      return;
    }
    trackedVideoTracks.add(track);
    track.addEventListener('ended', () => updateVideoAvailabilityFromStream(stream));
    track.addEventListener('mute', () => updateVideoAvailabilityFromStream(stream));
    track.addEventListener('unmute', () => updateVideoAvailabilityFromStream(stream));
  });
  connectionStatus.textContent = 'media-connected';
}

function handlePeerConnectionStateChange() {
  if (!pc) {
    return;
  }
  const state = pc.connectionState;
  connectionStatus.textContent = state;
  if (state === 'connected') {
    resetStreamReconnectBackoff();
    return;
  }
  if (['disconnected', 'failed', 'closed'].includes(state)) {
    const hadMedia = rawVideoAvailable || overlayVideoAvailable;
    setRawVideoAvailability(false);
    setOverlayVideoAvailability(false);
    rawVideo.srcObject = null;
    overlayVideo.srcObject = null;
    if (hadMedia || state !== 'closed') {
      scheduleStreamReconnect(`peer connection state: ${state}`);
    }
  }
}

function handlePeerIceCandidate(event) {
  const { candidate } = event;
  if (!candidate) {
    return;
  }

  const payload = {
    type: 'ice',
    candidate: candidate.candidate,
  };

  if (typeof candidate.sdpMid === 'string' && candidate.sdpMid.length > 0) {
    payload.sdpMid = candidate.sdpMid;
  }

  if (typeof candidate.sdpMLineIndex === 'number' && Number.isFinite(candidate.sdpMLineIndex)) {
    payload.sdpMLineIndex = candidate.sdpMLineIndex;
  }

  sendSignalingMessage(payload);
}

function handleSignalingOpen() {
  connectionStatus.textContent = 'signaling';
  resetStreamReconnectBackoff();
  if (gcsControlEnabled) {
    gcsChannelReady = true;
    updateGcsStatus('GCS: connected');
  }
}

function handleSignalingClose() {
  connectionStatus.textContent = 'disconnected';
  if (gcsControlEnabled) {
    gcsChannelReady = false;
    updateGcsStatus('GCS: disconnected');
  }
  scheduleStreamReconnect('signaling socket closed');
}

function handleSignalingError(event) {
  console.error('Signaling socket error', event);
  scheduleStreamReconnect('signaling socket error');
}

async function handleSignalingMessage(event) {
  try {
    const message = JSON.parse(event.data);
    if (message.error) {
      console.error('Signaling error', message);
      const details = [message.code, message.error]
        .map((part) => (typeof part === 'string' ? part.trim() : ''))
        .filter(Boolean)
        .join(' ');
      connectionStatus.textContent = details ? `error: ${details}` : 'error';
      return;
    }

    if (message.type === 'gcs_command_ack') {
      handleGcsCommandAck(message);
      return;
    }

    if (message.type === 'sdp') {
      if (!pc) {
        console.warn('Peer connection unavailable for SDP message');
        return;
      }
      const description = new RTCSessionDescription({
        type: message.sdpType || 'offer',
        sdp: message.sdp,
      });
      await pc.setRemoteDescription(description);
      if (description.type === 'offer') {
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        sendSignalingMessage({ type: 'sdp', sdpType: answer.type, sdp: answer.sdp });
      }
    } else if (message.type === 'ice') {
      if (!pc) {
        console.warn('Peer connection unavailable for ICE message');
        return;
      }
      const candidate = new RTCIceCandidate({
        candidate: message.candidate,
        sdpMid: message.sdpMid || undefined,
        sdpMLineIndex: message.sdpMLineIndex ?? undefined,
      });
      await pc.addIceCandidate(candidate);
    } else if (message.type === 'telemetry') {
      handleTelemetryMessage(message);
    }
  } catch (error) {
    console.error('Failed to process signaling message', error, event.data);
    if (typeof event.data === 'string') {
      const trimmed = event.data.trim();
      if (trimmed.length > 0) {
        connectionStatus.textContent = `error: ${trimmed}`;
        return;
      }
    }
    connectionStatus.textContent = 'error: invalid signaling message';
  }
}

function establishStreamConnection() {
  cleanupPeerConnection();
  cleanupSignalingSocket();

  if (connectionStatus) {
    connectionStatus.textContent = streamReconnectAttempts > 0 ? 'reconnecting' : 'connecting';
  }

  if (rawVideo.srcObject) {
    rawVideo.srcObject = null;
  }
  if (overlayVideo.srcObject) {
    overlayVideo.srcObject = null;
  }
  setRawVideoAvailability(false);
  setOverlayVideoAvailability(false);

  pc = new RTCPeerConnection({
    iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
  });
  pc.addEventListener('track', handlePeerTrack);
  pc.addEventListener('connectionstatechange', handlePeerConnectionStateChange);
  pc.addEventListener('icecandidate', handlePeerIceCandidate);

  signalingSocket = new WebSocket(signalingUrl);
  signalingSocket.addEventListener('open', handleSignalingOpen);
  signalingSocket.addEventListener('close', handleSignalingClose);
  signalingSocket.addEventListener('error', handleSignalingError);
  signalingSocket.addEventListener('message', handleSignalingMessage);
}

establishStreamConnection();

const detectionSocket = new WebSocket(detectionUrl);

detectionSocket.addEventListener('message', (event) => {
    try {
      const message = JSON.parse(event.data);
      latestDetections = message;
      detectionStatus.textContent = message.detections?.length ?? 0;
      if (message.timestamp) {
        const date = new Date(message.timestamp);
        detectionTimestamp.textContent = date.toLocaleTimeString();
      }
      maybeNotifyDetections(message);
    } catch (error) {
      console.error('Invalid detection payload', error);
    }
});

detectionSocket.addEventListener('close', () => {
  detectionTimestamp.textContent = 'connection lost';
});

rawVideo.addEventListener('loadeddata', () => setRawVideoAvailability(true));
rawVideo.addEventListener('emptied', () => setRawVideoAvailability(false));
overlayVideo.addEventListener('loadeddata', () => setOverlayVideoAvailability(true));
overlayVideo.addEventListener('emptied', () => setOverlayVideoAvailability(false));

function renderOverlay() {
  requestAnimationFrame(renderOverlay);
  const video = overlayVideo;
  if (!video || video.readyState < HTMLMediaElement.HAVE_METADATA) {
    return;
  }

  if (
    overlayCanvas.width !== video.videoWidth ||
    overlayCanvas.height !== video.videoHeight
  ) {
    overlayCanvas.width = video.videoWidth;
    overlayCanvas.height = video.videoHeight;
  }

  overlayCtx.clearRect(0, 0, overlayCanvas.width, overlayCanvas.height);
  if (!latestDetections || !Array.isArray(latestDetections.detections)) {
    return;
  }

  overlayCtx.lineWidth = 2;
  overlayCtx.textBaseline = 'top';
  overlayCtx.font = '16px "Segoe UI", sans-serif';

  latestDetections.detections.forEach((det) => {
    if (!Array.isArray(det.bbox) || det.bbox.length < 4) {
      return;
    }

    const [rawX, rawY, rawWidth, rawHeight] = det.bbox.map((value) => Number(value));
    if (![rawX, rawY, rawWidth, rawHeight].every(Number.isFinite)) {
      return;
    }

    const width = Math.max(0, rawWidth);
    const height = Math.max(0, rawHeight);
    if (width === 0 || height === 0) {
      return;
    }

    const clampedWidth = Math.min(width, overlayCanvas.width);
    const clampedHeight = Math.min(height, overlayCanvas.height);
    const x = clampToRange(rawX, 0, overlayCanvas.width - clampedWidth);
    const y = clampToRange(rawY, 0, overlayCanvas.height - clampedHeight);

    overlayCtx.strokeStyle = 'rgba(56, 189, 248, 0.95)';
    overlayCtx.fillStyle = 'rgba(15, 23, 42, 0.7)';
    overlayCtx.strokeRect(x, y, clampedWidth, clampedHeight);

    const confidenceValue = Number(det.confidence);
    const confidence = Number.isFinite(confidenceValue) ? confidenceValue : 0;
    const normalizedConfidence = clampToRange(confidence, 0, 1);
    const label = `${det.class ?? 'object'} ${(normalizedConfidence * 100).toFixed(1)}%`;
    const metrics = overlayCtx.measureText(label);
    const padding = 6;
    const labelWidth = metrics.width + padding;
    const labelX = clampToRange(x, 0, Math.max(0, overlayCanvas.width - labelWidth));
    const labelY = clampToRange(y - 24, 0, Math.max(0, overlayCanvas.height - 24));
    overlayCtx.fillRect(labelX, labelY, labelWidth, 24);
    overlayCtx.fillStyle = '#e0f2fe';
    overlayCtx.fillText(label, labelX + 3, labelY + 2);
  });
}

function clampToRange(value, min, max) {
  if (!Number.isFinite(value)) {
    return min;
  }
  if (max < min) {
    return min;
  }
  return Math.min(Math.max(value, min), max);
}

renderOverlay();
startVideoDelayMonitor(
  rawVideo,
  rawVideoStatus,
  rawVideoMessage,
  rawVideoDelay,
  () => rawVideoAvailable,
  'Camera stream',
);
startVideoDelayMonitor(
  overlayVideo,
  overlayVideoStatus,
  overlayVideoMessage,
  overlayVideoDelay,
  () => overlayVideoAvailable,
  'Overlay stream',
);

function streamHasLiveVideoTrack(stream) {
  if (!stream || typeof stream.getVideoTracks !== 'function') {
    return false;
  }
  if (typeof MediaStream !== 'undefined' && !(stream instanceof MediaStream)) {
    return false;
  }
  return stream.getVideoTracks().some((track) => track.readyState === 'live' && !track.muted);
}

function updateVideoAvailabilityFromStream(stream) {
  const wasAvailable = rawVideoAvailable || overlayVideoAvailable;
  const available = streamHasLiveVideoTrack(stream);
  setRawVideoAvailability(available);
  setOverlayVideoAvailability(available);
  if (available) {
    resetStreamReconnectBackoff();
  } else if (wasAvailable) {
    scheduleStreamReconnect('media tracks ended');
  }
}

function setRawVideoAvailability(isAvailable) {
  if (rawVideoAvailable === isAvailable) {
    return;
  }
  rawVideoAvailable = isAvailable;
  if (!rawVideoStatus || !rawVideoMessage) {
    return;
  }
  if (isAvailable) {
    setVideoStatus(rawVideoStatus, rawVideoMessage, rawVideoDelay, 'live', 'Live stream detected', '—');
  } else {
    setVideoStatus(
      rawVideoStatus,
      rawVideoMessage,
      rawVideoDelay,
      'offline',
      `No video signal · ${formatCurrentTime()}`,
      '—',
    );
  }
}

function setOverlayVideoAvailability(isAvailable) {
  if (overlayVideoAvailable === isAvailable) {
    return;
  }
  overlayVideoAvailable = isAvailable;
  if (!overlayVideoStatus || !overlayVideoMessage) {
    return;
  }
  if (isAvailable) {
    setVideoStatus(
      overlayVideoStatus,
      overlayVideoMessage,
      overlayVideoDelay,
      'live',
      'Overlay stream detected',
      '—',
    );
  } else {
    setVideoStatus(
      overlayVideoStatus,
      overlayVideoMessage,
      overlayVideoDelay,
      'offline',
      `No overlay signal · ${formatCurrentTime()}`,
      '—',
    );
  }
}

function setVideoStatus(container, messageElement, delayElement, state, message, delayText) {
  if (!container || !messageElement) {
    return;
  }
  container.dataset.state = state;
  messageElement.textContent = message;
  if (delayElement) {
    delayElement.textContent = typeof delayText === 'string' ? delayText : delayElement.textContent;
  }
}

function formatCurrentTime() {
  return new Date().toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function formatDelay(delayMs) {
  if (!Number.isFinite(delayMs)) {
    return 'delay: n/a';
  }
  if (delayMs >= 1000) {
    return `delay ≈ ${(delayMs / 1000).toFixed(1)} s`;
  }
  return `delay ≈ ${Math.round(delayMs)} ms`;
}

function estimateDetectionDelay() {
  if (!latestDetections || !latestDetections.timestamp) {
    return null;
  }
  const detectionTime = new Date(latestDetections.timestamp).getTime();
  if (!Number.isFinite(detectionTime)) {
    return null;
  }
  return Math.max(0, Date.now() - detectionTime);
}

function startVideoDelayMonitor(videoElement, container, messageElement, delayElement, availabilityGetter, label) {
  if (!videoElement || !container || !messageElement || !availabilityGetter) {
    return;
  }

  const supportsFrameCallback = typeof videoElement.requestVideoFrameCallback === 'function';
  let lastUpdate = 0;
  let lastState = container.dataset.state || 'idle';
  let lastMessageText = messageElement.textContent || '';
  let lastDelayText = delayElement ? delayElement.textContent || '' : '';

  const updateDelay = (metadata) => {
    if (!availabilityGetter()) {
      const messageText = `${label} unavailable · ${formatCurrentTime()}`;
      if (lastState !== 'offline' || lastMessageText !== messageText || lastDelayText !== '—') {
        setVideoStatus(container, messageElement, delayElement, 'offline', messageText, '—');
        lastMessageText = messageText;
        lastDelayText = '—';
        lastState = 'offline';
      }
      return;
    }

    const now = performance.now();
    if (now - lastUpdate < 1000) {
      return;
    }
    lastUpdate = now;

    let delayMs = null;
    if (metadata && typeof metadata.captureTime === 'number' && Number.isFinite(metadata.captureTime)) {
      delayMs = Date.now() - (performance.timeOrigin + metadata.captureTime);
    } else if (metadata && typeof metadata.presentationTime === 'number' && Number.isFinite(metadata.presentationTime)) {
      delayMs = Date.now() - (performance.timeOrigin + metadata.presentationTime);
    }

    if (!Number.isFinite(delayMs) || delayMs < 0) {
      delayMs = estimateDetectionDelay();
    }

    const safeDelay = Number.isFinite(delayMs) && delayMs >= 0 ? delayMs : null;
    const messageText = `${label} active · ${formatCurrentTime()}`;
    const delayText = safeDelay !== null ? formatDelay(safeDelay) : 'delay: n/a';
    if (lastState !== 'live' || lastMessageText !== messageText || lastDelayText !== delayText) {
      setVideoStatus(container, messageElement, delayElement, 'live', messageText, delayText);
      lastMessageText = messageText;
      lastDelayText = delayText;
      lastState = 'live';
    }
  };

  if (supportsFrameCallback) {
    const frameHandler = (_now, metadata) => {
      updateDelay(metadata);
      videoElement.requestVideoFrameCallback(frameHandler);
    };
    videoElement.requestVideoFrameCallback(frameHandler);
  } else {
    setInterval(() => {
      updateDelay(null);
    }, 600);
  }
}

async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) {
    console.warn('Service workers not supported in this browser');
    return;
  }
  try {
    const registration = await navigator.serviceWorker.register('./service-worker.js', { scope: './' });
    console.log('Service worker registered', registration);
  } catch (error) {
    console.error('Failed to register service worker', error);
  }
}

function maybeNotifyDetections(message) {
  if (!('serviceWorker' in navigator) || typeof Notification === 'undefined' || !message) {
    return;
  }

  const detections = Array.isArray(message.detections) ? message.detections : [];
  const detectionCount = detections.length;
  if (detectionCount === 0) {
    return;
  }

  const labels = detections
    .slice(0, 3)
    .map((det) => det.class || 'object')
    .join(', ');
  const summary = `${detectionCount}:${labels}:${message.timestamp || ''}`;
  if (summary === lastNotificationSummary) {
    return;
  }

  if (Notification.permission === 'granted') {
    navigator.serviceWorker.ready
      .then((registration) => {
        registration.active?.postMessage({
          type: 'detection',
          count: detectionCount,
          labels,
          timestamp: message.timestamp || Date.now(),
        });
      })
      .catch((error) => console.error('Failed to notify service worker', error));
    lastNotificationSummary = summary;
  } else if (Notification.permission !== 'denied' && !detectionPermissionRequested) {
    detectionPermissionRequested = true;
    Notification.requestPermission().then((permission) => {
      detectionPermissionRequested = false;
      if (permission === 'granted') {
        maybeNotifyDetections(message);
      }
    });
  }
}

function handleTelemetryMessage(message) {
  const latitude = parseFiniteNumber(message.latitude);
  const longitude = parseFiniteNumber(message.longitude);
  if (latitude === null || longitude === null) {
    return;
  }

  const altitudeValue = parseFiniteNumber(message.altitude);
  const accuracyCandidate = parseFiniteNumber(message.accuracy);
  const accuracyValue = accuracyCandidate !== null && accuracyCandidate >= 0 ? accuracyCandidate : null;
  const timestampCandidate = parseFiniteNumber(message.timestamp);
  const timestamp = timestampCandidate !== null && timestampCandidate > 0 ? timestampCandidate : Date.now();

  latestTelemetryState = {
    lat: latitude,
    lng: longitude,
    altitude: altitudeValue,
    accuracy: accuracyValue,
    timestamp,
    source: typeof message.source === 'string' ? message.source : '',
  };
  droneTrail.push({ lat: latitude, lng: longitude });
  if (droneTrail.length > DRONE_TRAIL_MAX_POINTS) {
    droneTrail.splice(0, droneTrail.length - DRONE_TRAIL_MAX_POINTS);
  }
  updateDroneLocationUi(latestTelemetryState);
  applyTelemetryToMap(latestTelemetryState);
  scheduleTelemetryStaleCheck();
}

function updateDroneLocationUi(telemetry) {
  if (!droneLocationContainer) {
    return;
  }
  droneLocationContainer.dataset.state = 'live';
  if (droneLocationCoords) {
    droneLocationCoords.textContent = `${telemetry.lat.toFixed(6)}, ${telemetry.lng.toFixed(6)}`;
  }
  if (droneLocationMeta) {
    droneLocationMeta.textContent = formatTelemetryMeta(telemetry);
  }
}

function formatTelemetryMeta(telemetry) {
  const parts = [];
  const time = new Date(telemetry.timestamp);
  if (!Number.isNaN(time.getTime())) {
    parts.push(`Updated ${time.toLocaleTimeString()}`);
  }
  if (telemetry.accuracy !== null) {
    parts.push(`±${telemetry.accuracy.toFixed(1)} m`);
  }
  if (telemetry.altitude !== null) {
    parts.push(`ALT ${telemetry.altitude.toFixed(1)} m`);
  }
  return parts.join(' · ');
}

function applyTelemetryToMap(telemetry) {
  if (!mapInstance || !window.L) {
    return;
  }
  const latlng = window.L.latLng(telemetry.lat, telemetry.lng);
  if (!droneMarker) {
    const icon = window.L.divIcon({
      className: 'drone-location-marker',
      html: '<span class="pulse"></span><span class="dot"></span>',
      iconSize: [24, 24],
      iconAnchor: [12, 12],
    });
    droneMarker = window.L.marker(latlng, { icon });
    droneMarker.addTo(mapInstance);
  } else {
    droneMarker.setLatLng(latlng);
  }

  if (telemetry.accuracy !== null) {
    const radius = Math.max(telemetry.accuracy, 1);
    if (!droneAccuracyCircle) {
      droneAccuracyCircle = window.L.circle(latlng, {
        radius,
        color: '#38bdf8',
        weight: 1,
        opacity: 0.35,
        fillColor: '#38bdf8',
        fillOpacity: 0.15,
      });
      droneAccuracyCircle.addTo(mapInstance);
    } else {
      droneAccuracyCircle.setLatLng(latlng);
      droneAccuracyCircle.setRadius(radius);
    }
  } else if (droneAccuracyCircle) {
    mapInstance.removeLayer(droneAccuracyCircle);
    droneAccuracyCircle = null;
  }

  const trailLatLngs = droneTrail.map((point) => [point.lat, point.lng]);
  if (trailLatLngs.length > 0) {
    if (!droneTrailPolyline) {
      droneTrailPolyline = window.L.polyline(trailLatLngs, {
        color: '#22d3ee',
        weight: 2,
        opacity: 0.6,
        dashArray: '6 8',
      });
      droneTrailPolyline.addTo(mapInstance);
    } else {
      droneTrailPolyline.setLatLngs(trailLatLngs);
    }
  }

  if (!droneAutoCentered) {
    droneAutoCentered = true;
    mapInstance.setView(latlng, Math.max(mapInstance.getZoom(), 15));
  }
}

function scheduleTelemetryStaleCheck() {
  if (telemetryStaleTimer) {
    clearTimeout(telemetryStaleTimer);
  }
  telemetryStaleTimer = setTimeout(() => {
    telemetryStaleTimer = null;
    markTelemetryStale();
  }, TELEMETRY_STALE_TIMEOUT_MS);
}

function markTelemetryWaiting() {
  if (telemetryStaleTimer) {
    clearTimeout(telemetryStaleTimer);
    telemetryStaleTimer = null;
  }
  if (!droneLocationContainer) {
    return;
  }
  droneLocationContainer.dataset.state = 'waiting';
  if (droneLocationCoords) {
    droneLocationCoords.textContent = '—';
  }
  if (droneLocationMeta) {
    droneLocationMeta.textContent = 'Awaiting telemetry…';
  }
}

function markTelemetryStale() {
  if (!droneLocationContainer) {
    return;
  }
  if (!latestTelemetryState) {
    markTelemetryWaiting();
    return;
  }
  droneLocationContainer.dataset.state = 'stale';
  if (droneLocationCoords) {
    droneLocationCoords.textContent = `${latestTelemetryState.lat.toFixed(6)}, ${latestTelemetryState.lng.toFixed(6)}`;
  }
  if (droneLocationMeta) {
    const lastTime = new Date(latestTelemetryState.timestamp);
    droneLocationMeta.textContent = `Last update ${lastTime.toLocaleTimeString()}`;
  }
}

function parseFiniteNumber(value) {
  if (value === null || value === undefined) {
    return null;
  }
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function setFlightControlsVisibility(shouldShow) {
  if (!flightControlsPanel) {
    return;
  }

  flightControlsPanel.hidden = !shouldShow;
  flightControlsPanel.setAttribute('aria-hidden', String(!shouldShow));
  flightControlsPanel.style.display = shouldShow ? '' : 'none';

  if (toggleFlightControlsButton) {
    toggleFlightControlsButton.setAttribute('aria-expanded', String(shouldShow));
    toggleFlightControlsButton.textContent = shouldShow ? 'Hide Flight Controls' : 'Flight Controls';
  }
}

function initFlightControlsToggle() {
  if (!flightControlsPanel || !toggleFlightControlsButton) {
    return;
  }

  const focusFirstControl = () => {
    const focusTarget =
      takeoffButton ||
      flightControlsPanel.querySelector(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
    focusTarget?.focus?.();
  };

  const closePanel = () => {
    setFlightControlsVisibility(false);
    toggleFlightControlsButton.focus();
  };

  toggleFlightControlsButton.addEventListener('click', () => {
    const shouldOpen = flightControlsPanel.hidden;
    setFlightControlsVisibility(shouldOpen);
    if (shouldOpen) {
      requestAnimationFrame(() => focusFirstControl());
    } else {
      toggleFlightControlsButton.focus();
    }
  });

  closeFlightControlsButton?.addEventListener('click', () => {
    closePanel();
  });

  flightControlsPanel.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !flightControlsPanel.hidden) {
      event.preventDefault();
      closePanel();
    }
  });

  setFlightControlsVisibility(false);
}

function initRoutePlanner() {
  if (!routePanel || !routeMapElement || !window.L) {
    return;
  }

  mapInstance = L.map(routeMapElement, {
    zoomControl: true,
    attributionControl: true,
  }).setView([37.5665, 126.978], 13);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(mapInstance);

  routePolyline = L.polyline([], {
    color: '#38bdf8',
    weight: 3,
    opacity: 0.9,
  }).addTo(mapInstance);

  mapInstance.on('click', (event) => {
    addWaypoint(event.latlng);
  });

  toggleRoutePanel?.addEventListener('click', () => toggleRoutePanelVisibility());
  closeRoutePanel?.addEventListener('click', () => toggleRoutePanelVisibility(false));
  undoWaypointButton?.addEventListener('click', removeLastWaypoint);
  clearRouteButton?.addEventListener('click', clearRoute);
  startRouteButton?.addEventListener('click', transmitRouteToGcs);

  updateRouteSummary();
  if (latestTelemetryState) {
    updateDroneLocationUi(latestTelemetryState);
    applyTelemetryToMap(latestTelemetryState);
  }
}

function toggleRoutePanelVisibility(forceState) {
  if (!routePanel || !toggleRoutePanel) {
    return;
  }
  const shouldOpen = typeof forceState === 'boolean' ? forceState : !routePanel.classList.contains('open');
  routePanel.classList.toggle('open', shouldOpen);
  routePanel.setAttribute('aria-hidden', String(!shouldOpen));
  toggleRoutePanel.setAttribute('aria-expanded', String(shouldOpen));
  if (shouldOpen) {
    setTimeout(() => {
      mapInstance?.invalidateSize();
    }, 250);
  }
}

function addWaypoint(latlng) {
  if (!mapInstance || !routePolyline) {
    return;
  }
  const waypoint = { lat: latlng.lat, lng: latlng.lng };
  const marker = L.marker(latlng, { draggable: true, autoPan: true, riseOnHover: true });
  marker.on('dragend', () => {
    const position = marker.getLatLng();
    waypoint.lat = position.lat;
    waypoint.lng = position.lng;
    syncRouteGeometry();
  });
  marker.bindTooltip(`#${waypoints.length + 1}`, { permanent: true, direction: 'top', offset: [0, -10] });
  marker.addTo(mapInstance);
  waypointMarkers.push(marker);
  waypoints.push(waypoint);
  syncRouteGeometry();
}

function removeLastWaypoint() {
  const marker = waypointMarkers.pop();
  if (marker && mapInstance) {
    mapInstance.removeLayer(marker);
  }
  waypoints.pop();
  syncRouteGeometry();
}

function clearRoute() {
  waypointMarkers.forEach((marker) => mapInstance?.removeLayer(marker));
  waypointMarkers = [];
  waypoints = [];
  syncRouteGeometry();
}

function syncRouteGeometry() {
  if (routePolyline) {
    routePolyline.setLatLngs(waypoints.map((point) => [point.lat, point.lng]));
  }
  waypointMarkers.forEach((marker, index) => {
    marker.setTooltipContent(`#${index + 1}`);
  });
  updateRouteSummary();
}

function updateRouteSummary() {
  if (waypointCountLabel) {
    waypointCountLabel.textContent = String(waypoints.length);
  }
  if (routeDistanceLabel) {
    const distanceMeters = calculateRouteDistance(waypoints);
    if (distanceMeters >= 1000) {
      routeDistanceLabel.textContent = `${(distanceMeters / 1000).toFixed(2)} km`;
    } else {
      routeDistanceLabel.textContent = `${distanceMeters.toFixed(1)} m`;
    }
  }
}

function calculateRouteDistance(points) {
  if (!Array.isArray(points) || points.length < 2) {
    return 0;
  }
  let distance = 0;
  for (let i = 1; i < points.length; i += 1) {
    distance += haversineDistance(points[i - 1], points[i]);
  }
  return distance;
}

function haversineDistance(a, b) {
  if (!a || !b) {
    return 0;
  }
  const toRad = (value) => (value * Math.PI) / 180;
  const earthRadius = 6371000;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);

  const h =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
  const c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
  return earthRadius * c;
}

function updateGcsStatus(message) {
  if (!Array.isArray(gcsStatusElements) || gcsStatusElements.length === 0) {
    return;
  }
  gcsStatusElements.forEach((element) => {
    if (element) {
      element.textContent = message;
    }
  });
}

function ensureGcsReady() {
  if (!gcsControlEnabled) {
    updateGcsStatus('GCS: disabled');
    return false;
  }
  if (!gcsChannelReady) {
    updateGcsStatus('GCS: relay unavailable');
    return false;
  }
  return true;
}

function sendGcsCommand(action, payloadExtras = {}, statusMessage) {
  if (!ensureGcsReady()) {
    return false;
  }
  const payload = {
    action,
    streamId,
    createdAt: Date.now(),
    ...payloadExtras,
  };
  sendSignalingMessage({ type: 'gcs_command', payload });
  if (typeof statusMessage === 'string' && statusMessage.trim().length > 0) {
    updateGcsStatus(statusMessage);
  }
  return true;
}

function initGcsControls() {
  const registerSimpleCommand = (element, action, statusText) => {
    if (!element) {
      return;
    }
    element.addEventListener('click', () => {
      sendGcsCommand(action, {}, statusText);
    });
  };

  registerSimpleCommand(takeoffButton, 'takeoff', 'GCS: requesting takeoff…');
  registerSimpleCommand(landButton, 'land', 'GCS: requesting landing…');
  registerSimpleCommand(returnHomeButton, 'return_home', 'GCS: requesting return-to-home…');
  registerSimpleCommand(cancelReturnButton, 'cancel_return_home', 'GCS: cancelling return-to-home…');

  virtualStickForm?.addEventListener('submit', (event) => {
    event.preventDefault();
    const formData = new FormData(virtualStickForm);
    const payload = {
      pitch: parseFiniteNumber(formData.get('pitch')) ?? 0,
      roll: parseFiniteNumber(formData.get('roll')) ?? 0,
      yaw: parseFiniteNumber(formData.get('yaw')) ?? 0,
      throttle: parseFiniteNumber(formData.get('throttle')) ?? 0,
    };
    sendGcsCommand('virtual_stick', payload, 'GCS: sending virtual stick command…');
  });

  gimbalForm?.addEventListener('submit', (event) => {
    event.preventDefault();
    const formData = new FormData(gimbalForm);
    const pitch = parseFiniteNumber(formData.get('pitch')) ?? 0;
    const roll = parseFiniteNumber(formData.get('roll')) ?? 0;
    const yaw = parseFiniteNumber(formData.get('yaw')) ?? 0;
    const durationRaw = parseFiniteNumber(formData.get('duration')) ?? 2;
    const duration = Math.min(30, Math.max(1, Math.round(durationRaw)));
    const payload = { pitch, roll, yaw, duration };
    sendGcsCommand('gimbal_rotate', payload, 'GCS: rotating gimbal…');
  });
}

function initGcsControlChannel() {
  gcsChannelReady = false;
  if (!gcsControlEnabled) {
    updateGcsStatus('GCS: disabled');
    return;
  }
  updateGcsStatus('GCS: waiting for relay');
}

function transmitRouteToGcs() {
  if (!gcsControlEnabled) {
    updateGcsStatus('GCS: disabled');
    return;
  }

  if (!gcsChannelReady) {
    updateGcsStatus('GCS: relay unavailable');
    return;
  }
  if (waypoints.length < 2) {
    updateGcsStatus('GCS: add at least two waypoints');
    return;
  }
  const altitude = Number.parseFloat(defaultAltitudeInput?.value || '30');
  const boundedAltitude = Number.isFinite(altitude) ? Math.min(Math.max(altitude, 5), 500) : 30;
  const missionWaypoints = waypoints.map((point, index) => ({
    index,
    latitude: point.lat,
    longitude: point.lng,
    altitude: boundedAltitude,
  }));
  const payload = {
    waypoints: missionWaypoints,
    options: {
      altitude: boundedAltitude,
    },
  };
  sendGcsCommand('flight_path', payload, 'GCS: sending mission...');
}

function handleGcsCommandAck(message) {
  if (!gcsControlEnabled) {
    return;
  }
  const payload =
    message && typeof message === 'object' && message.payload && typeof message.payload === 'object'
      ? message.payload
      : {};
  if (payload.error) {
    const code = typeof payload.code === 'string' && payload.code ? payload.code : 'UNKNOWN';
    const action = typeof payload.action === 'string' && payload.action ? `${payload.action}: ` : '';
    updateGcsStatus(`GCS error: ${action}${payload.error} (${code})`);
    return;
  }
  const status = typeof payload.status === 'string' ? payload.status : '';
  const actionDescriptor = typeof payload.action === 'string' && payload.action ? payload.action : '';
  if (status) {
    const descriptor = actionDescriptor ? `${actionDescriptor}: ${status}` : status;
    updateGcsStatus(`GCS: ${descriptor}`);
  } else {
    updateGcsStatus('GCS: acknowledgment received');
  }
}

initialiseWorkflowSync();
attachTabNavigation();
renderAnalysisView();
renderRecordingsList();

refreshActiveAnalysisStatus({ force: true });
if (analysisStatusRefreshTimerId !== null) {
  window.clearInterval(analysisStatusRefreshTimerId);
}
analysisStatusRefreshTimerId = window.setInterval(() => {
  try {
    refreshActiveAnalysisStatus();
  } catch (error) {
    console.warn('Failed to refresh analysis status', error);
  }
}, ANALYSIS_STATUS_REFRESH_INTERVAL_MS);

if (refreshRecordingsButton) {
  refreshRecordingsButton.addEventListener('click', () => {
    refreshRecordingsList();
  });
}

if (recordingsListElement) {
  refreshRecordingsList();
}

registerServiceWorker();
initFlightControlsToggle();
initRoutePlanner();
initGcsControls();
initGcsControlChannel();
