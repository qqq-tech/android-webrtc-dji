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
4. Send GCS commands or raw streaming requests to the Android app via the Socket.IO channel:
   ```json
   {"action":"takeoff"}
   {"action":"virtual_stick","pitch":0.2,"roll":0.0,"yaw":0.0,"throttle":0.1}
   {"action":"start","host":"<jetson-ip>","port":9000}
   ```

All control/data messages are UTF-8 encoded JSON to keep the interfaces consistent across Android, Jetson, Go and browser components.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Prerequisites
I used Socket.IO throughout all my projects, and you'll need the following at hand to proceed:

* Android application (tested on 'minSdkVersion 24', 'targetSdkVersion 30') 
* Any server that can act as the signaling server
* A website or just any barebone HTML file with a video tag and some Javascript code to invoke the call functions.

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
4. **Configure Pion relay** – launch the ground control app, swipe in from the right edge, and choose **Pion 릴레이 설정** to update the signaling URL/stream ID. The values are persisted in shared preferences and applied by the embedded publisher. If you want to change the shipped defaults before installing the APK, edit `android-application/app/src/main/res/values/strings.xml` (`pion_signaling_url_default`, `pion_stream_id_default`).
5. **Run** – deploy the app to a DJI-supported device, connect the drone, and launch the activity that instantiates `DJIStreamer`. Confirm your signaling server URL, drone ID, and optional raw TCP streaming targets are set before requesting a stream from the browser dashboard.

### Browser dashboard

1. **Install dependencies** – the dashboard uses vanilla HTML/JS; no build step is required. If you already have the Jetson detection broadcaster running (see below) it will now serve the `browser/` directory automatically, so you can simply open `http://<JETSON_HOST>:8765/` and the updated `dashboard.html` will be delivered with all static assets. For standalone testing you can still serve the files with any static file server, e.g.:
   ```bash
   cd browser
   python -m http.server 8081
   ```
2. **Run** – open `http://localhost:8081/dashboard.html?streamId=<STREAM_ID>&signalingHost=<RELAY_HOST>` in Chrome/Firefox. Replace `STREAM_ID` with the identifier used by the Android peer and `RELAY_HOST` with the reachable hostname/IP (including `ws://`/`wss://` if applicable) of the Pion relay.
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

Once you have your Node.JS server with a socket running, add the listener function for the event ```webrtc_msg```. 
```
socket.on("webrtc_msg", (receivee: string, msg: object) => {
    let from_id = socket.id;
    socket.to(receivee).emit('webrtc_msg', from_id, msg);
});
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- SETUP -->
### Setup

Copy the folder containing the Java files to your android application and alter the package name to fit your solution. You will notice that ```SocketConnection``` occurs a few places so modify that to use your own Socket handle. I will not include code for setting up sockets on android either.
Once the code has been merged with your own project you only need to instantiate the ```DJIStreamer``` somewhere it will persist in your application as such:
```
DJIStreamer streamer = new DJIStreamer(this);
``` 
That is it for the android part. You will not need to interact anymore with the instance of ```DJIStreamer```. All calls will be initiated from the browser window, and the android application will automatically accept any incoming calls.

Now the last thing is to include ```WebRTCManager.js``` in your HTML and before attempting to start any videofeed call the setup of socket events:
```
DroneStreamManager.setupSocketEvent(socket);
```
 and once you wish to call a drone in order to get its videofeed you invoke the function as such:
```
const ds = DroneStreamManager.createDroneStream(droneSocketId, videoTagID);
ds.startDroneStream();
```
We let the ```DroneStreamManager``` instantiate an instance of ```DroneStream``` for us and invoke the ```startDroneStream()``` afterwards. Please notice the arguments for creating a drone stream; the socket ID and the video tag ID. The socket ID will be the ID belonging to the android application that is assigned when connecting to our signaling server. This is to let our signaling server know where to pass the message when it receives it. We also provide the function with the ID of the HTML video tag, so the drone stream object knows which DOM element to render the video to once it has it.

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
