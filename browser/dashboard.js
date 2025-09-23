const params = new URLSearchParams(window.location.search);
const streamId = params.get('streamId') || 'mavic-stream';
const signalingHost = params.get('signalingHost') || window.location.hostname;
const signalingPort = params.get('signalingPort') || '8080';
const detectionHost = params.get('detectionHost') || signalingHost;
const detectionPort = params.get('detectionPort') || '8765';
const gcsHost = params.get('gcsHost') || signalingHost;
const gcsPort = params.get('gcsPort') || signalingPort;
const gcsProtocol = params.get('gcsProtocol') || (window.location.protocol === 'https:' ? 'https' : 'http');
const gcsPathParam = params.get('gcsPath');

const isSecurePage = window.location.protocol === 'https:';
const signalingProtocol = params.get('signalingProtocol') || (isSecurePage ? 'wss' : 'ws');
const detectionProtocol = params.get('detectionProtocol') || (isSecurePage ? 'wss' : 'ws');

const signalingPortSegment = signalingPort ? `:${signalingPort}` : '';
const detectionPortSegment = detectionPort ? `:${detectionPort}` : '';

const signalingUrl = `${signalingProtocol}://${signalingHost}${signalingPortSegment}/ws?role=subscriber&streamId=${encodeURIComponent(streamId)}`;
const detectionUrl = `${detectionProtocol}://${detectionHost}${detectionPortSegment}/detections`;

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
const gcsStatusLabel = document.getElementById('gcsStatus');
streamIdLabel.textContent = streamId;

let latestDetections = null;
let lastNotificationSummary = '';
let detectionPermissionRequested = false;
let mapInstance = null;
let routePolyline = null;
let waypointMarkers = [];
let waypoints = [];
let gcsSocket = null;
let rawVideoAvailable = false;
let overlayVideoAvailable = false;
const trackedVideoTracks = new WeakSet();

const pc = new RTCPeerConnection({
  iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
});

const signalingSocket = new WebSocket(signalingUrl);

function sendSignalingMessage(payload) {
  if (signalingSocket.readyState === WebSocket.OPEN) {
    signalingSocket.send(JSON.stringify(payload));
  } else {
    console.warn('Signaling socket not open, dropping message', payload);
  }
}

pc.ontrack = (event) => {
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
};

pc.onconnectionstatechange = () => {
  connectionStatus.textContent = pc.connectionState;
  if (['disconnected', 'failed', 'closed'].includes(pc.connectionState)) {
    setRawVideoAvailability(false);
    setOverlayVideoAvailability(false);
  }
};

pc.onicecandidate = (event) => {
  if (event.candidate) {
    sendSignalingMessage({
      type: 'ice',
      candidate: event.candidate.candidate,
      sdpMid: event.candidate.sdpMid,
      sdpMLineIndex: event.candidate.sdpMLineIndex,
    });
  }
};

signalingSocket.addEventListener('open', () => {
  connectionStatus.textContent = 'signaling';
});

signalingSocket.addEventListener('close', () => {
  connectionStatus.textContent = 'disconnected';
});

signalingSocket.addEventListener('message', async (event) => {
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

    if (message.type === 'sdp') {
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
      const candidate = new RTCIceCandidate({
        candidate: message.candidate,
        sdpMid: message.sdpMid || undefined,
        sdpMLineIndex: message.sdpMLineIndex ?? undefined,
      });
      await pc.addIceCandidate(candidate);
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
});

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
  const available = streamHasLiveVideoTrack(stream);
  setRawVideoAvailability(available);
  setOverlayVideoAvailability(available);
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

function initGcsSocket() {
  if (!window.io) {
    if (gcsStatusLabel) {
      gcsStatusLabel.textContent = 'GCS: Socket.IO unavailable';
    }
    return;
  }

  const portSegment = gcsPort ? `:${gcsPort}` : '';
  const origin = `${gcsProtocol}://${gcsHost}${portSegment}`;
  const options = {
    transports: ['websocket'],
  };
  if (gcsPathParam) {
    options.path = gcsPathParam;
  }
  gcsSocket = window.io(origin, options);
  gcsSocket.on('connect', () => {
    if (gcsStatusLabel) {
      gcsStatusLabel.textContent = 'GCS: connected';
    }
  });
  gcsSocket.on('disconnect', () => {
    if (gcsStatusLabel) {
      gcsStatusLabel.textContent = 'GCS: disconnected';
    }
  });
  gcsSocket.on('connect_error', (error) => {
    if (gcsStatusLabel) {
      gcsStatusLabel.textContent = `GCS: ${error?.message || 'connection error'}`;
    }
  });
  gcsSocket.on('gcs_command_ack', (payload) => {
    if (!gcsStatusLabel || !payload) {
      return;
    }
    if (payload.error) {
      gcsStatusLabel.textContent = `GCS error: ${payload.code || 'UNKNOWN'} - ${payload.error}`;
    } else if (payload.status) {
      const descriptor = payload.action ? `${payload.action}: ${payload.status}` : payload.status;
      gcsStatusLabel.textContent = `GCS: ${descriptor}`;
    }
  });
}

function transmitRouteToGcs() {
  if (!gcsSocket || !gcsSocket.connected) {
    if (gcsStatusLabel) {
      gcsStatusLabel.textContent = 'GCS: unavailable';
    }
    return;
  }
  if (waypoints.length < 2) {
    if (gcsStatusLabel) {
      gcsStatusLabel.textContent = 'GCS: add at least two waypoints';
    }
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
    action: 'flight_path',
    waypoints: missionWaypoints,
    createdAt: Date.now(),
    options: {
      altitude: boundedAltitude,
    },
  };
  gcsSocket.emit('gcs_command', payload);
  if (gcsStatusLabel) {
    gcsStatusLabel.textContent = 'GCS: sending mission...';
  }
}

registerServiceWorker();
initRoutePlanner();
initGcsSocket();
