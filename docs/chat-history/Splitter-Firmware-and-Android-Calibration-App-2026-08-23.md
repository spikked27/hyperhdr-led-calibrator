# Splitter Firmware and Android Calibration App — Chat Snapshot

- Source: user-provided MHT saved from the ChatGPT shared conversation
- Snapshot URL: `https://chatgpt.com/share/6a8b82e3-cdcc-83ea-8b02-cf37e5bf6985?ogimg=plain`
- MHT save date: 2026-08-23 23:41:12 GMT
- Note: This preserves every user-visible conversation turn rendered in the supplied MHT. Collapsed tool/reasoning panels are represented by their visible summary labels because their full hidden contents are not present in the saved DOM.

## 1. User — `conversation-turn-27`

Uploaded an image

## 2. User — `conversation-turn-39`

Uploaded a file
Uploaded a file

## 3. User — `conversation-turn-45`

Uploaded a file
And this fw 1.06 may help as well, let's do this now

## 4. User — `conversation-turn-51`

Uploaded a file

## 5. User — `conversation-turn-61`

Uploaded an image

## 6. User — `conversation-turn-63`

Uploaded a file
That worked, here's the broken firmware dump

## 7. User — `conversation-turn-65`

Uploaded a file
That's exactly what I gave you, a full firmware dump, here it is again after flashing the v1.06 firmware again

## 8. User — `conversation-turn-87`

Uploaded an image
Uploaded an image

## 9. User — `conversation-turn-103`

Uploaded an image

## 10. User — `conversation-turn-107`

Uploaded an image
What settings should I use in the auto tone mapping section?  30 seconds seems like a long time to wait

## 11. Assistant — `conversation-turn-122`

Worked for 55s
Yes. In fact,
HyperHDR v22’s Infinite Color Engine gives us a much better way to do this than eyeballing yellow, cyan, etc. one at a time.
The HDR LUT calibration we already did corrects the
capture/video path
; what you are asking about now is a separate
physical LED color calibration
.
HyperHDR’s current ICE processing has an LED calibration stage that can correct the primaries with a matrix, or use all
8 anchor colors
—red, green, blue, cyan, magenta, yellow, white, and black—for a more complete correction. Internally, ICE performs this calibration in linear RGB before gamma/brightness/saturation processing.
Best method: measure it instead of eyeballing it
The most accurate practical way is a
colorimeter
. Something like a Calibrite/older X-Rite i1Display can measure the actual chromaticity of:
the corresponding color on your LG screen, and
the light produced by the LEDs on the wall.
Then instead of saying “yellow looks a little green,” we get actual measurements such as:
TV yellow:       x=.419  y=.505
LED-wall yellow: x=.395  y=.545
From measurements of
R, G, B and white
, we can mathematically calculate the correction matrix HyperHDR needs. If we also measure cyan, magenta, and yellow, we can use ICE's more complete LUT-based LED calibration. That is materially better than adjusting each color by eye.
And we can automate almost all of it
The workflow I'd use for your setup is:
Put a full-screen test pattern on the LG.
Fix HyperHDR brightness at one level while calibrating; probably
50%
so we're away from low-level LED nonlinearities and saturation.
Turn off room lights and keep ambient conditions constant.
Measure screen
R, G, B, C, M, Y and white
.
Then command HyperHDR to display those same solid colors and measure the reflected LED light from the wall.
Feed the measurements into a small calculator/script.
It calculates the corrected RGB values to enter into
Image Processing → LED calibration
.
HyperHDR already has the machinery for exactly that kind of correction. Its full calibration mode uses these eight targets:
Red
Green
Blue
Cyan
Magenta
Yellow
White
Black
while its simpler mode derives a 3×3 calibration matrix from just R/G/B.
If you don't own a colorimeter
We can still do a
surprisingly good camera-based version
with your phone, but there's one important requirement: the camera must be completely manual—
fixed white balance, fixed ISO, fixed shutter, fixed exposure, no HDR, no auto color enhancement
. Then photograph the TV patch and the wall illumination separately and extract average RGB/chromaticity from the images.
That would be much more repeatable than looking back and forth and trying to decide whether yellow is too green.
I would
not
use the phone's lux sensor for color calibration. Lux only gives luminance; it tells us nothing useful about whether the LED is too red/green/blue.
One thing I would calibrate separately
Your SK6812
W die
should be handled first, since we just changed ICE so neutral content is primarily produced by W. HyperHDR's RGBW ICE has separate controls for the physical white diode's intensity and white point; its documentation explicitly treats those separately from the normal RGB calibration.
HyperHDR Wiki
So I'd do it in this order:
1. W-channel white point
Get your wall white looking correct with the ICE white settings.
2. W-channel intensity
Set
ICE white LED intensity
so switching between saturated and neutral colors doesn't produce an obvious luminance jump.
3. RGB primary calibration
Measure/correct red, green and blue.
4. C/M/Y check
Measure cyan, magenta and yellow and use the full ICE calibration if the secondaries still miss.
5. Gamma last
Check 25/50/75% patches and tune gamma only after chromaticity is correct.
And importantly,
don't try to make the LED wall physically match the LG's brightness
. They're very different viewing surfaces. We want the
chromaticity/hue to match
, while brightness remains independently controlled by your Home Assistant HDR/SDR brightness automation.
If you have access to a colorimeter—or tell me which one you have—I can give you a very straightforward measurement procedure and then
calculate the actual HyperHDR R/G/B/C/M/Y/W calibration values from the measurements
. That's probably the closest we can get to a genuine automated LED-to-screen calibration.
Sources

## 12. User — `conversation-turn-123`

Can we make a phone app that does this?

## 13. Assistant — `conversation-turn-124`

Yes. We could make an Android app that turns this into a guided calibration instead of manually matching colors.
The practical version would work like this:
The app connects to HyperHDR over your LAN.
It locks the phone camera to fixed exposure, white balance, ISO, shutter, focus, and ideally captures RAW.
It walks you through measuring the TV:
red
green
blue
cyan
magenta
yellow
white
black
You then point the phone at the illuminated wall behind the TV.
The app tells HyperHDR to generate those same colors on the LEDs and measures each one automatically.
It compares TV chromaticity to LED-wall chromaticity.
It calculates the RGB/CMY/white correction values HyperHDR's Infinite Color Engine wants.
It shows a before/after ΔE error and optionally writes the corrected settings directly to HyperHDR.
For your RGBW setup, I'd give it a separate
white-diode calibration step
before the RGB colors. That would tune the W-diode white point and relative intensity first, then calibrate RGB and the secondary colors around it.
The UI could be extremely simple:
HyperHDR LED Calibration

[ Connect to HyperHDR ✓ ]

Camera: Main rear camera
RAW capture: Supported ✓
Exposure: Locked ✓
White balance: Locked ✓

Step 1 of 3
Measure TV colors
[ START ]

Step 2 of 3
Measure LED reflection
[ START ]

Step 3 of 3
Calculated correction

Average ΔE before: 12.6
Average ΔE after:   2.8

[ APPLY TO HYPERHDR ]
There are two ways we could handle the TV test patterns. The easy MVP would have you manually advance through a calibration video/pattern on the Shield while the phone recognizes each patch. A more polished version could include a tiny Android TV/Shield companion app so the phone commands the Shield to display the exact RGB values automatically. Then calibration becomes almost completely hands-off.
The biggest limitation is that a phone camera is
not a true colorimeter
. Its RGB filters don't have standardized spectral response, and Samsung/Apple/etc. camera processing can alter colors. But using RAW sensor data, locked exposure/WB, the same camera for both measurements, and comparing relative chromaticity rather than absolute brightness should make it far more repeatable and accurate than eyeballing yellow against yellow. I would also include an optional calibration-reference mode later for anyone with a known white/gray reference card.
For the math, I'd have the app work in linear RGB/XYZ and CIE Lab, solve the initial 3×3 RGB correction matrix, then refine it using C/M/Y/W measurements. That's a very good match for what HyperHDR's ICE is already doing internally.
And because your brightness is being handled independently, the app should
normalize luminance during the color calculation
. We don't want a 20% HDR bias-light setting to affect the hue calibration.
I'd build this
Android-first
, because it gives us good low-level camera control and it fits your setup well. A sensible project structure would be:
Android app
├── HyperHDR discovery / connection
├── Camera2 RAW measurement
├── TV measurement wizard
├── RGBW LED measurement wizard
├── Color science / ΔE calculation
├── ICE correction solver
├── Before/after charts
└── Apply / export HyperHDR settings
This is very doable. The first version doesn't even need the Shield companion app; we can make a working MVP that connects directly to HyperHDR, performs the camera measurements, calculates the correction, and gives you the exact ICE values to enter. Then we can add direct HyperHDR writing and automated Shield patterns afterward.

## 14. User — `conversation-turn-125`

Let's give it a shot!  Create the app and give it rigorous testing before give me a beta version

## 15. User — `conversation-turn-161`

Uploaded an image
That worked really well, the only issue is that the image preview is heavily distorted no matter which camera is selected, see screenshot.  Camera selection worked great.  The box that's on the preview should fit to the TV screen, the app should also automatically take photos of the screen when the video switches colors and automatically move to LED capture once the video is on a black screen.  Please allow for some movement of the phone during capture so someone can just hold it steady pointed at the screen.  We should also remake the video so it is closely aligned with our capture sequence, for example, needing a long black screen at the end during LED capture.  Really work to make the video and app work flawlessly together.  The app should say start the video now to kick off the automated sequence.
Show more
Show less

## 16. Assistant — `conversation-turn-162`

Inspected image for beta8 implementation
Inspected calibration code, implemented rectangle tracking, and created branches
Downloaded HyperHDR calibration source files
Fetching Remaining MainActivity Code

## 17. User — `conversation-turn-163`

Still there?

## 18. Assistant — `conversation-turn-164`

Yes, I’m here. I’ve started the
Beta 8
work for the fully synchronized app/video workflow. I’m still working through the camera-preview geometry, automatic TV tracking/capture, handheld-motion tolerance, and the long final-black sequence so I don’t hand you another partially tested build.

## 19. User — `conversation-turn-165`

Ok I don't see any progress

## 20. Assistant — `conversation-turn-166`

Compared Beta8 and main branch auto-video changes and progress
Cloned the HyperHDR LED Calibrator repository
Implement Automated TV Calibration Video Workflow
