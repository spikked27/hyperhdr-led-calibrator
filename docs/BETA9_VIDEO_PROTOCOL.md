# Beta 9 companion video protocol

- 1920×1080, 30 fps reference generator in `tools/generate_beta9_video.py`.
- 8 s initial unmarked BLACK, intended to be paused while the app illuminates the wall and locks the TV border.
- 15 s each: WHITE, RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW.
- 120 s final BLACK.
- Each calibration patch (including final BLACK) carries four black/white marker pairs near the top-left edge.
- Pair 0 is always WHITE|BLACK and validates marker orientation/presence.
- Pairs 1–3 encode the patch index LSB first; bit 1 is WHITE|BLACK and bit 0 is BLACK|WHITE.
- The marker is outside the app's inset screen-color measurement ROI.
- Nominal duration: 3:53.
