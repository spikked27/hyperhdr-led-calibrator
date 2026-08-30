# Beta 8 field notes → Beta 9 requirements

User field feedback received 2026-08-23:

- Camera preview is still heavily distorted / smushed.
- App can freeze with a camera error if the user exits and returns while calibration is running.
- Automatic TV color recognition is unreliable; RED was repeatedly missed and the video continued while the app remained stuck waiting for RED.
- Investigate whether live recognition is RAW and improve the method.
- Use the known 16:9 TV aspect ratio to improve border detection.
- Preview overlay box must snap to the actual TV border.
- During initial TV-border recognition, leave the backlights ON while the video/TV is BLACK; once the TV border is detected, turn the lights OFF and proceed with calibration.
- Add a **Commit calibration values** button at the end.

Beta 9 branch `beta9-reliable-tracking` implements these requirements. See `docs/BETA9.md` for the technical design.
