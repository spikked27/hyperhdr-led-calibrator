# Beta validation plan

The beta is not considered ready until the following checks pass.

## Automated

- Android project compiles against API 35.
- JVM unit tests pass.
- Calibration solver returns identity anchors for identity measurements.
- Measured black/ambient offsets are removed before solving.
- Singular/invalid primary matrices are rejected rather than producing garbage output.
- HyperHDR SSDP responses are parsed case-insensitively.
- Non-HyperHDR SSDP devices are ignored.
- A malformed SSDP LOCATION falls back to the UDP sender address.
- Debug APK is produced as a GitHub Actions artifact.

## Protocol review

Implementation is checked against current HyperHDR source behavior:

- Discovery uses SSDP multicast 239.255.255.250:1900.
- HyperHDR replies to ST:ssdp:all and advertises HYPERHDR-JSS-PORT, HYPERHDR-NAME, and LOCATION.
- The JSON server accepts newline-delimited JSON over TCP and replies with newline-delimited compact JSON.
- Calibration colors use HyperHDR's color command and a dedicated temporary priority.
- The temporary priority is cleared after calibration.

## On-device beta checks

These require an actual Android phone and the user's HyperHDR installation and therefore cannot be fully simulated in CI:

1. App requests camera permission cleanly.
2. HyperHDR is found automatically without entering an IP address.
3. Camera preview starts and remains stable for a complete run.
4. White balance stays visually constant through the TV and wall sequences.
5. HyperHDR changes to each requested LED color and clears the temporary priority afterward.
6. Re-running the same measurement setup produces materially similar target values.
7. Calculated ICE values improve visible R/G/B/C/M/Y/W matching rather than making it worse.

The app deliberately does not write persistent HyperHDR settings in beta 0.1. The first real-device run therefore has a safe rollback path: simply clear/exit the app and leave the existing HyperHDR LED calibration untouched.
