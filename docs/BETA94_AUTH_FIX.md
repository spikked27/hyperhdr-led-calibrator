# Beta 9.4 — HyperHDR persistence authorization fix

## Field feedback

After calibration, the app's **Commit calibration values to HyperHDR** action failed and the solved values had to be entered manually in the HyperHDR UI.

## Root cause

The app's live `adjustment` command did not require admin configuration access, so temporary calibration values could be exercised. Persistent configuration is different:

- HyperHDR `config/getconfig` requires admin authorization.
- HyperHDR `config/setconfig` requires admin authorization.
- JSON authorization is connection-scoped.
- The Beta 9.2/9.3 client opened a fresh TCP socket for each request and never authenticated those config sockets.

Therefore the persistent save/read-back path could fail with **No Authorization** even when the calibration values themselves were valid.

## HyperHDR authorization behavior verified from source

- `authorize/login` accepts an admin password or token.
- Passwords must be at least 8 characters.
- Successful password login returns HyperHDR's current user token.
- HyperHDR identifies the longer user token (>36 characters) and authorizes it through the user-token admin path.

## Beta 9.4 behavior

1. A small authorization screen is shown before the existing calibrator.
2. User enters the HyperHDR admin password.
3. The password is held only in process memory and is never saved to preferences/files/logs.
4. When the user selects the HyperHDR instance, `HyperHdrClient` authenticates immediately.
5. Successful password login returns the HyperHDR user token; the password is discarded and only the token remains in process memory.
6. Every admin operation opens a socket and performs `authorize/login` on that same socket before instance selection/config access.
7. `readCalibrationTargets()` performs authenticated `getconfig`.
8. `commitCalibration()` performs authenticated `getconfig -> patch -> setconfig -> getconfig` on one socket.
9. All eight ICE anchors are verified by read-back before Commit reports success.
10. Only after persistence verifies successfully are the same anchors applied live to the running instance.

## Calibration workflow compatibility

This is an authorization/persistence correction only. Beta 9.4 continues to use the Beta 9.3 closed-loop calibration workflow and the same six-minute Beta 9.3 calibration video.
