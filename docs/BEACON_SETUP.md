# Setting up a beacon leak

For instructors placing Bluetooth beacons at a training site. A beacon acts as a gas source:
a phone's readings rise as it gets closer. Signal strength is all the phone has to go on, so how
the beacon is set up and placed matters as much as the software.

## The beacon

Tested and confirmed working with Sim Sensor:

| Beacon | Notes | Where |
|---|---|---|
| **Feasycom FSC-BP108B** | Waterproof iBeacon tag, replaceable battery | [on Amazon](https://a.co/d/0fHXvmUs) |
| **PDDAXLQUE DX-CP33** | BLE 5.1 beacon (iBeacon and Eddystone), replaceable AAA battery | [on Amazon](https://a.co/d/0bsgVMWy) |

Other iBeacons should work as well, as long as you can set their UUID, major and minor and they
advertise at least a few times a second, but they have **not been tested yet**. Whichever beacon
you use, calibrate it on site (step 3) before training with it.

The steps below use the FSC-BP108B's FeasyBeacon app; other beacons have their own configuration
app with the same settings (iBeacon on, UUID, major, minor, interval, transmit power).

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

To start over, an admin can choose **Reset calibration** next to the beacon under **Devices →
Beacons** in the console. Its power at 1 m goes back to the beacon's own, the environment to the
default (Indoors), and the gas edge is removed. Phones use the reset values straight away, and
each of the beacon's exercises logs it. Then calibrate it again on a phone.

## What to tell trainees

Hold the meter in front of you. A body between the phone and the beacon blocks a lot of signal,
and near the gas edge that can make the reading drop out, much as a real meter held behind your
back would miss the gas.
