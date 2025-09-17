const params = new URLSearchParams(window.location.search);
const streamId = params.get('streamId') || 'mavic-stream';
const signalingHost = params.get('signalingHost') || window.location.hostname;
const signalingPort = params.get('signalingPort') || '8080';
const detectionHost = params.get('detectionHost') || signalingHost;
const detectionPort = params.get('detectionPort') || '8765';

const signalingUrl = `ws://${signalingHost}:${signalingPort}/ws?role=subscriber&streamId=${encodeURIComponent(streamId)}`;
const detectionUrl = `ws://${detectionHost}:${detectionPort}/detections`;

const rawVideo = document.getElementById('rawVideo');
const overlayVideo = document.getElementById('overlayVideo');
const overlayCanvas = document.getElementById('overlayCanvas');
const overlayCtx = overlayCanvas.getContext('2d');
const connectionStatus = document.getElementById('connectionStatus');
const detectionStatus = document.getElementById('detectionStatus');
const detectionTimestamp = document.getElementById('detectionTimestamp');
const streamIdLabel = document.getElementById('streamId');
streamIdLabel.textContent = streamId;

let latestDetections = null;

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
  connectionStatus.textContent = 'media-connected';
};

pc.onconnectionstatechange = () => {
  connectionStatus.textContent = pc.connectionState;
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
      connectionStatus.textContent = `error: ${message.code}`;
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
  } catch (error) {
    console.error('Invalid detection payload', error);
  }
});

detectionSocket.addEventListener('close', () => {
  detectionTimestamp.textContent = 'connection lost';
});

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
    const [x, y, width, height] = det.bbox || [];
    if (width <= 0 || height <= 0) {
      return;
    }
    overlayCtx.strokeStyle = 'rgba(56, 189, 248, 0.95)';
    overlayCtx.fillStyle = 'rgba(15, 23, 42, 0.7)';
    overlayCtx.strokeRect(x, y, width, height);

    const label = `${det.class ?? 'object'} ${(det.confidence * 100).toFixed(1)}%`;
    const metrics = overlayCtx.measureText(label);
    const padding = 6;
    const labelY = Math.max(y - 24, 0);
    overlayCtx.fillRect(x, labelY, metrics.width + padding, 24);
    overlayCtx.fillStyle = '#e0f2fe';
    overlayCtx.fillText(label, x + 3, labelY + 2);
  });
}

renderOverlay();
