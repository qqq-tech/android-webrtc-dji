<a name="readme-top"></a>
<!-- PROJECT LOGO -->
<div align="center">
<h3 align="center">Stream DJI video on Android using WebRTC </h3>
  <p align="center">
    This is a sample to get started with streaming the videofeed of DJI drones straight to your browser with low latency.
    <br />
    <a href="https://github.com/Andreas1331/android-webrtc-dji/tree/main"><strong>Explore the code</strong></a>
      <br />
      <p><i>INFO: This is only tested on DJI Mavic AIR 2S and DJI Mavic 2 Enterprise</i></p>
  </p>
</div>

<!-- TABLE OF CONTENTS -->
<details>
  <summary>Table of Contents</summary>
  <ol>
    <li><a href="#about-the-project">About The Project</a></li>
    <li><a href="#flow-illustration">Flow Illustration</a></li>
    <li><a href="#prerequisites">Prerequisites</a></li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#pre-setup">Pre-setup</a></li>
        <li><a href="#setup">Setup</a></li>
      </ul>
    </li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>


<!-- ABOUT THE PROJECT -->
## About The Project

Originally this library was created for another project, but seeing as I had a hard time finding examples of the exact setup I needed I decided to make my solution public. Hopefully this will help others in utilizing WebRTC to etablish connections between a client residing in a browser and an android application. 

The project consists of two components; one for the browser side and another for the android side. The browser side is to no surprise written in Javascript while the android side is Java. This solution is a one-way transmission of videofeed, meaning the android application is not expecting any video nor audio from the other peer. For my own project I needed video from an arbitrary number of drones to be displayed on an interactive dashboard using React. That is why there's a ```DroneStreamManager``` class on the browser side to handle connecting and closing of streams. On the android side it will not do much more besides keeping track of all its ongoing streams. You can do with that information as you please.

If you are not familiar with WebRTC you should be aware of a third component - the signaling server. I have by choice not included any project for this as you are free to implement that in whatever framework you decide. I will however include the few lines of code making up my own signaling server, as it really does not require much. You can just create a simple Node.JS socket server. The only purpose is to transmit messages between two peers until WebRTC has finalised a connection.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- DEMONSTRATION -->
## Flow Illustration
The flow of information is as follows the client of both the browser and android must be connected to the signaling server. They will send messages to each other through the signaling server in order to start a WebRTC peer-to-peer connection. With the P2P started the android application will then extract the videofeed from the drone and transmit it using the established connection. Beaware if you put your android application to sleep to fly the drone the stream of video will pause as well. So either allow the application to run in the background or do not allow the application to sleep to fix this.
<p align="center"><img src="images/webrtc-android.png"/></p>

## Extended architecture for Mavic + Jetson

This fork adds an end-to-end workflow tailored for DJI Mavic drones using Mobile SDK 4 and the Jetson analytics stack:

- **Android ground control station (GCS)** – the `GCSCommandHandler` routes remote commands (take-off, land, return-to-home, virtual stick, gimbal rotation) from the signaling backend to the DJI flight controller and publishes telemetry/battery/gimbal state in real time.
- **Adaptive video delivery** – `WebRTCClient` now produces SDP/ICE JSON messages with the structure `{ "type": "sdp" | "ice", ... }` and negotiates directly with the bundled [pion](https://github.com/pion/webrtc) relay. At the same time `RawH264TcpStreamer` exposes the camera feed as a custom TCP protocol (magic `DRNH`, frame type, length, frame number) for consumers that prefer raw H.264 access.
- **Pion relay server** – `pion-server/main.go` keeps a publisher/subscriber registry, forwards RTP packets from the Android peer to any number of subscribers (Jetson, browser, etc.) and propagates errors using `{ "error": "...", "code": "..." }` objects.
- **Jetson YOLO pipeline** – `jetson/webrtc_receiver.py` subscribes to the relay, decodes frames with `aiortc`, runs YOLOv8 inference (`yolo_processor.py`) and broadcasts detection metadata via a small WebSocket hub (`websocket_server.py`) using the agreed payload:
  ```json
  {
    "frame_id": 123,
    "detections": [{"bbox": [x, y, width, height], "class": "person", "confidence": 0.98}],
    "timestamp": 1718192900000
  }
  ```
- **Operator dashboard** – `browser/dashboard.html`/`dashboard.js` render the raw WebRTC track on the left and draw YOLO overlays on a canvas on the right. SDP/ICE exchange follows the same JSON format and errors are surfaced to the operator when `error`/`code` is received.

To start the complete flow:

1. Launch the Go relay (`go run pion-server/main.go --addr :8080`).
2. Run the Jetson consumer (`python jetson/webrtc_receiver.py <streamId> --signaling-url ws://<relay-host>:8080/ws`).
3. Open `browser/dashboard.html?streamId=<streamId>&signalingHost=<relay-host>` in your browser to view both feeds.
4. Send GCS commands or raw streaming requests to the Android app through the relay control channel (the dashboard issues these automatically when you use the route planner):
   ```json
   {"type":"gcs_command","payload":{"action":"takeoff"}}
   {"type":"gcs_command","payload":{"action":"virtual_stick","pitch":0.2,"roll":0.0,"yaw":0.0,"throttle":0.1}}
   {"type":"raw_stream","payload":{"action":"start","host":"<jetson-ip>","port":9000}}
   ```

All control/data messages are UTF-8 encoded JSON to keep the interfaces consistent across Android, Jetson, Go and browser components.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Prerequisites
Make sure you have the following pieces ready before wiring everything together:

* An Android application (tested on `minSdkVersion 24`, `targetSdkVersion 30`) bundled with the sources under [`android-application/`](android-application).
* The Go relay in [`pion-server/`](pion-server) running to broker SDP/ICE, telemetry and control messages between peers.
* The optional Jetson pipeline (`jetson/webrtc_receiver.py`) if you want real-time object detection overlays.
* A browser environment that can load [`browser/dashboard.html`](browser/dashboard.html) or your own UI powered by the same JSON protocol.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- GETTING STARTED -->
## Getting Started
In order to get started just follow the steps below. Create your own HTML file with a video tag to stream the videofeed to, or use your existing project. That is up to you.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Component build & run guide

Follow the component-specific notes below to compile and launch each part of the workflow.

### Android ground control station

1. **Import sources** – copy the files from [`android-application/`](android-application) into your Android Studio project (or a Gradle module) and align the package names with your application ID. Ensure the DJI Mobile SDK 4 dependency is declared in `build.gradle`:
   ```gradle
   implementation 'com.dji:dji-sdk:4.16'
   implementation 'org.webrtc:google-webrtc:1.0.+'  // or a pinned version supported by your device
   ```
2. **Configure DJI keys** – register your application on the DJI developer portal, download the `DJISDKLIB` AAR if required, and add your `dji.sdk.key` to `AndroidManifest.xml` together with the required permissions (USB, internet, location, etc.).
3. **Build** – run `./gradlew assembleDebug` (or use the Android Studio *Build > Make Project* action) to produce an APK that contains the GCS logic plus the WebRTC/raw H.264 streamers.
4. **Configure Pion relay** – launch the ground control app, swipe in from the right edge, and choose **Pion 릴레이 설정** to update the signaling URL/stream ID. The values are persisted in shared preferences and applied by the embedded publisher. Legacy Socket.IO endpoints are rewritten to the `/ws` path automatically, so existing field configurations continue to function. If you want to change the shipped defaults before installing the APK, edit `android-application/app/src/main/res/values/strings.xml` (`pion_signaling_url_default`, `pion_stream_id_default`).
5. **Run** – deploy the app to a DJI-supported device, connect the drone, and launch the activity that instantiates `DJIStreamer`. Confirm your signaling server URL, drone ID, and optional raw TCP streaming targets are set before requesting a stream from the browser dashboard.

### Browser dashboard

1. **Install dependencies** – the dashboard uses vanilla HTML/JS; no build step is required. If you already have the Jetson detection broadcaster running (see below) it will now serve the `browser/` directory automatically, so you can simply open `http://<JETSON_HOST>:8765/` and the updated `dashboard.html` will be delivered with all static assets. For standalone testing you can still serve the files with any static file server, e.g.:
   ```bash
   cd browser
   python -m http.server 8081
   ```
2. **Run** – open `http://localhost:8081/dashboard.html?streamId=<STREAM_ID>&signalingHost=<RELAY_HOST>` in Chrome/Firefox. Replace `STREAM_ID` with the identifier used by the Android peer and `RELAY_HOST` with the reachable hostname/IP (including `ws://`/`wss://` if applicable) of the Pion relay. If you already bookmarked an older Socket.IO style endpoint (for example `http://host:8080/socket.io`), pass it via `signalingUrl=` or `signalingHost=` and the dashboard will automatically translate it to the relay's `/ws` endpoint.
3. **Operate** – the left pane displays the raw WebRTC track while the right canvas renders YOLO overlays as soon as the Jetson WebSocket publishes detection JSON. Any `{ "error": "...", "code": "..." }` messages received from the relay will surface in the UI console.

### Jetson analytics service

1. **Create a Python environment** – Python 3.9+ is recommended. On the Jetson run:
   ```bash
   cd jetson
   python3 -m venv venv
   source venv/bin/activate
   pip install --upgrade pip
   pip install -r requirements.txt
   ```
   The requirements file lists `aiortc`, `opencv-python`, `ultralytics`, and supporting packages needed for WebRTC reception and YOLO inference.
2. **Provision YOLO weights** – download or copy the desired YOLOv8 model (e.g., `yolov8n.pt`) into the `jetson/` directory or adjust `yolo_processor.py` to point at your custom weights.
3. **Run** – start the WebSocket broadcast service and WebRTC receiver:
   ```bash
   # Terminal 1 – WebSocket hub for browser overlays and static dashboard assets
   python websocket_server.py --host 0.0.0.0 --port 8765

   # Terminal 2 – subscribe to the Android stream via the Pion relay
   python webrtc_receiver.py <STREAM_ID> \
       --signaling-url ws://<RELAY_HOST>:8080/ws \
       --overlay-ws ws://<JETSON_HOST>:8765 \
       --model yolov8n.pt
   ```
   The receiver relays detection metadata that matches the agreed JSON format, enabling the dashboard to render overlays in real time. With the bundled static file handler you can load the dashboard directly from the broadcaster at `http://<JETSON_HOST>:8765/dashboard.html?streamId=<STREAM_ID>&signalingHost=<RELAY_HOST>`, which already reflects the latest compatibility fixes for serving HTML alongside WebSocket upgrades.
   If you prefer to specify the relay host/port separately, continue using `--signaling-host` and `--signaling-port` (provide them without the `ws://` prefix).

### Twelve Labs video analysis client

Use the helper in [`scripts/twelvelabs_client.py`](scripts/twelvelabs_client.py) to upload videos, create embeddings, and run Twelve Labs' analysis API from the command line. The tool first posts the video to the `/v1.3/embed/tasks` endpoint, waits until it is completed, and then sends the analysis prompt using the returned `video_id`.

```bash
python scripts/twelvelabs_client.py \
    --api-key "$TWELVE_LABS_API_KEY" \
    --model-name Marengo-retrieval-2.7 \
    --video-file /path/to/video.mp4 \
    --prompt "주요 이벤트를 요약해줘" \
    --temperature 0.2 \
    --response-format '{"type":"json_schema","json_schema":{"type":"object","properties":{"summary":{"type":"string"}}}}'
```

You can replace `--video-file` with `--video-url` when the source is already hosted at a publicly accessible address. Flags such as `--video-embedding-scope video`, `--video-clip-length 10`, `--temperature 0.4`, `--analysis-stream`, `--max-tokens 1500`, and `--poll-interval 5` help tune the request. When uploading files the helper gzips the multipart body to match Twelve Labs' reference implementation; opt out with `--disable-upload-gzip` if you need to send raw bytes. The script prints the final embedding/analysis payload to stdout as JSON and falls back to `--analysis-video-id` when the video identifier cannot be derived automatically. To load complex response formats from disk, point `--response-format` to a JSON file instead of inlining the payload.

By default the helper targets the v1.3 embedding task endpoint described at [Create video embedding task](https://docs.twelvelabs.io/v1.3/api-reference/video-embeddings/create-video-embedding-task). If your account is still pinned to a different API revision supply `--embedding-path` (and optionally `--analysis-path`) to override the paths while keeping the rest of the workflow intact. Install the Python dependency with `pip install requests` before running the script.

### Dashboard analysis workflow

The detection broadcaster (`jetson/websocket_server.py`) now exposes a REST endpoint at `/analysis` that reuses the helper library to upload recordings, wait for embeddings, and persist Twelve Labs analysis responses. Results are cached in `recordings/twelvelabs_analysis.json` so a given recording is analysed only once; subsequent requests return the stored payload immediately.

Configure the integration with the following environment variables before launching the Jetson server:

| Variable | Description |
| --- | --- |
| `TWELVE_LABS_API_KEY` | **Required.** Twelve Labs API key used for all requests. |
| `TWELVE_LABS_MODEL_NAME` | Embedding model name (defaults to `Marengo-retrieval-2.7`). |
| `TWELVE_LABS_DEFAULT_PROMPT` | Prompt sent when the dashboard does not override it. |
| `TWELVE_LABS_TEMPERATURE` | Optional temperature for the analysis request. |
| `TWELVE_LABS_MAX_TOKENS` | Optional response token budget. |
| `TWELVE_LABS_EMBED_SCOPE` | Comma-separated embedding scopes (for example `clip,video`). |
| `TWELVE_LABS_DISABLE_UPLOAD_GZIP` | Set to `1` to upload raw bytes instead of gzipping the multipart body. |
| `TWELVE_LABS_CACHE_PATH` | Override the cache file location (defaults to `recordings/twelvelabs_analysis.json`). |

When the environment is configured the “Analyze” button in `browser/dashboard.html` invokes `/analysis?action=start` with the selected recording identifiers. Existing results are surfaced without re-running the pipeline, and the UI reports the stored completion time alongside the generated summary.

### Pion relay server

1. **Install Go toolchain** – Go 1.20+ is recommended.
2. **Download dependencies** – from the repository root run:
   ```bash
   cd pion-server
   go mod tidy
   ```
   This fetches the Pion WebRTC modules and creates `go.sum` on first use.
3. **Build/Run** – to compile a binary execute `go build -o bin/pion-relay main.go`. During development you can run the relay directly:
   ```bash
   go run main.go --addr :8080
   ```
   The server exposes WebSocket endpoints for publishers/subscribers, forwarding SDP/ICE JSON, RTP packets, and propagating structured error responses.
   * To enable HTTPS alongside HTTP, supply both `--tls-cert`/`--tls-key` and an additional bind using `--https-addr :8443`. The relay continues serving insecure WebSockets on `--addr` while the secure listener answers on the TLS port.
   * For quick testing you can generate a self-signed certificate with `scripts/generate-self-signed-cert.sh`. The script writes `certs/server.crt` and `certs/server.key`, which map to the relay flags above and the Jetson WebSocket broadcaster (`--certfile`/`--keyfile`).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- PRE SETUP -->
### Pre-setup

As mentioned before you will have to create your own signaling server, but I recommend a Node.JS socket server. The libraries (browser & android) have two dependencies, so make sure you include these as such:

In your index.html include the script tag
```
<script src="https://webrtc.github.io/adapter/adapter-latest.js"></script>
```
and in your android alter your gradle files. Add ```jcenter()``` as a repository. Then add the implementation to your dependencies.
```
implementation 'org.webrtc:google-webrtc:1.0.+'
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- SETUP -->
### Setup

Copy the folder containing the Java files to your Android application and align the package name with your solution. Instantiate the `DJIStreamer` somewhere it can live for the duration of the flight session:
```
DJIStreamer streamer = new DJIStreamer(this);
```
The streamer now connects directly to the Go relay, so you no longer need to provision a separate Socket.IO backend. The relay delivers SDP/ICE, telemetry and control messages between the dashboard and the drone. When the web UI emits `{ "type": "gcs_command", "payload": { ... } }`, the relay forwards it to the Android device where `GCSCommandHandler` executes the request and responds with `{ "type": "gcs_command_ack", ... }`.

On the browser side you can either reuse [`browser/dashboard.js`](browser/dashboard.js) or follow the same pattern: send JSON envelopes through the relay WebSocket and listen for acknowledgements and telemetry updates.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- LICENSE -->
## License

Distributed under the MIT License. See `LICENSE.txt` for more information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- CONTACT -->
## Contact

Andreas  - **Website to be inserted**

<p align="right">(<a href="#readme-top">back to top</a>)</p>


<!-- ACKNOWLEDGMENTS -->
## Acknowledgments

* [A valueable example for setting up WebRTC on android](https://medium.com/@mehariaabhishek/how-to-use-webrtc-android-sdk-to-share-peer-to-peer-live-data-in-android-34b1aad1f1ba)

<p align="right">(<a href="#readme-top">back to top</a>)</p>
