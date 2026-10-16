# Setting up a beacon leak

For instructors placing Bluetooth beacons at a training site. A beacon acts as a gas source:
a phone's readings rise as it gets closer. Signal strength is all the phone has to go on, so how
the beacon is set up and placed matters as much as the software.

## 1. Program the beacon (once per beacon)

In the FeasyBeacon app (Feasycom FSC-BP108B):

- **iBeacon slot on**, with the agency UUID, major (kit) and minor (beacon number). Turn the
  other slots off.
- **Interval 200 ms.**
- **Change the PIN** from the default 000000.
- **Transmit power: lower it for indoor rooms.** At full power (0 dBm) the signal fills
  neighbouring rooms. Choose a lower step the app offers (try a few steps down) so the signal
  covers the room and fades fast past its walls. Outdoors, keep the default.

Changing the transmit power changes the signal at every distance, so **calibrate again
afterwards** (step 3).

## 2. Place it

- **Low, where the gas would be.** Most training gases (H2S, LEL vapours) collect low. A beacon
  on or near the floor also loses more signal to furniture and people, which keeps it local.
- **Shield the side facing a wall you don't want it to reach through.** Inside the prop drum,
  or behind a metal plate or foil, the beacon becomes directional: strong into the room, weak
  behind the shield.
- **Each room or area gets its own beacon.** Don't count on one beacon covering two rooms.
- Concrete, block and metal walls stop much more signal than drywall. Thin drywall is the
  hardest case.

## 3. Calibrate on site (on a phone)

On an agency phone in an exercise that includes the beacon: **Settings → Calibrate <beacon>**.

1. **Distances.** Record at 1 m, then 3 m (add 5 m if there's room), with the phone at chest
   height and a clear line to the beacon. Save calibration. A warning means a measurement was
   probably blocked; record it again.
2. **Gas edge.** Stand at the furthest spot where the gas should still read, usually the doorway
   (inside the room). Record edge, then Save gas edge. Below this signal the phone shows no
   reading, so signal through a wall doesn't count.
3. **Check it.** Walk outside the room and along the other side of the walls. The Calibrate
   screen shows **Here: OUTSIDE** where there should be no gas. If a spot outside the wall
   still shows inside, lower the transmit power or add shielding on that side, then calibrate
   again.

Calibration is stored on the beacon and used by every phone in its exercises. It belongs to the
site: moving the beacon to a different room or from outdoors to indoors means calibrating again.

## What to tell trainees

Hold the meter in front of you. A body between the phone and the beacon blocks a lot of signal,
and near the gas edge that can make the reading drop out, much as a real meter held behind your
back would miss the gas.
